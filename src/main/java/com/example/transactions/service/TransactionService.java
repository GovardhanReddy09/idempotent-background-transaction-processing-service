package com.example.transactions.service;

import com.example.transactions.api.AccountBalanceResponse;
import com.example.transactions.api.TransactionRequest;
import com.example.transactions.api.BatchItemResult;
import com.example.transactions.api.BatchSubmissionResult;
import com.example.transactions.model.*;
import com.example.transactions.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import jakarta.validation.Validator;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TransactionService {
    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final BackgroundProcessor processor;
    private final TransactionSubmissionService submissionService;
    private final Validator validator;
    private final DuplicateSubmissionRepository duplicateSubmissions;
    private final FailedSubmissionRepository failedSubmissions;

    public TransactionService(TransactionRepository transactions, AccountRepository accounts,
            BackgroundProcessor processor, TransactionSubmissionService submissionService, Validator validator,
            DuplicateSubmissionRepository duplicateSubmissions, FailedSubmissionRepository failedSubmissions) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.processor = processor;
        this.submissionService = submissionService;
        this.validator = validator;
        this.duplicateSubmissions = duplicateSubmissions;
        this.failedSubmissions = failedSubmissions;
    }

    public SubmissionResult submit(TransactionRequest request) {
        try {
            return submissionService.submit(request);
        } catch (DataIntegrityViolationException exception) {
            if (transactions.findByTransactionId(request.transactionId()).isPresent()) {
                return submissionService.duplicate(request.transactionId(), request.requestId());
            }
            throw exception;
        }
    }

    public BatchSubmissionResult submitBatch(List<TransactionRequest> requests) {
        List<BatchItemResult> results = new ArrayList<>(requests.size());
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;

        for (int index = 0; index < requests.size(); index++) {
            TransactionRequest request = requests.get(index);
            if (request == null) {
                String reason = "Transaction record cannot be null.";
                recordRejectedSubmission(null, reason);
                results.add(new BatchItemResult(index, null, TransactionStatus.FAILED.name(), false, reason));
                rejected++;
                continue;
            }

            String validationErrors = validator.validate(request).stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            if (!validationErrors.isEmpty()) {
                recordRejectedSubmission(request, validationErrors);
                results.add(new BatchItemResult(index, request.transactionId(),
                        TransactionStatus.FAILED.name(), false, validationErrors));
                rejected++;
                continue;
            }

            SubmissionResult result = submit(request);
            results.add(new BatchItemResult(index, request.transactionId(), result.status().name(),
                    result.duplicate(), result.message()));
            if (result.duplicate()) {
                duplicates++;
            } else {
                accepted++;
            }
        }

        return new BatchSubmissionResult(requests.size(), accepted, duplicates, rejected, List.copyOf(results));
    }

    public void recordRejectedSubmission(TransactionRequest request, String reason) {
        failedSubmissions.save(new FailedSubmission(
                request == null ? null : request.transactionId(),
                request == null ? null : request.requestId(),
                reason));
    }

    public List<TransactionRecord> findAll() {
        return transactions.findAll();
    }

    public TransactionRecord findOne(String id) {
        return transactions.findByTransactionId(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found: " + id));
    }

    public Map<String, Object> runProcessingNow() {
        return processor.processAvailable();
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (TransactionStatus status : TransactionStatus.values()) {
            if (status == TransactionStatus.DUPLICATE) {
                result.put(status.name(), duplicateSubmissions.count());
            } else if (status == TransactionStatus.FAILED) {
                result.put(status.name(), transactions.countByStatus(status) + failedSubmissions.count());
            } else {
                result.put(status.name(), transactions.countByStatus(status));
            }
        }
        result.put("uniqueTransactions", transactions.count());
        result.put("total", transactions.count() + duplicateSubmissions.count() + failedSubmissions.count());
        return result;
    }

    public Map<String, Object> accountBalance(String id) {
        Account account = accounts.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Account not found: " + id));
        return Map.of("accountId", id, "balance", account.getBalance(),
                "nextExpectedSequence", account.getNextSequence());
    }

    public List<AccountBalanceResponse> allAccountBalances() {
        return accounts.findAll().stream()
                .sorted(Comparator.comparing(Account::getAccountId))
                .map(account -> new AccountBalanceResponse(account.getAccountId(), account.getBalance(),
                        account.getNextSequence()))
                .toList();
    }
}
