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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

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
            return duplicate(request.transactionId(), request.requestId());
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
        return new SubmissionResult(transaction.getTransactionId(), TransactionStatus.RECEIVED, false,
                "Accepted for background processing.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionResult duplicate(String transactionId, String requestId) {
        duplicates.save(new DuplicateSubmission(transactionId, requestId));
        return new SubmissionResult(transactionId, TransactionStatus.DUPLICATE, true,
                "Business transaction already exists; no second effect will be applied.");
    }
}
