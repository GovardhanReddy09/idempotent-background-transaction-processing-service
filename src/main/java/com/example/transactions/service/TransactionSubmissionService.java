package com.example.transactions.service;

import com.example.transactions.api.TransactionRequest;
import com.example.transactions.model.Account;
import com.example.transactions.model.DuplicateSubmission;
import com.example.transactions.model.TransactionRecord;
import com.example.transactions.model.TransactionStatus;
import com.example.transactions.repository.AccountRepository;
import com.example.transactions.repository.DuplicateSubmissionRepository;
import com.example.transactions.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionSubmissionService {
    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final DuplicateSubmissionRepository duplicates;

    public TransactionSubmissionService(TransactionRepository transactions, AccountRepository accounts,
            DuplicateSubmissionRepository duplicates) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.duplicates = duplicates;
    }

    @Transactional
    public SubmissionResult submit(TransactionRequest request) {
        if (transactions.findByTransactionId(request.transactionId()).isPresent()) {
            return duplicate(request);
        }

        TransactionRecord transaction = transactions.saveAndFlush(new TransactionRecord(
                request.transactionId(),
                request.requestId(),
                request.accountId(),
                request.transactionType(),
                request.amount(),
                request.currency(),
                request.sequenceNumber(),
                Math.max(request.simulateFailureCount(), request.simulateFailureOnce() ? 1 : 0)));
        accounts.findById(request.accountId())
                .orElseGet(() -> accounts.save(new Account(request.accountId())));
        return new SubmissionResult(transaction.getTransactionId(), TransactionStatus.RECEIVED, false, false,
                "Accepted for background processing.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionResult duplicate(TransactionRequest request) {
        TransactionRecord existing = transactions.findByTransactionId(request.transactionId())
                .orElseThrow(() -> new IllegalStateException("Duplicate transaction disappeared before audit."));
        boolean payloadConflict = !sameBusinessPayload(existing, request);
        duplicates.save(new DuplicateSubmission(request.transactionId(), request.requestId(), payloadConflict));
        String message = payloadConflict
                ? "transactionId is already associated with a different business payload."
                : "Business transaction already exists; no second effect will be applied.";
        return new SubmissionResult(request.transactionId(), TransactionStatus.DUPLICATE, true,
                payloadConflict, message);
    }

    private boolean sameBusinessPayload(TransactionRecord existing, TransactionRequest request) {
        return existing.getAccountId().equals(request.accountId())
                && existing.getTransactionType() == request.transactionType()
                && existing.getAmount().compareTo(request.amount()) == 0
                && existing.getCurrency().equals(request.currency())
                && existing.getSequenceNumber() == request.sequenceNumber();
    }
}
