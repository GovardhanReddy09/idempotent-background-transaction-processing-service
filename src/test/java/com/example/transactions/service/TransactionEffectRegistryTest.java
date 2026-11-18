package com.example.transactions.service;

import com.example.transactions.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionEffectRegistryTest {
    private final CreditEffectStrategy credit = new CreditEffectStrategy();
    private final DebitEffectStrategy debit = new DebitEffectStrategy();

    @Test
    void strategiesApplyCreditAndDebitEffects() {
        TransactionEffectRegistry registry = new TransactionEffectRegistry(List.of(credit, debit));

        assertAmount("15", registry.forType(TransactionType.CREDIT)
                .apply(new BigDecimal("10"), new BigDecimal("5")));
        assertAmount("5", registry.forType(TransactionType.DEBIT)
                .apply(new BigDecimal("10"), new BigDecimal("5")));
    }

    @Test
    void registryRejectsDuplicateStrategies() {
        assertThrows(IllegalStateException.class,
                () -> new TransactionEffectRegistry(List.of(credit, new CreditEffectStrategy(), debit)));
    }

    @Test
    void registryRejectsMissingStrategies() {
        assertThrows(IllegalStateException.class,
                () -> new TransactionEffectRegistry(List.of(credit)));
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
