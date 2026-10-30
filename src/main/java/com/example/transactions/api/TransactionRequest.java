package com.example.transactions.api;

import com.example.transactions.model.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record TransactionRequest(
        @NotBlank @Size(max = 80) String transactionId,
        @NotBlank @Size(max = 80) String requestId,
        @NotBlank @Size(max = 80) String accountId,
        @NotNull TransactionType transactionType,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Positive long sequenceNumber,
        boolean simulateFailureOnce,
        @Min(0) @Max(10) int simulateFailureCount
) {}
