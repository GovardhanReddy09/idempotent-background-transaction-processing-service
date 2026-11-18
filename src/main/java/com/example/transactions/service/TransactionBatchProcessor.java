package com.example.transactions.service;

import com.example.transactions.model.TransactionStatus;
import com.example.transactions.repository.DuplicateSubmissionRepository;
import com.example.transactions.repository.FailedSubmissionRepository;
import com.example.transactions.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class TransactionBatchProcessor {
    private final TransactionRepository transactions;
    private final DuplicateSubmissionRepository duplicateSubmissions;
    private final FailedSubmissionRepository failedSubmissions;
    private final TransactionWorkProcessor workProcessor;
    private final int batchSize;

    public TransactionBatchProcessor(TransactionRepository transactions,
            DuplicateSubmissionRepository duplicateSubmissions, FailedSubmissionRepository failedSubmissions,
            TransactionWorkProcessor workProcessor, @Value("${app.worker.batch-size:100}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.worker.batch-size must be at least 1");
        }
        this.transactions = transactions;
        this.duplicateSubmissions = duplicateSubmissions;
        this.failedSubmissions = failedSubmissions;
        this.workProcessor = workProcessor;
        this.batchSize = batchSize;
    }

    public Map<String, Object> processAvailable() {
        int processed = 0;
        int retried = 0;
        int failed = 0;
        int evaluated = 0;
        List<Long> work = transactions.findCandidateIds(
                List.of(TransactionStatus.RECEIVED, TransactionStatus.RETRY_PENDING, TransactionStatus.PENDING_ORDER),
                Instant.now(),
                PageRequest.of(0, batchSize));

        for (Long transactionId : work) {
            TransactionWorkProcessor.Outcome outcome = workProcessor.processOne(transactionId);
            if (outcome == TransactionWorkProcessor.Outcome.SKIPPED) {
                continue;
            }
            evaluated++;
            switch (outcome) {
                case PROCESSED -> processed++;
                case RETRIED -> retried++;
                case FAILED -> failed++;
                case PENDING, SKIPPED -> { }
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
}
