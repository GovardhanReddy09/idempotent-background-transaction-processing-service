package com.example.transactions.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class BackgroundProcessor {
    private final TransactionBatchProcessor batchProcessor;

    public BackgroundProcessor(TransactionBatchProcessor batchProcessor) {
        this.batchProcessor = batchProcessor;
    }

    @Scheduled(fixedDelayString="${app.worker.fixed-delay-ms:2000}")
    public void scheduledRun() {
        processAvailable();
    }

    public synchronized Map<String, Object> processAvailable() {
        return batchProcessor.processAvailable();
    }
}
