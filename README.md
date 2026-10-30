# Idempotent Background Transaction Processing Service

A lightweight transaction ingestion and background-processing service for the FDE L2 practical assessment. It uses Java 17, Spring Boot, Spring Data JPA, and H2; no external queue or database is required.

## Step-by-step implementation

1. **Validate and accept** each transaction over REST. Invalid requests return HTTP 400 (single endpoint) or a per-item `FAILED` result (batch); validation failures are recorded in a separate audit table.
2. **Persist idempotently** using `transactionId` as the business key, backed by a database unique constraint. A repeated key returns `DUPLICATE` and cannot create another business effect.
3. **Order per account** by contiguous sequence numbers beginning at 1. The worker processes an expected sequence, holds future sequences in `PENDING_ORDER`, and fails stale or conflicting sequences.
4. **Apply the business effect atomically** with the status change and account sequence advancement in a database transaction. A debit that would make the balance negative fails but consumes its sequence slot.
5. **Retry transient failures safely.** The demo's `simulateFailureOnce` flag or optional `simulateFailureCount` (0-10) exercises retry paths: a failed attempt applies no balance or sequence change and becomes `RETRY_PENDING`. Attempts are capped by configuration.
6. **Inspect behavior** through transaction lookup, account balance, processing summaries, logs, and focused integration tests.

## Run

Prerequisites: JDK 17+ and Maven 3.9+.

```shell
mvn test
mvn clean package
mvn spring-boot:run
java -jar target/idempotent-transaction-service-1.0.0.jar
```

`mvn clean package` creates the single runnable artifact `target/idempotent-transaction-service-1.0.0.jar`. Run either `mvn spring-boot:run` or the JAR command above (not both on the same port). The service listens on `http://localhost:8080`. Data is stored in the local H2 file `./data/transactions`. The development H2 console is at `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:file:./data/transactions`, user `sa`, blank password).

## API

Submit a credit:

```shell
curl.exe -i -X POST http://localhost:8080/api/transactions `
  -H "Content-Type: application/json" `
  -d "{\"transactionId\":\"TXN-1\",\"requestId\":\"REQ-1\",\"accountId\":\"ACC-1\",\"transactionType\":\"CREDIT\",\"amount\":100.00,\"currency\":\"INR\",\"sequenceNumber\":1}"
```

New requests return HTTP 202 and `RECEIVED`; a duplicate `transactionId` returns HTTP 200 and `DUPLICATE`. The scheduled worker runs every two seconds. Trigger it immediately for a demo:

```shell
curl.exe -X POST http://localhost:8080/api/processing/run
```

Other endpoints:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/transactions` | List stored transactions |
| `GET` | `/api/transactions/{transactionId}` | Inspect one transaction |
| `POST` | `/api/transactions/batch` | Submit up to 500 transaction records in one JSON array |
| `GET` | `/api/processing/summary` | Counts by status and total |
| `GET` | `/api/accounts` | List every account balance and next sequence |
| `GET` | `/api/accounts/{accountId}` | Balance and next expected sequence |

Validation errors return HTTP 400 with `error` and `details`; unknown transaction/account IDs return HTTP 404. Each duplicate attempt is recorded separately from the unique business transaction, so retrying the same ID cannot apply the financial effect twice but still appears in the `DUPLICATE` count. Invalid submissions are audited as `FAILED` attempts without creating business transactions or accounts. In the summary, `uniqueTransactions` counts accepted business transaction records; `total` counts every recorded submission attempt: unique transactions + duplicates + validation failures. `RECEIVED` is a current lifecycle status, not a cumulative intake count, so it normally returns to zero after processing.

### PowerShell workflows: single, batch, sample, and duplicate requests

Open a second PowerShell window while the application is running. Set `$base` to the server's port (`8080`, or `8081` if you started the server on that port). Change to the project folder when you want to load the sample JSON file.

Ready-to-run scripts are also available in the project root: `.\run-sample-batch.ps1` submits `sample-transactions.json`, processes it, and prints the summary/accounts; `.\run-single-transactions.ps1` submits individual sample-style requests (including a duplicate and invalid attempt), processes them, and checks their status. The scripts default to port 8081; pass another base URL if needed, for example `.\run-single-transactions.ps1 -BaseUrl http://localhost:8080`. The batch sample uses fixed transaction IDs, so run it against a fresh database to see all records accepted as new.

#### Submit one transaction

```powershell
$base = 'http://localhost:8081'
$body = '{"transactionId":"TXN-1","requestId":"REQ-1","accountId":"ACC-1","transactionType":"CREDIT","amount":100.00,"currency":"INR","sequenceNumber":1}'
Invoke-RestMethod -Method Post -Uri "$base/api/transactions" -ContentType 'application/json' -Body $body
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run"
Invoke-RestMethod -Method Get -Uri "$base/api/transactions/TXN-1"
Invoke-RestMethod -Method Get -Uri "$base/api/accounts/ACC-1"
Invoke-RestMethod -Method Get -Uri "$base/api/accounts"
```

For the same account, use the next sequence number for each new transaction. Repeating the same `transactionId` is deliberately treated as a duplicate, not a second credit or debit.

#### Submit several records in one JSON array

