package com.example.transactions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "failed_submissions")
public class FailedSubmission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 80)
    private String transactionId;

    @Column(length = 80)
    private String requestId;

    @Column(nullable = false, length = 1000)
    private String failureReason;

    @Column(nullable = false)
    private Instant attemptedAt;

    protected FailedSubmission() {
    }

    public FailedSubmission(String transactionId, String requestId, String failureReason) {
        this.transactionId = transactionId;
        this.requestId = requestId;
        this.failureReason = failureReason;
        this.attemptedAt = Instant.now();
    }
}
