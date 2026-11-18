# Idempotent Background Transaction Processing Service

A lightweight transaction ingestion and background-processing service for the FDE L2 practical assessment. It uses Java 17, Spring Boot, Spring Data JPA, and H2; no external queue or database is required.

## Step-by-step implementation

1. **Validate and accept** each transaction over REST. Invalid requests return HTTP 400 (single endpoint) or a per-item `FAILED` result (batch); validation failures are recorded in a separate audit table.
2. **Persist idempotently** using `transactionId` as the business key, backed by a database unique constraint. An identical repeated key returns `DUPLICATE`; reusing it for a different business payload returns HTTP 409 and cannot create another effect.
3. **Order per account** by contiguous sequence numbers beginning at 1. The worker processes an expected sequence, holds future sequences in `PENDING_ORDER`, and fails stale or conflicting sequences.
4. **Apply the business effect atomically** with the status change and account sequence advancement in a database transaction. A debit that would make the balance negative fails but consumes its sequence slot. Credit/debit arithmetic is provided by registered strategies; the application rejects duplicate or missing strategy registrations at startup.
5. **Retry transient failures safely.** The demo's `simulateFailureOnce` flag or optional `simulateFailureCount` (0-10) exercises retry paths: a failed attempt applies no balance or sequence change and becomes `RETRY_PENDING`. Attempts are capped by configuration.
6. **Inspect behavior** through transaction lookup, account balance, processing summaries, logs, and focused integration tests.

The worker's polling delay, attempt cap, candidate batch size, and retry backoff are configurable in `application.properties`. See [DESIGN.md](DESIGN.md#extending-transaction-behavior) for how to add a transaction-effect strategy without duplicating sequencing or balance-safety rules.

## Run

Prerequisites: JDK 17+ and Maven 3.9+.

The API uses HTTP Basic authentication and the application refuses to start without credentials. In the first PowerShell window, set a username and enter the password securely:

```powershell
$env:APP_API_USERNAME = 'demo'
$apiCredential = Get-Credential -UserName $env:APP_API_USERNAME -Message 'Choose the local demo API password'
$env:APP_API_PASSWORD = $apiCredential.GetNetworkCredential().Password
```

Keep the credential environment variables set while starting the server. In a separate PowerShell window, set the same username and password there too before calling the API or running a demo script. Credentials are supplied through environment variables, not stored in the repository. Use HTTPS and a managed secret store outside local development; Basic authentication over plain HTTP is only appropriate on a trusted local connection.

```shell
mvn test
mvn clean package
mvn spring-boot:run
java -jar target/idempotent-transaction-service-1.0.0.jar
```

`mvn clean package` creates the single runnable artifact `target/idempotent-transaction-service-1.0.0.jar`. Run either `mvn spring-boot:run` or the JAR command above (not both on the same port). The service listens on `http://localhost:8080`. Data is stored in the local H2 file `./data/transactions`. The development H2 console is disabled; use repository/API inspection or explicit database tooling when diagnosing persisted state.

The worker retries transient failures using capped exponential backoff with jitter. Defaults are a 2-second initial delay and a 60-second maximum; configure `app.worker.retry.initial-delay-ms` and `app.worker.retry.max-delay-ms`. The next retry time is persisted, so a not-yet-due retry does not consume worker batch capacity.

Spring Boot Actuator exposes `/actuator/health` publicly with status only, and `/actuator/health/liveness` and `/actuator/health/readiness` for probes. Authenticated operators can access `/actuator/metrics` and `/actuator/prometheus`; the latter can be scraped by Prometheus using the configured API credentials. Only the listed health, info, metrics, and Prometheus endpoints are exposed.

## API

Submit a credit from PowerShell after setting the API credentials in that window:

```powershell
. .\api-auth.ps1
$headers = Get-ApiAuthorizationHeaders
$body = '{"transactionId":"TXN-1","requestId":"REQ-1","accountId":"ACC-1","transactionType":"CREDIT","amount":100.00,"currency":"INR","sequenceNumber":1}'
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/transactions' -Headers $headers -ContentType 'application/json' -Body $body
```

