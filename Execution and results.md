# Execution and results

This guide is a rehearsal script for explaining and demonstrating the idempotent transaction processor. It shows what to type, what a typical response means, and what happens inside the service. Run commands in **PowerShell**. The project is configured for Java 17+ and this folder includes the runnable JAR.

## 1. Start clean for a predictable demo

Use two PowerShell terminals. In terminal 1, start a new, timestamped H2 database so old demo values do not affect your results:

```powershell
Set-Location "$HOME\Downloads\idempotent-transaction-service"
$dbName = "interview-demo-$(Get-Date -Format yyyyMMddHHmmss)"
java -jar .\target\idempotent-transaction-service-1.0.0.jar --server.port=8080 "--spring.datasource.url=jdbc:h2:file:./data/$dbName;DB_CLOSE_ON_EXIT=FALSE"
```

Keep terminal 1 open. Wait until Spring Boot reports that Tomcat started on port 8080. This starts the service and creates an empty database for this run; it does not delete or alter earlier database files. Do not start a second copy if the service is already listening on port 8080.

In terminal 2:

```powershell
Set-Location "$HOME\Downloads\idempotent-transaction-service"
$base = 'http://localhost:8080'
Invoke-RestMethod -Method Get -Uri "$base/api/processing/summary"
```

On the fresh database, the summary should initially show zero for each status and `total`. Explain that `RECEIVED` is a *current lifecycle status*, not the cumulative number ever submitted.

## 2. Run the fixed six-row batch sample

The batch demonstrates four unique valid transactions, one duplicate attempt, and one invalid amount. It also submits sequence 2 before sequence 1 on `ACC-201`, and triggers a one-time simulated retry on `ACC-203`.

```powershell
$batch = Get-Content .\sample-transactions.json -Raw
$batchResult = Invoke-RestMethod -Method Post -Uri "$base/api/transactions/batch" -ContentType 'application/json' -Body $batch
$batchResult | ConvertTo-Json -Depth 8
```

The HTTP response is normally **202 Accepted**. Its top-level values should be:

```text
total     : 6
accepted  : 4
duplicates: 1
rejected  : 1
```

Each `results` entry corresponds to an input row. New valid rows initially show `RECEIVED`; the repeated `TXN-2002` row shows `DUPLICATE`; invalid `TXN-2004` shows `FAILED` with an amount-validation reason. `TXN-2004` is retained in the failed-submission audit and counted in the total, but it does not become a business transaction or create `ACC-202`.

### What happened inside the service?

1. The controller accepted the JSON array and the service validated each row separately, so one bad row did not reject the valid rows.
2. The valid unique transactions were stored with status `RECEIVED`. The database uniqueness constraint on `transactionId` and a separate duplicate-audit record prevented the second `TXN-2002` from creating another business effect.
3. The invalid row was recorded in the failed-submission audit. It did not occupy a transaction ID or account sequence.
4. The scheduled processor checks for eligible rows every two seconds. You can trigger it explicitly to make the walkthrough immediate.

## 3. Process rows, ordering, and simulated retry

```powershell
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run" | ConvertTo-Json -Depth 8
```

On the first pass, the worker sorts work by account and sequence. For `ACC-201`, sequence 1 (`TXN-2001`) is processed before sequence 2 (`TXN-2002`), even though sequence 2 appeared first in the input. Sequence 3 (`TXN-2003`) follows. The first attempt for `TXN-2005` is intentionally simulated to fail: it becomes `RETRY_PENDING` without changing the balance or sequence. On the next pass it succeeds. If the two-second scheduled worker runs between commands, a transaction may already have advanced to its next status; that is normal.

The worker response describes that invocation (`evaluatedThisRun`, `processedThisRun`, `retryScheduledThisRun`, `failedThisRun`, and current status counts). These are not a second submission; invoking processing repeatedly does not apply the same credit/debit twice.

## 4. Check specific transactions, all accounts, and totals

```powershell
Invoke-RestMethod -Method Get -Uri "$base/api/transactions/TXN-2001" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Get -Uri "$base/api/transactions/TXN-2002" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Get -Uri "$base/api/transactions/TXN-2005" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Get -Uri "$base/api/accounts" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Get -Uri "$base/api/processing/summary" | ConvertTo-Json -Depth 8
```

When processing and retrying have completed, expect:

| Check | Expected result |
|---|---|
| `TXN-2001`, `TXN-2002`, `TXN-2003`, `TXN-2005` | `PROCESSED` |
| `TXN-2004` in the batch result | `FAILED`; it is not returned by `GET /api/transactions/{id}` because it is an audited invalid submission, not an accepted business transaction |
| `ACC-201` | Balance `85.00`, next expected sequence `4` (`100` credit - `40` debit + `25` credit) |
| `ACC-203` | Balance `500.00`, next expected sequence `2` |
| Summary | `PROCESSED: 4`, `DUPLICATE: 1`, `FAILED: 1`, `RECEIVED: 0`, `uniqueTransactions: 4`, `total: 6` |

