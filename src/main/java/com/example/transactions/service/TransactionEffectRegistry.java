package com.example.transactions.service;

import com.example.transactions.model.TransactionType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class TransactionEffectRegistry {
    private final Map<TransactionType, TransactionEffectStrategy> strategies;

    public TransactionEffectRegistry(List<TransactionEffectStrategy> strategies) {
        EnumMap<TransactionType, TransactionEffectStrategy> registered = new EnumMap<>(TransactionType.class);
        for (TransactionEffectStrategy strategy : strategies) {
            TransactionEffectStrategy previous = registered.putIfAbsent(strategy.transactionType(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Multiple transaction effect strategies registered for " + strategy.transactionType());
            }
        }
        for (TransactionType type : TransactionType.values()) {
            if (!registered.containsKey(type)) {
                throw new IllegalStateException("No transaction effect strategy registered for " + type);
            }
        }
        this.strategies = Map.copyOf(registered);
    }

    public TransactionEffectStrategy forType(TransactionType type) {
        TransactionEffectStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported transaction type: " + type);
        }
        return strategy;
    }
}