New requests return HTTP 202 and `RECEIVED`; an identical duplicate `transactionId` returns HTTP 200 and `DUPLICATE`. Reusing the key with a different business payload returns HTTP 409 and `payloadConflict: true`. Both outcomes are recorded in the duplicate audit. The scheduled worker runs every two seconds. Trigger it immediately for a demo:

```powershell
. .\api-auth.ps1
$headers = Get-ApiAuthorizationHeaders
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/processing/run' -Headers $headers
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

Ready-to-run scripts are also available in the project root: `.\run-sample-batch.ps1` submits `sample-transactions.json`, processes it, and prints the summary/accounts; `.\run-single-transactions.ps1` submits individual sample-style requests (including a duplicate and invalid attempt), processes it, and checks its status. In that PowerShell window, set `APP_API_USERNAME` and `APP_API_PASSWORD` as described above; the scripts read them from the environment. The scripts default to port 8081; pass another base URL if needed, for example `.\run-single-transactions.ps1 -BaseUrl http://localhost:8080`. The batch sample uses fixed transaction IDs, so run it against a fresh database to see all records accepted as new.

Set the client credentials in that second PowerShell window:

```powershell
$env:APP_API_USERNAME = 'demo'
$apiCredential = Get-Credential -UserName $env:APP_API_USERNAME -Message 'Enter the API password'
$env:APP_API_PASSWORD = $apiCredential.GetNetworkCredential().Password
```

#### Submit one transaction

```powershell
$base = 'http://localhost:8081'
$body = '{"transactionId":"TXN-1","requestId":"REQ-1","accountId":"ACC-1","transactionType":"CREDIT","amount":100.00,"currency":"INR","sequenceNumber":1}'
. .\api-auth.ps1
$headers = Get-ApiAuthorizationHeaders
Invoke-RestMethod -Method Post -Uri "$base/api/transactions" -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/api/transactions/TXN-1" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/api/accounts/ACC-1" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/api/accounts" -Headers $headers
```

For the same account, use the next sequence number for each new transaction. Repeating the same `transactionId` is deliberately treated as a duplicate, not a second credit or debit.

#### Submit several records in one JSON array

```powershell
$base = 'http://localhost:8081'
. .\api-auth.ps1
$headers = Get-ApiAuthorizationHeaders
$batch = @'
[
  {"transactionId":"BATCH-1","requestId":"REQ-B1","accountId":"ACC-BATCH","transactionType":"CREDIT","amount":100.00,"currency":"INR","sequenceNumber":1},
  {"transactionId":"BATCH-2","requestId":"REQ-B2","accountId":"ACC-BATCH","transactionType":"DEBIT","amount":25.00,"currency":"INR","sequenceNumber":2}
]
'@
$batchResult = Invoke-RestMethod -Method Post -Uri "$base/api/transactions/batch" -Headers $headers -ContentType 'application/json' -Body $batch
$batchResult | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/api/accounts" -Headers $headers
```

#### Submit the supplied `sample-transactions.json`

Run this in a PowerShell window whose current directory is the project folder. The sample intentionally includes a future sequence before the preceding one, a repeated transaction ID, one invalid negative amount, and a one-time simulated retry.

```powershell
# Run from the project folder (the directory containing pom.xml).
$base = 'http://localhost:8081'
. .\api-auth.ps1
$headers = Get-ApiAuthorizationHeaders
$batch = Get-Content .\sample-transactions.json -Raw
$batchResult = Invoke-RestMethod -Method Post -Uri "$base/api/transactions/batch" -Headers $headers -ContentType 'application/json' -Body $batch
$batchResult | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run" -Headers $headers
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/api/processing/summary" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/api/accounts" -Headers $headers
```

The batch endpoint returns a result for each input index. New valid records are accepted independently, repeated IDs are reported as `DUPLICATE`, and Bean Validation failures are reported as `FAILED` without blocking other records. A batch may contain up to 500 items. The worker applies the same sequence and retry rules to single records and batches.

