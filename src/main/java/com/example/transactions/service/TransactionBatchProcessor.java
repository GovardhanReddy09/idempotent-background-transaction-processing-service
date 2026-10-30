package com.example.transactions.service;

import com.example.transactions.model.Account;
import com.example.transactions.model.TransactionRecord;
import com.example.transactions.model.TransactionStatus;
import com.example.transactions.model.TransactionType;
import com.example.transactions.repository.AccountRepository;
import com.example.transactions.repository.DuplicateSubmissionRepository;
import com.example.transactions.repository.FailedSubmissionRepository;
import com.example.transactions.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Service
public class TransactionBatchProcessor {
    private static final Logger LOGGER = Logger.getLogger(TransactionBatchProcessor.class.getName());

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final DuplicateSubmissionRepository duplicateSubmissions;
    private final FailedSubmissionRepository failedSubmissions;
    private final int maxAttempts;

    public TransactionBatchProcessor(TransactionRepository transactions, AccountRepository accounts,
            DuplicateSubmissionRepository duplicateSubmissions, FailedSubmissionRepository failedSubmissions,
            @Value("${app.worker.max-attempts:3}") int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("app.worker.max-attempts must be at least 1");
        }
        this.transactions = transactions;
        this.accounts = accounts;
        this.duplicateSubmissions = duplicateSubmissions;
        this.failedSubmissions = failedSubmissions;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public Map<String, Object> processAvailable() {
        int processed = 0;
        int retried = 0;
        int failed = 0;
        int evaluated = 0;
        List<TransactionRecord> work = transactions.findByStatusInOrderByAccountIdAscSequenceNumberAscCreatedAtAscIdAsc(
                List.of(TransactionStatus.RECEIVED, TransactionStatus.RETRY_PENDING, TransactionStatus.PENDING_ORDER));

        for (TransactionRecord tx : work) {
            Outcome outcome = processOne(tx);
            evaluated++;
            switch (outcome) {
                case PROCESSED -> processed++;
                case RETRIED -> retried++;
                case FAILED -> failed++;
                case PENDING -> { }
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evaluatedThisRun", evaluated);
        report.put("processedThisRun", processed);
        report.put("retryScheduledThisRun", retried);
        report.put("failedThisRun", failed);
        report.put("waitingForSequence", transactions.countByStatus(TransactionStatus.PENDING_ORDER));
        report.put("statusCounts", statusCounts());
        return report;
    }

    private Outcome processOne(TransactionRecord tx) {
        Account account = accounts.findById(tx.getAccountId())
                .orElseGet(() -> accounts.save(new Account(tx.getAccountId())));
        if (tx.getSequenceNumber() > account.getNextSequence()) {
            tx.setStatus(TransactionStatus.PENDING_ORDER);
            return Outcome.PENDING;
        }
        if (tx.getSequenceNumber() < account.getNextSequence()) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setFailureReason("Stale or conflicting sequence number");
            return Outcome.FAILED;
        }

        tx.setStatus(TransactionStatus.PROCESSING);
        tx.incrementAttempts();
        if (tx.getSimulatedFailuresRemaining() > 0) {
            tx.consumeSimulatedFailure();
            tx.setFailureReason("Simulated transient failure");
            if (tx.getAttempts() < maxAttempts) {
                tx.setStatus(TransactionStatus.RETRY_PENDING);
                LOGGER.info(() -> "Retry scheduled transactionId=" + tx.getTransactionId()
                        + " attempt=" + tx.getAttempts());
                return Outcome.RETRIED;
            }
            tx.setStatus(TransactionStatus.FAILED);
            account.advanceSequence();
            return Outcome.FAILED;
        }

        BigDecimal newBalance = tx.getTransactionType() == TransactionType.CREDIT
                ? account.getBalance().add(tx.getAmount())
                : account.getBalance().subtract(tx.getAmount());
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            tx.setStatus(TransactionStatus.FAILED);
            tx.setFailureReason("Insufficient funds");
            account.advanceSequence();
            return Outcome.FAILED;
        }

        account.setBalance(newBalance);
        account.advanceSequence();
        tx.setStatus(TransactionStatus.PROCESSED);
        tx.setFailureReason(null);
        LOGGER.info(() -> "Transaction processed transactionId=" + tx.getTransactionId()
                + " accountId=" + tx.getAccountId());
        return Outcome.PROCESSED;
    }

    private Map<String, Long> statusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TransactionStatus status : TransactionStatus.values()) {
            if (status == TransactionStatus.DUPLICATE) {
                counts.put(status.name(), duplicateSubmissions.count());
            } else if (status == TransactionStatus.FAILED) {
                counts.put(status.name(), transactions.countByStatus(status) + failedSubmissions.count());
            } else {
                counts.put(status.name(), transactions.countByStatus(status));
            }
        }
        return counts;
    }

    private enum Outcome { PROCESSED, RETRIED, FAILED, PENDING }
}
