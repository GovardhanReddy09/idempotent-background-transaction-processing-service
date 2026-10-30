package com.example.transactions.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name="accounts")
public class Account {
    @Id private String accountId;
    @Column(nullable=false, precision=19, scale=2) private BigDecimal balance;
    @Column(nullable=false) private long nextSequence;
    protected Account() {}
    public Account(String id) { accountId=id; balance=BigDecimal.ZERO; nextSequence=1; }
    public String getAccountId(){return accountId;} public BigDecimal getBalance(){return balance;}
    public void setBalance(BigDecimal b){balance=b;} public long getNextSequence(){return nextSequence;}
    public void advanceSequence(){nextSequence++;}
}
