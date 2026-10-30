package com.example.transactions.api;

import java.util.List;

public record BatchSubmissionResult(
        int total,
        int accepted,
        int duplicates,
        int rejected,
        List<BatchItemResult> results
) {}
