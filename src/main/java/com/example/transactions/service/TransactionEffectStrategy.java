package com.example.transactions.service;

import com.example.transactions.model.TransactionType;

import java.math.BigDecimal;

public interface TransactionEffectStrategy {
    TransactionType transactionType();

    BigDecimal apply(BigDecimal currentBalance, BigDecimal amount);
}
