package com.example.transactions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "duplicate_submissions")
public class DuplicateSubmission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String transactionId;

    @Column(nullable = false, length = 80)
    private String requestId;

    @Column(nullable = false)
    private Instant attemptedAt;

    protected DuplicateSubmission() {
    }

    public DuplicateSubmission(String transactionId, String requestId) {
        this.transactionId = transactionId;
        this.requestId = requestId;
        this.attemptedAt = Instant.now();
    }
}
