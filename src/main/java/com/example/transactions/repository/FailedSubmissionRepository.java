package com.example.transactions.repository;

import com.example.transactions.model.FailedSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FailedSubmissionRepository extends JpaRepository<FailedSubmission, Long> {
    long countByTransactionId(String transactionId);
}
