# Execution and results

This guide is a rehearsal script for explaining and demonstrating the idempotent transaction processor. It shows what to type, what a typical response means, and what happens inside the service. Run commands in **PowerShell**. The project requires Java 17+ and Maven 3.9+.

## 1. Build and start from a fresh copy

After cloning the repository or extracting the shared project folder, open PowerShell in that folder and run:

```powershell
java -version
mvn -version
mvn test
mvn clean package
```

The test command runs the unit and Spring integration suite. The package command creates the executable `target\idempotent-transaction-service-1.0.0.jar`; the JAR includes the application dependencies. Continue to section 2 to start the service with a fresh database. Credentials are mandatory; set them in the server window before launching. The service listens on port 8080 by default. Keep that terminal open, then use a second PowerShell terminal for API requests and set the same credentials there. Use either `mvn spring-boot:run` or the JAR command to start the service, not both at once.

## 2. Start clean for a predictable demo

Use two PowerShell terminals. In terminal 1, start a new, timestamped H2 database so old demo values do not affect your results:

```powershell
# Run from the project folder (the directory containing pom.xml).
$env:APP_API_USERNAME = 'demo'
$apiCredential = Get-Credential -UserName $env:APP_API_USERNAME -Message 'Choose the local demo API password'
$env:APP_API_PASSWORD = $apiCredential.GetNetworkCredential().Password
$dbName = "interview-demo-$(Get-Date -Format yyyyMMddHHmmss)"
java -jar .\target\idempotent-transaction-service-1.0.0.jar --server.port=8080 "--spring.datasource.url=jdbc:h2:file:./data/$dbName;DB_CLOSE_ON_EXIT=FALSE"
```

Keep terminal 1 open. Wait until Spring Boot reports that Tomcat started on port 8080. This starts the service and creates an empty database for this run; it does not delete or alter earlier database files. Do not start a second copy if the service is already listening on port 8080.

In terminal 2:

```powershell
# Run from the project folder (the directory containing pom.xml).
$base = 'http://localhost:8080'
$env:APP_API_USERNAME = 'demo'
$apiCredential = Get-Credential -UserName $env:APP_API_USERNAME -Message 'Enter the API password'
$env:APP_API_PASSWORD = $apiCredential.GetNetworkCredential().Password
. .\api-auth.ps1
$authHeaders = Get-ApiAuthorizationHeaders
Invoke-RestMethod -Method Get -Uri "$base/api/processing/summary" -Headers $authHeaders
```

On the fresh database, the summary should initially show zero for each status and `total`. Explain that `RECEIVED` is a *current lifecycle status*, not the cumulative number ever submitted.

## 3. Run the fixed six-row batch sample

The batch demonstrates four unique valid transactions, one duplicate attempt, and one invalid amount. It also submits sequence 2 before sequence 1 on `ACC-201`. `TXN-2005` retains the assessment's sequence number 5 on a new account, so it demonstrates the missing-sequence wait; its simulated retry is configured but does not run.

```powershell
$batch = Get-Content .\sample-transactions.json -Raw
$batchResult = Invoke-RestMethod -Method Post -Uri "$base/api/transactions/batch" -Headers $authHeaders -ContentType 'application/json' -Body $batch
$batchResult | ConvertTo-Json -Depth 8
```

The HTTP response is normally **202 Accepted**. Its top-level values should be:

```text
total     : 6
accepted  : 4
duplicates: 1
rejected  : 1
```

Each `results` entry corresponds to an input row. New valid rows initially show `RECEIVED`; the repeated `TXN-2002` row uses request ID `REQ-2-DUP` and shows `DUPLICATE`; invalid `TXN-2004` shows `FAILED` with an amount-validation reason. `TXN-2004` is retained in the failed-submission audit and counted in the total, but it does not become a business transaction or create `ACC-202`.

### What happened inside the service?

1. The controller accepted the JSON array and the service validated each row separately, so one bad row did not reject the valid rows.
2. The valid unique transactions were stored with status `RECEIVED`. The database uniqueness constraint on `transactionId` and a separate duplicate-audit record prevented the second `TXN-2002` from creating another business effect.
3. The invalid row was recorded in the failed-submission audit. It did not occupy a transaction ID or account sequence.
4. The scheduled processor checks for eligible rows every two seconds. Transient failures are deferred with exponential backoff; the default first retry delay is 1-2 seconds (jittered) and demo commands wait before requesting the second pass.

## 4. Process rows, ordering, and sequence gap

```powershell
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run" -Headers $authHeaders | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run" -Headers $authHeaders | ConvertTo-Json -Depth 8
```

On the first pass, the worker processes `ACC-201` in sequence order: sequence 1 (`TXN-2001`) before sequence 2 (`TXN-2002`), then sequence 3 (`TXN-2003`). `TXN-2005` on `ACC-203` becomes `PENDING_ORDER` because the account expects sequence 1; neither its attempt count nor balance changes. A second pass leaves it pending until sequences 1-4 are submitted. If the two-second scheduled worker runs between commands, a transaction may already have advanced to its next status; that is normal.

The worker response describes that invocation (`evaluatedThisRun`, `processedThisRun`, `retryScheduledThisRun`, `failedThisRun`, and current status counts). These are not a second submission; invoking processing repeatedly does not apply the same credit/debit twice. Each tick reads a bounded set of candidate IDs, then uses transaction-row and account-row locks for concurrent workers sharing the database.

