package com.example.transactions.repository;

import com.example.transactions.model.DuplicateSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DuplicateSubmissionRepository extends JpaRepository<DuplicateSubmission, Long> {
    long countByTransactionId(String transactionId);
}