```powershell
$base = 'http://localhost:8081'
$batch = @'
[
  {"transactionId":"BATCH-1","requestId":"REQ-B1","accountId":"ACC-BATCH","transactionType":"CREDIT","amount":100.00,"currency":"INR","sequenceNumber":1},
  {"transactionId":"BATCH-2","requestId":"REQ-B2","accountId":"ACC-BATCH","transactionType":"DEBIT","amount":25.00,"currency":"INR","sequenceNumber":2}
]
'@
$batchResult = Invoke-RestMethod -Method Post -Uri "$base/api/transactions/batch" -ContentType 'application/json' -Body $batch
$batchResult | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run"
Invoke-RestMethod -Method Get -Uri "$base/api/accounts"
```

#### Submit the supplied `sample-transactions.json`

Run this in a PowerShell window whose current directory is the project folder. The sample intentionally includes a future sequence before the preceding one, a repeated transaction ID, one invalid negative amount, and a one-time simulated retry.

```powershell
Set-Location "$HOME\Downloads\idempotent-transaction-service"
$base = 'http://localhost:8081'
$batch = Get-Content .\sample-transactions.json -Raw
$batchResult = Invoke-RestMethod -Method Post -Uri "$base/api/transactions/batch" -ContentType 'application/json' -Body $batch
$batchResult | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run"
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run"
Invoke-RestMethod -Method Get -Uri "$base/api/processing/summary"
Invoke-RestMethod -Method Get -Uri "$base/api/accounts"
```

The batch endpoint returns a result for each input index. New valid records are accepted independently, repeated IDs are reported as `DUPLICATE`, and Bean Validation failures are reported as `FAILED` without blocking other records. A batch may contain up to 500 items. The worker applies the same sequence and retry rules to single records and batches.

#### Check what a duplicate request does

Submit the *same* body twice, keeping the same `transactionId`:

```powershell
$base = 'http://localhost:8081'
$body = '{"transactionId":"TXN-DUP-DEMO","requestId":"REQ-DUP-1","accountId":"ACC-DUP-DEMO","transactionType":"CREDIT","amount":50.00,"currency":"INR","sequenceNumber":1}'
Invoke-RestMethod -Method Post -Uri "$base/api/transactions" -ContentType 'application/json' -Body $body
Invoke-RestMethod -Method Post -Uri "$base/api/transactions" -ContentType 'application/json' -Body $body
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run"
Invoke-RestMethod -Method Get -Uri "$base/api/accounts"
Invoke-RestMethod -Method Get -Uri "$base/api/processing/summary"
```

The second submission returns `DUPLICATE` and is counted in the summary, but the account balance increases only once. Use a new transaction ID and the next sequence number to make another business transaction.

## Sample input and output

`sample-transactions.json` is an intentionally challenging batch: sequence 2 before sequence 1 on `ACC-201`, a duplicate ID, a negative amount, and a one-time simulated transient failure on `ACC-203`. Sequence numbers start at 1 independently for each account, so the first `ACC-203` transaction uses sequence 1 even though it appears as the sixth input row. `TXN-2004` has sequence 4 as suggested, but its negative amount is rejected before sequencing and it does not create an `ACC-202` account. Submit the whole file to `POST /api/transactions/batch`.

Example accepted response:

```json
{
  "transactionId": "TXN-1",
  "status": "RECEIVED",
  "duplicate": false,
  "message": "Accepted for background processing."
}
```

Example duplicate response:

```json
{
  "transactionId": "TXN-1",
  "status": "DUPLICATE",
  "duplicate": true,
  "message": "Business transaction already exists; no second effect will be applied."
}
```

After processing the sample, the batch result reports `accepted: 4`, `duplicates: 1`, and `rejected: 1`. After the retry pass, `GET /api/processing/summary` reports `PROCESSED: 4`, `DUPLICATE: 1`, `FAILED: 1`, `uniqueTransactions: 4`, and `total: 6` (all six input attempts). `RECEIVED` is `0` because it is a current status, not the total number ever received. `GET /api/accounts` lists all created accounts with balances and next expected sequences. The valid rows affect `ACC-201` (balance `85.00`, next sequence `4`: 100 credit - 40 debit + 25 credit) and `ACC-203` (balance `500.00`, next sequence `2`). The `ACC-202` request is invalid (negative amount), so it is audited as failed and does not create an account.

If `TXN-2005` on the new `ACC-203` account is assigned sequence 5, it correctly remains `PENDING_ORDER`: sequences 1-4 for that account have not been submitted. It will not reach the simulated retry until those missing transactions are supplied. Keep it at sequence 1 to exercise the retry scenario in this standalone sample. The suggested sequence-5 row can be used as a separate gap-ordering test.

## Tests and operational visibility

`mvn test` runs Spring integration tests for sequential and concurrent duplicate idempotency, concurrent worker triggers, batch submission and invalid-record isolation, out-of-order processing, deferred retries without duplicate effects, retry exhaustion, invalid HTTP input isolation, and failed-debit sequence advancement. `GET /api/processing/summary` reports current transaction status counts plus separately audited duplicate and rejected attempts. Worker logs include successful processing and retry scheduling.

## Scope and limitations

This assessment implementation deliberately uses a single-process scheduled database poller. It has no real queue, authentication, or multi-worker coordination. `simulateFailureOnce` is a deterministic demo hook, not a production failure mechanism. H2 schema auto-update and the H2 console are for local development only. See [DESIGN.md](DESIGN.md) for semantics, trade-offs, and production hardening.