## 5. Check specific transactions, all accounts, and totals

```powershell
Invoke-RestMethod -Method Get -Uri "$base/api/transactions/TXN-2001" -Headers $authHeaders | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Get -Uri "$base/api/transactions/TXN-2002" -Headers $authHeaders | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Get -Uri "$base/api/transactions/TXN-2005" -Headers $authHeaders | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Get -Uri "$base/api/accounts" -Headers $authHeaders | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Get -Uri "$base/api/processing/summary" -Headers $authHeaders | ConvertTo-Json -Depth 8
```

When the sample's processing passes have completed, expect:

| Check | Expected result |
|---|---|
| `TXN-2001`, `TXN-2002`, `TXN-2003` | `PROCESSED` |
| `TXN-2005` | `PENDING_ORDER`, attempts `0`; ACC-203 still expects sequence `1` |
| `TXN-2004` in the batch result | `FAILED`; it is not returned by `GET /api/transactions/{id}` because it is an audited invalid submission, not an accepted business transaction |
| `ACC-201` | Balance `85.00`, next expected sequence `4` (`100` credit - `40` debit + `25` credit) |
| `ACC-203` | Balance `0.00`, next expected sequence `1` |
| Summary | `PROCESSED: 3`, `PENDING_ORDER: 1`, `DUPLICATE: 1`, `FAILED: 1`, `RECEIVED: 0`, `uniqueTransactions: 4`, `total: 6` |

The summary counts **all six submission attempts**: four accepted unique transactions, one duplicate, and one invalid submission. Status totals show their current/final statuses, so a processed transaction is counted under `PROCESSED`, not `RECEIVED`.

### Final output to expect for the fixed sample

After both processing commands complete, this command:

```powershell
Invoke-RestMethod -Method Get -Uri "$base/api/processing/summary" -Headers $authHeaders | ConvertTo-Json
```

should return these summary values on the fresh database:

```json
{
  "RECEIVED": 0,
  "PROCESSING": 0,
  "PROCESSED": 3,
  "DUPLICATE": 1,
  "PENDING_ORDER": 1,
  "RETRY_PENDING": 0,
  "FAILED": 1,
  "uniqueTransactions": 4,
  "total": 6
}
```

`Invoke-RestMethod -Method Get -Uri "$base/api/accounts" -Headers $authHeaders | ConvertTo-Json -Depth 8` should show these two accounts (JSON property order may vary):

```json
[
  {
    "accountId": "ACC-201",
    "balance": 85.00,
    "nextExpectedSequence": 4
  },
  {
    "accountId": "ACC-203",
    "balance": 0.00,
    "nextExpectedSequence": 1
  }
]
```

The accepted transaction lookup commands return the stored transaction and its current status. `TXN-2001` is sequence 1 on `ACC-201` for a 100 INR credit. `TXN-2005` is sequence 5 on `ACC-203` for a 500 INR credit; it has zero processing attempts until that account receives valid sequences 1-4. The simulated retry occurs only after the missing sequence gap is filled. Do not expect `TXN-2004` from the transaction lookup endpoint: that invalid request is available in the batch result as `FAILED` and in the summary's failed count.

The `POST /api/processing/run` response reports work performed in that particular call. A typical run may show `processedThisRun: 3` and `evaluatedThisRun: 4` on the first pass, then `processedThisRun: 0` and `evaluatedThisRun: 1` on the next because the sequence-5 row remains pending. If the scheduled worker processes some rows before the manual command, these per-run figures will differ; the final account balances and summary above remain the expected result.

## 6. Demonstrate individual submissions instead

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

## 7. Explain the core design in the interview

- **Idempotency:** `transactionId` is the business key. Identical retries are audited as duplicates; a reused key with a different business payload returns HTTP 409 and is marked as a payload conflict.
- **Ordering:** sequence numbers start at 1 independently per account. A future sequence waits for the missing sequence; it is not applied out of order.
- **Safe retries:** a simulated transient failure changes no balance or account sequence. The persisted retry deadline uses capped exponential backoff with jitter; attempts are capped by `app.worker.max-attempts` (default 3).
- **Failure isolation:** invalid batch items become audited `FAILED` attempts without blocking valid rows. They do not occupy transaction IDs or account sequences.
- **Atomicity:** account balance, next sequence, and transaction status updates are committed together through the database transaction.
- **Extensibility:** credit and debit balance calculations are separate Spring strategies registered by transaction type. The registry rejects missing or duplicate strategies at startup; sequencing, locking, overdraft checks, and transaction boundaries stay in the shared processor.
- **Operational visibility:** transaction lookup, account endpoints, worker-run output, logs, processing summary, Actuator health probes, JVM/HTTP metrics, and the Prometheus scrape endpoint support inspection and monitoring.
- **Security:** API and monitoring endpoints use HTTP Basic credentials from environment variables; only health status is public. Production needs TLS and managed secrets/identity.
- **Trade-offs:** the assessment uses a scheduled database poller and local H2, not Kafka or a production database. Production still needs migrations, lock-timeout/load testing on its chosen database, monitoring dashboards/alerts, and stronger identity management.

## 8. End the demo

In terminal 1, stop the service with `Ctrl+C`. The timestamped H2 database remains under `data\` for later inspection. Use a new timestamped name in section 1 for another clean demo; no database deletion is required.
