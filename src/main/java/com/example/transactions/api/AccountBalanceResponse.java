package com.example.transactions.api;

import java.math.BigDecimal;

public record AccountBalanceResponse(String accountId, BigDecimal balance, long nextExpectedSequence) {}
