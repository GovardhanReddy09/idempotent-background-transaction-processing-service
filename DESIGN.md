# Design Note

## Architecture

`TransactionController` exposes single-record and batch REST APIs. `TransactionRequest` validates external input before `TransactionService` persists a `TransactionRecord`. Batch submissions validate and persist each record independently and report per-item accepted, duplicate, or failed outcomes. Validation failures are persisted separately as `FailedSubmission` audit rows, so they contribute to totals without occupying the business transaction key or account sequence. Spring Data JPA stores records and account state in H2. `BackgroundProcessor` polls eligible records on a configurable fixed delay, applies business rules, and updates transaction status and account state in a database transaction. There is no queue dependency.

## Idempotency

`transactionId` is the business idempotency key and has a database unique constraint. A request using an existing key receives a `DUPLICATE` response and creates a separate duplicate-attempt audit row; the already-recorded transaction remains the only transaction that can change the account. A unique-key race is caught outside the failed insert transaction and translated into the same duplicate response and audit entry if the winning transaction is visible. The processor applies balance, sequence, and status changes atomically, so a failed attempt that is retried cannot leave a partial balance update.

## Ordering contract

Sequence numbers are contiguous and start at 1 independently for each account. The worker orders eligible rows by account, sequence number, acceptance time, then generated record ID:

- A number greater than `nextSequence` becomes `PENDING_ORDER` and makes no account change.
- A number equal to `nextSequence` is eligible to apply.
- A number lower than `nextSequence` is stale or conflicting and becomes `FAILED`.
- If two distinct transaction IDs claim the same expected sequence, the first accepted record is processed. The later one becomes stale after the first advances the account.
- A valid expected transaction advances `nextSequence`. A permanent business failure such as insufficient funds also consumes that sequence slot so later transactions are not blocked.

This is strict in-order processing rather than arrival-order processing. It intentionally does not infer a missing sequence or skip a gap.

Sequence numbering starts independently at 1 for every account. Thus an isolated first transaction for a new account must use sequence 1 to reach processing and exercise retries. A first transaction submitted with sequence 5 waits in `PENDING_ORDER` until valid transactions for sequences 1 through 4 arrive; an invalid record at another account/sequence does not fill that gap.

## Retries and failures

Each processing attempt increments `attempts`. `simulateFailureOnce` (or `simulateFailureCount`, a bounded 0-10 test hook) makes the next attempt fail deterministically. Each simulated failure sets `RETRY_PENDING` and changes neither balance nor sequence. A later worker pass may retry it. `app.worker.max-attempts` (default 3) caps attempts; a transaction still failing at the cap is marked `FAILED` and consumes its sequence. The default sample models one transient failure and succeeds on attempt two; the count option exercises the cap. A real downstream integration should classify retryable versus permanent errors and use backoff.

Invalid REST requests are rejected by Bean Validation before business-transaction persistence, and an error response identifies validation failure. The failed submission is retained in the audit table, does not occupy a transaction ID or account sequence, and does not prevent other valid requests from processing. Insufficient funds is a business failure recorded on its accepted transaction as `FAILED`, not an HTTP validation error.

The batch endpoint accepts up to 500 transaction objects per JSON array. It validates each item independently: invalid items are reported as `FAILED` and audited, duplicates as `DUPLICATE` and audited, and new items as `RECEIVED`. Valid items are enqueued even when another item in that batch is invalid. The worker processes accepted records using the same ordering and retry rules as single submissions.

## Consistency and concurrency

The worker's transaction keeps its database updates atomic. The account row is the source of truth for both balance and next sequence. The in-process worker facade serializes scheduled and manually triggered runs so they cannot apply the same row concurrently. The unique constraint is the last line of defense for concurrent duplicate inserts. This demo assumes a single worker process; concurrent worker instances would need account row locking or optimistic versioning plus safe work claiming. Submission and processing must share the same database for the current consistency guarantee.

## Observability

`GET /api/processing/summary` reports current transaction status counts and audited duplicate and validation-failure attempts. `uniqueTransactions` counts accepted business transaction records; `total` is unique records + duplicate attempts + invalid submission attempts, so it includes every recorded input attempt. `RECEIVED` is a current lifecycle status, not a cumulative count of records ever submitted. `GET /api/accounts` lists all created accounts with their current balances and next sequence, while `GET /api/transactions/{transactionId}` and `GET /api/accounts/{accountId}` expose individual transaction/account state. `PROCESSING` is a transient state within the atomic worker transaction. Processing and retry events are logged. This is adequate for a local assessment but is not a substitute for production metrics, structured logs, tracing, or alerts.

## Trade-offs and production hardening

A scheduled database poller is simpler than introducing Kafka and meets the assessment's lightweight constraint. Its limitations are polling latency, single-instance assumptions, and the absence of durable retry scheduling beyond status persistence. Production work should include:

- database migrations, account locking/versioning, multi-worker claiming and lease recovery;
- retry backoff with jitter, retryable/permanent error classification, and a dead-letter workflow;
- an immutable ledger and reconciliation rather than relying only on a mutable balance;
- authentication/authorization, rate limits, request size limits, and production-safe database configuration;
- metrics, tracing, structured logs, operational dashboards, and alerts;
- an outbox/inbox pattern and idempotent downstream consumers for external side effects.

The H2 console and Hibernate schema auto-update are development conveniences and must be disabled/replaced for production.

## AI-assisted development

AI assistance was used to scaffold and iteratively review the service, identify acceptance scenarios, and produce tests/documentation. The final implementation should be reviewed and run by its owner, who should be able to explain idempotency, sequence semantics, transaction boundaries, retry behavior, and the production limitations.
