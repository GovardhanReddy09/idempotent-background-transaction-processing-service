package com.example.transactions.repository;

import com.example.transactions.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface TransactionRepository extends JpaRepository<TransactionRecord, Long> {
    Optional<TransactionRecord> findByTransactionId(String transactionId);
    List<TransactionRecord> findByStatusInOrderByAccountIdAscSequenceNumberAscCreatedAtAscIdAsc(
            List<TransactionStatus> statuses);
    long countByStatus(TransactionStatus status);
}
