package com.example.transactions.service;

import com.example.transactions.model.TransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class CreditEffectStrategy implements TransactionEffectStrategy {
    @Override
    public TransactionType transactionType() {
        return TransactionType.CREDIT;
    }

    @Override
    public BigDecimal apply(BigDecimal currentBalance, BigDecimal amount) {
        return currentBalance.add(amount);
    }
}
