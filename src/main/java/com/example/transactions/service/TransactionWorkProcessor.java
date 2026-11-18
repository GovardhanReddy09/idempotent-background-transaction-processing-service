package com.example.transactions.service;

import com.example.transactions.model.Account;
import com.example.transactions.model.TransactionRecord;
import com.example.transactions.model.TransactionStatus;
import com.example.transactions.repository.AccountRepository;
import com.example.transactions.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

@Service
public class TransactionWorkProcessor {
    private static final Logger LOGGER = Logger.getLogger(TransactionWorkProcessor.class.getName());
    private static final List<TransactionStatus> ELIGIBLE_STATUSES = List.of(
            TransactionStatus.RECEIVED, TransactionStatus.RETRY_PENDING, TransactionStatus.PENDING_ORDER);

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final TransactionEffectRegistry effects;
    private final RetryBackoff retryBackoff;
    private final int maxAttempts;

    public TransactionWorkProcessor(TransactionRepository transactions, AccountRepository accounts,
            TransactionEffectRegistry effects, RetryBackoff retryBackoff,
            @Value("${app.worker.max-attempts:3}") int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("app.worker.max-attempts must be at least 1");
        }
        this.transactions = transactions;
        this.accounts = accounts;
        this.effects = effects;
        this.retryBackoff = retryBackoff;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public Outcome processOne(Long transactionId) {
        TransactionRecord tx = transactions.findByIdForUpdate(transactionId).orElse(null);
        if (tx == null || !ELIGIBLE_STATUSES.contains(tx.getStatus())) {
            return Outcome.SKIPPED;
        }

        Account account = accounts.findByIdForUpdate(tx.getAccountId())
                .orElseGet(() -> accounts.save(new Account(tx.getAccountId())));
        if (tx.getSequenceNumber() > account.getNextSequence()) {
            tx.setStatus(TransactionStatus.PENDING_ORDER);
            return Outcome.PENDING;
        }
        if (tx.getSequenceNumber() < account.getNextSequence()) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setFailureReason("Stale or conflicting sequence number");
            tx.setNextAttemptAt(null);
            return Outcome.FAILED;
        }

        tx.setStatus(TransactionStatus.PROCESSING);
        tx.incrementAttempts();
        if (tx.getSimulatedFailuresRemaining() > 0) {
            tx.consumeSimulatedFailure();
            tx.setFailureReason("Simulated transient failure");
            if (tx.getAttempts() < maxAttempts) {
                tx.setStatus(TransactionStatus.RETRY_PENDING);
                tx.setNextAttemptAt(Instant.now().plus(retryBackoff.delayAfterAttempt(tx.getAttempts())));
                LOGGER.info(() -> "Retry scheduled attempt=" + tx.getAttempts());
                return Outcome.RETRIED;
            }
            tx.setStatus(TransactionStatus.FAILED);
            tx.setNextAttemptAt(null);
            account.advanceSequence();
            return Outcome.FAILED;
        }

        var newBalance = effects.forType(tx.getTransactionType()).apply(account.getBalance(), tx.getAmount());
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setFailureReason("Insufficient funds");
            tx.setNextAttemptAt(null);
            account.advanceSequence();
            return Outcome.FAILED;
        }

        account.setBalance(newBalance);
        account.advanceSequence();
        tx.setStatus(TransactionStatus.PROCESSED);
        tx.setNextAttemptAt(null);
        tx.setFailureReason(null);
        LOGGER.info("Transaction processed");
        return Outcome.PROCESSED;
    }

    enum Outcome {
        PROCESSED, RETRIED, FAILED, PENDING, SKIPPED
    }
}
