package com.example.transactions.repository;

import com.example.transactions.model.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface TransactionRepository extends JpaRepository<TransactionRecord, Long> {
    Optional<TransactionRecord> findByTransactionId(String transactionId);
    @Query("""
            select t.id from TransactionRecord t
            where t.status in :statuses
            order by case when t.status = com.example.transactions.model.TransactionStatus.PENDING_ORDER
                          then 1 else 0 end,
                     t.accountId asc, t.sequenceNumber asc, t.createdAt asc, t.id asc
            """)
    List<Long> findCandidateIds(@Param("statuses") Collection<TransactionStatus> statuses, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionRecord t where t.id = :id")
    Optional<TransactionRecord> findByIdForUpdate(@Param("id") Long id);

    long countByStatus(TransactionStatus status);
}
