package com.example.transactions.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transaction_records", uniqueConstraints = @UniqueConstraint(name = "uk_business_transaction", columnNames = "transaction_id"))
public class TransactionRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name="transaction_id", nullable=false, unique=true, length=80)
    private String transactionId;
    @Column(nullable=false, length=80) private String requestId;
    @Column(nullable=false, length=80) private String accountId;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=16) private TransactionType transactionType;
    @Column(nullable=false, precision=19, scale=2) private BigDecimal amount;
    @Column(nullable=false, length=3) private String currency;
    @Column(nullable=false) private long sequenceNumber;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=24) private TransactionStatus status;
    @Column(nullable=false) private int attempts;
    @Column(name="simulate_failure_once", nullable=false) private boolean simulateFailureOnce;
    private Integer simulatedFailuresRemaining;
    @Column(length=500) private String failureReason;
    @Column(nullable=false) private Instant createdAt;
    private Instant updatedAt;

    protected TransactionRecord() {}
    public TransactionRecord(String transactionId, String requestId, String accountId, TransactionType type,
            BigDecimal amount, String currency, long sequenceNumber, int simulatedFailures) {
        this.transactionId=transactionId; this.requestId=requestId; this.accountId=accountId;
        this.transactionType=type; this.amount=amount; this.currency=currency; this.sequenceNumber=sequenceNumber;
        this.simulateFailureOnce = simulatedFailures > 0;
        this.simulatedFailuresRemaining=simulatedFailures; this.status=TransactionStatus.RECEIVED;
        this.attempts=0; this.createdAt=Instant.now(); this.updatedAt=this.createdAt;
    }
    @PreUpdate void touch() { updatedAt=Instant.now(); }
    @PostLoad void restoreLegacyFailureCount() {
        if (simulatedFailuresRemaining == null) {
            simulatedFailuresRemaining = simulateFailureOnce ? 1 : 0;
        }
    }
    public Long getId(){return id;} public String getTransactionId(){return transactionId;}
    public String getRequestId(){return requestId;} public String getAccountId(){return accountId;}
    public TransactionType getTransactionType(){return transactionType;} public BigDecimal getAmount(){return amount;}
    public String getCurrency(){return currency;} public long getSequenceNumber(){return sequenceNumber;}
    public TransactionStatus getStatus(){return status;} public void setStatus(TransactionStatus s){status=s;}
    public int getAttempts(){return attempts;} public void incrementAttempts(){attempts++;}
    public int getSimulatedFailuresRemaining(){
        return simulatedFailuresRemaining == null ? (simulateFailureOnce ? 1 : 0) : simulatedFailuresRemaining;
    }
    public void consumeSimulatedFailure(){
        simulatedFailuresRemaining = getSimulatedFailuresRemaining() - 1;
        simulateFailureOnce = false;
    }
    public String getFailureReason(){return failureReason;} public void setFailureReason(String s){failureReason=s;}
}
