package com.example.transactions.api;

public record BatchItemResult(
        int index,
        String transactionId,
        String status,
        boolean duplicate,
        String message
) {}