The summary counts **all six submission attempts**: four accepted unique transactions, one duplicate, and one invalid submission. Status totals show their current/final statuses, so a processed transaction is counted under `PROCESSED`, not `RECEIVED`.

### Final output to expect for the fixed sample

After both processing commands complete, this command:

```powershell
Invoke-RestMethod -Method Get -Uri "$base/api/processing/summary" | ConvertTo-Json
```

should return these summary values on the fresh database:

```json
{
  "RECEIVED": 0,
  "PROCESSING": 0,
  "PROCESSED": 4,
  "DUPLICATE": 1,
  "PENDING_ORDER": 0,
  "RETRY_PENDING": 0,
  "FAILED": 1,
  "uniqueTransactions": 4,
  "total": 6
}
```

`Invoke-RestMethod -Method Get -Uri "$base/api/accounts" | ConvertTo-Json -Depth 8` should show these two accounts (JSON property order may vary):

```json
[
  {
    "accountId": "ACC-201",
    "balance": 85.00,
    "nextExpectedSequence": 4
  },
  {
    "accountId": "ACC-203",
    "balance": 500.00,
    "nextExpectedSequence": 2
  }
]
```

The accepted transaction lookup commands return a transaction object whose important fields include the requested `transactionId`, final `"status": "PROCESSED"`, and the submitted account, amount, and sequence. For example, `TXN-2001` is sequence 1 on `ACC-201` for a 100 INR credit, and `TXN-2005` is sequence 1 on `ACC-203` for a 500 INR credit. `TXN-2005` should have two processing attempts because its first attempt was intentionally simulated to fail. Do not expect `TXN-2004` from the transaction lookup endpoint: that invalid request is available in the batch result as `FAILED` and in the summary's failed count.

The `POST /api/processing/run` response reports work performed in that particular call. A typical run may show `processedThisRun: 3` and `retryScheduledThisRun: 1` on the first pass, then `processedThisRun: 1` on the next. If the scheduled worker processes some rows before the manual command, these per-run figures will differ; the final account balances and summary above remain the expected result.

## 5. Demonstrate individual submissions instead

For a separate walkthrough, use the script that sends records one at a time. It generates unique IDs per execution, submits an out-of-order sequence, repeats a transaction ID, tries an invalid negative amount, simulates a retry, then fetches each accepted transaction and account summary:

```powershell
.\run-single-transactions.ps1 -BaseUrl $base
```

The script prints each submission response as it is sent. Expect `RECEIVED` for new requests, `DUPLICATE` for the repeated ID, and an expected HTTP 400 message for the invalid request. It then triggers the processor twice and prints the final transactions, accounts, and summary. Since it generates IDs but shares the same database, its summary is cumulative with any earlier demo in that database. Use a fresh database if you want its totals isolated.

For the fixed sample batch, the equivalent script command is:

```powershell
.\run-sample-batch.ps1 -BaseUrl $base
```

The batch script reads `sample-transactions.json`, submits all six rows together, runs the processor twice, then prints transaction results, account balances, and the summary. Its IDs are fixed, so use a fresh database for the expected first-run accepted/duplicate counts.

## 6. Explain the core design in the interview

- **Idempotency:** `transactionId` is the business key. Duplicate attempts are audited separately and cannot apply another financial effect.
- **Ordering:** sequence numbers start at 1 independently per account. A future sequence waits for the missing sequence; it is not applied out of order.
- **Safe retries:** a simulated transient failure changes no balance or account sequence. A later processing pass can retry it. Attempts are capped by `app.worker.max-attempts` (default 3).
- **Failure isolation:** invalid batch items become audited `FAILED` attempts without blocking valid rows. They do not occupy transaction IDs or account sequences.
- **Atomicity:** account balance, next sequence, and transaction status updates are committed together through the database transaction.
- **Operational visibility:** transaction lookup, account endpoints, worker-run output, logs, and the processing summary show what was accepted and how it was handled.
- **Trade-offs:** the assessment uses a scheduled database poller and local H2, not Kafka or a production database. Multi-instance work claiming, authentication, migrations, production retry backoff, and monitoring would need to be added for a production deployment.

## 7. End the demo

In terminal 1, stop the service with `Ctrl+C`. The timestamped H2 database remains under `data\` for later inspection. Use a new timestamped name in section 1 for another clean demo; no database deletion is required.