For a conflicting key in a batch, that item's result sets `payloadConflict: true` and the response increments `conflicts`; unrelated batch items still receive their own outcomes.

#### Check what a duplicate request does

Submit the *same* body twice, keeping the same `transactionId`:

```powershell
$base = 'http://localhost:8081'
. .\api-auth.ps1
$headers = Get-ApiAuthorizationHeaders
$body = '{"transactionId":"TXN-DUP-DEMO","requestId":"REQ-DUP-1","accountId":"ACC-DUP-DEMO","transactionType":"CREDIT","amount":50.00,"currency":"INR","sequenceNumber":1}'
Invoke-RestMethod -Method Post -Uri "$base/api/transactions" -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Method Post -Uri "$base/api/transactions" -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Method Post -Uri "$base/api/processing/run" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/api/accounts" -Headers $headers
Invoke-RestMethod -Method Get -Uri "$base/api/processing/summary" -Headers $headers
```

The second submission returns `DUPLICATE` and is counted in the summary, but the account balance increases only once. Reuse the ID with a different account, direction, amount, currency, or sequence to get HTTP 409 with `payloadConflict: true`; the original transaction remains unchanged. Use a new transaction ID and the next sequence number to make another business transaction.

## Sample input and output

`sample-transactions.json` is an intentionally challenging batch: sequence 2 before sequence 1 on `ACC-201`, a duplicate ID, a negative amount, and a sequence-5 row on new account `ACC-203` with a one-time retry hook. That last row remains `PENDING_ORDER` because the account expects sequence 1; its hook cannot run until sequences 1-4 are submitted. `TXN-2004` has sequence 4, but its negative amount is rejected before sequencing and it does not create an `ACC-202` account. Submit the whole file to `POST /api/transactions/batch`.

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

After processing the sample, the batch result reports `accepted: 4`, `duplicates: 1`, and `rejected: 1`. The final summary reports `PROCESSED: 3`, `PENDING_ORDER: 1`, `DUPLICATE: 1`, `FAILED: 1`, `uniqueTransactions: 4`, and `total: 6` (all six input attempts). `RECEIVED` is `0` because it is a current status, not the total number ever received. `ACC-201` ends at balance `85.00`, next sequence `4`; `ACC-203` remains at balance `0.00`, next sequence `1`. `TXN-2005` has zero attempts until `ACC-203` receives sequences 1-4, so the simulated retry is intentionally not reached by this sample. The `ACC-202` request is invalid (negative amount), so it is audited as failed and does not create an account.


## Tests and operational visibility

`mvn test` runs Spring integration tests for sequential and concurrent duplicate idempotency, conflicting idempotency payloads, concurrent worker triggers, the original sample file and its sequence-5 gap, batch submission and invalid-record isolation, out-of-order processing, deferred retries without duplicate effects, retry deadlines and exhaustion, authentication, actuator access, invalid HTTP input isolation, and failed-debit sequence advancement. GitHub Actions runs the same test suite on pushes and pull requests. Each tick selects a bounded set of eligible candidates, then uses transaction-row and account-row locks to safely recheck and process work across service instances. `GET /api/processing/summary` reports current transaction status counts plus separately audited duplicate and rejected attempts. Worker logs avoid account IDs.

## Scope and limitations

This assessment implementation uses a scheduled database poller, not a message broker. Database row locks coordinate multiple service instances while the database supports the locking semantics used here; this is not a substitute for production migrations or tuned lock timeouts. HTTP Basic authentication, Actuator health/metrics, Prometheus exposition, and retry backoff are included, but a production deployment still needs TLS termination, a managed identity/secret system, monitoring dashboards and alert rules, and load tests against its selected database. `simulateFailureOnce` is a deterministic demo hook, not a production failure mechanism. H2 schema auto-update is for local development only, and the H2 console is disabled. See [DESIGN.md](DESIGN.md) for semantics, trade-offs, and production hardening.
