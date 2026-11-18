# Design Note

## Architecture

`TransactionController` exposes single-record and batch REST APIs. `TransactionRequest` validates external input before `TransactionService` persists a `TransactionRecord`. Batch submissions validate and persist each record independently and report per-item accepted, duplicate, or failed outcomes. Validation failures are persisted separately as `FailedSubmission` audit rows, so they contribute to totals without occupying the business transaction key or account sequence. Spring Data JPA stores records and account state in H2. `BackgroundProcessor` polls eligible records on a configurable fixed delay, applies business rules, and updates transaction status and account state in a database transaction. Balance effects are supplied by registered `TransactionEffectStrategy` beans; startup fails if a transaction type has zero or multiple strategies. There is no queue dependency.

## Extending transaction behavior

Worker delay, maximum attempts, and candidate batch size are configured in `application.properties`. To add a transaction effect, add its persisted `TransactionType` and provide exactly one Spring `TransactionEffectStrategy` that computes a new balance from the current balance and validated amount. The registry checks strategy coverage and uniqueness at startup; sequencing, locking, overdraft checks, and transaction boundaries remain in the shared worker path. This keeps type-specific balance arithmetic open to extension without duplicating safety rules. Adding a type that also changes API validation, persistence constraints, or account semantics still requires corresponding tests and a reviewed schema migration.

## Idempotency

`transactionId` is the business idempotency key and has a database unique constraint. An existing key with the same account, transaction type, amount, currency, and sequence returns `DUPLICATE`; a different business payload returns HTTP 409 and sets `payloadConflict`. Request IDs and the demo-only failure hook are not business payload fields. Both outcomes create a duplicate-attempt audit row, including a conflict flag; the original transaction remains the only one that can change the account. A unique-key race is caught outside the failed insert transaction and translated into the same duplicate/conflict response and audit entry if the winning transaction is visible. The processor applies balance, sequence, and status changes atomically, so a failed attempt that is retried cannot leave a partial balance update.

## Ordering contract

Sequence numbers are contiguous and start at 1 independently for each account. Within new/retry work and pending-gap work, the worker orders candidates by account, sequence number, acceptance time, then generated record ID:

- A number greater than `nextSequence` becomes `PENDING_ORDER` and makes no account change.
- A number equal to `nextSequence` is eligible to apply.
- A number lower than `nextSequence` is stale or conflicting and becomes `FAILED`.
- If two distinct transaction IDs claim the same expected sequence, the first accepted record is processed. The later one becomes stale after the first advances the account.
- A valid expected transaction advances `nextSequence`. A permanent business failure such as insufficient funds also consumes that sequence slot so later transactions are not blocked.

This is strict in-order processing rather than arrival-order processing. It intentionally does not infer a missing sequence or skip a gap.

Sequence numbering starts independently at 1 for every account. Thus an isolated first transaction for a new account must use sequence 1 to reach processing and exercise retries. A first transaction submitted with sequence 5 waits in `PENDING_ORDER` until valid transactions for sequences 1 through 4 arrive; an invalid record at another account/sequence does not fill that gap. The supplied sample intentionally preserves `TXN-2005` at sequence 5, so its simulated retry is not attempted during the sample run.

## Retries and failures

Each processing attempt increments `attempts`. `simulateFailureOnce` (or `simulateFailureCount`, a bounded 0-10 test hook) makes the next attempt fail deterministically. Each simulated failure sets `RETRY_PENDING` and changes neither balance nor sequence. A later worker pass may retry it. `app.worker.max-attempts` (default 3) caps attempts; a transaction still failing at the cap is marked `FAILED` and consumes its sequence. An isolated sequence-1 transaction can model a transient failure and succeed on attempt two; the count option exercises the cap. The supplied sample's sequence-5 transaction does not reach that hook until its sequence gap is filled. A real downstream integration should classify retryable versus permanent errors and use backoff.

Invalid REST requests are rejected by Bean Validation before business-transaction persistence, and an error response identifies validation failure. The failed submission is retained in the audit table, does not occupy a transaction ID or account sequence, and does not prevent other valid requests from processing. Insufficient funds is a business failure recorded on its accepted transaction as `FAILED`, not an HTTP validation error.

The batch endpoint accepts up to 500 transaction objects per JSON array. It validates each item independently: invalid items are reported as `FAILED` and audited, duplicates as `DUPLICATE` and audited, and new items as `RECEIVED`. Valid items are enqueued even when another item in that batch is invalid. The worker processes accepted records using the same ordering and retry rules as single submissions.

## Consistency and concurrency

Each worker tick selects at most `app.worker.batch-size` candidate IDs, ordered so new/retry work is considered before sequence-gap rows. Each candidate is reloaded under a pessimistic transaction-row lock and rechecked for an eligible status; its account row is locked before checking the sequence and updating balance/sequence. Transaction and account changes commit together for each item. Competing instances may select the same ID, but only one can hold its transaction lock; after waiting, the other sees the committed status and skips it. The account lock serializes distinct transaction IDs for the same account and prevents concurrent balance/sequence updates. Locks are released on commit or rollback, so a process crash does not leave a durable claim. The unique constraint remains the last line of defense for concurrent duplicate inserts. This assumes all workers share a database with compatible pessimistic-lock semantics. A production database should be load-tested for lock contention and timeout behavior.

## Observability

`GET /api/processing/summary` reports current transaction status counts and audited duplicate and validation-failure attempts. `uniqueTransactions` counts accepted business transaction records; `total` is unique records + duplicate attempts + invalid submission attempts, so it includes every recorded input attempt. `RECEIVED` is a current lifecycle status, not a cumulative count of records ever submitted. `GET /api/accounts` lists all created accounts with their current balances and next sequence, while `GET /api/transactions/{transactionId}` and `GET /api/accounts/{accountId}` expose individual transaction/account state. `PROCESSING` is a transient state within the atomic worker transaction. Worker logs record outcome and attempt count without transaction or account identifiers. The H2 console is disabled. This is adequate for a local assessment but is not a substitute for production metrics, structured logs, tracing, authentication, or alerts.

## Trade-offs and production hardening

A scheduled database poller is simpler than introducing Kafka and meets the assessment's lightweight constraint. Its limitations are polling latency, database lock contention, and the absence of durable retry scheduling beyond status persistence. Production work should include:

- database migrations, lock-timeout policy, and load testing for multi-worker contention;
- retry backoff with jitter, retryable/permanent error classification, and a dead-letter workflow;
- an immutable ledger and reconciliation rather than relying only on a mutable balance;
- authentication/authorization, rate limits, request size limits, and production-safe database configuration;
- metrics, tracing, structured logs, operational dashboards, and alerts;
- an outbox/inbox pattern and idempotent downstream consumers for external side effects.

The H2 console and Hibernate schema auto-update are development conveniences and must be disabled/replaced for production.

## AI-assisted development

AI assistance was used to translate supplied review feedback into payload-conflict handling, per-record database locking, bounded polling, the transaction-effect strategy extension point, sample regression tests, and matching runbook updates. No LLM is called by the service and no agent or external graph store is included; AI-assisted development is documented separately from product AI capabilities. The original sample data was retained rather than modified to make its sequence-5 transaction process: under the documented contiguous-per-account rule it correctly stays pending. The project owner remains responsible for running the tests, checking behavior against the assessment brief, and reviewing database-specific lock behavior before any production use.
