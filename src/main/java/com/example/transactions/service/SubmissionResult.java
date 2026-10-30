package com.example.transactions.service;

import com.example.transactions.model.TransactionStatus;

public record SubmissionResult(String transactionId, TransactionStatus status, boolean duplicate, String message) {}
