package com.example.transactions;

import com.example.transactions.api.TransactionRequest;
import com.example.transactions.model.*;
import com.example.transactions.repository.AccountRepository;
import com.example.transactions.repository.DuplicateSubmissionRepository;
import com.example.transactions.repository.FailedSubmissionRepository;
import com.example.transactions.repository.TransactionRepository;
import com.example.transactions.service.SubmissionResult;
import com.example.transactions.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.worker.fixed-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:transactions-test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class TransactionApplicationTests {
    @Autowired
    private TransactionService service;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private DuplicateSubmissionRepository duplicateSubmissions;

    @Autowired
    private FailedSubmissionRepository failedSubmissions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void duplicateBusinessTransactionDoesNotCreateAnotherEffect() {
        String suffix = suffix();
        String id = "DUP-" + suffix;
        String account = "ACC-DUP-" + suffix;
        long duplicateCountBefore = duplicateSubmissions.count();

        assertFalse(service.submit(request(id, "REQ-" + suffix, account, 1,
                TransactionType.CREDIT, "50", false)).duplicate());
        assertTrue(service.submit(request(id, "REQ-DUP-" + suffix, account, 1,
                TransactionType.CREDIT, "500", false)).duplicate());

        service.runProcessingNow();

        assertEquals(1, transactions.findAll().stream()
                .filter(transaction -> transaction.getTransactionId().equals(id)).count());
        assertEquals(1, duplicateSubmissions.countByTransactionId(id));
        assertAmount("50", accounts.findById(account).orElseThrow().getBalance());
        assertEquals(duplicateCountBefore + 1, service.summary().get("DUPLICATE"));
    }

    @Test
    void concurrentDuplicateSubmissionsResolveToOneBusinessTransaction() throws Exception {
        String suffix = suffix();
        String id = "CONCURRENT-" + suffix;
        String account = "ACC-CONCURRENT-" + suffix;
        long duplicateCountBefore = duplicateSubmissions.count();
        TransactionRequest request = request(id, "REQ-" + suffix, account, 1,
                TransactionType.CREDIT, "40", false);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SubmissionResult> first = executor.submit(() -> {
                await(start);
                return service.submit(request);
            });
            Future<SubmissionResult> second = executor.submit(() -> {
                await(start);
                return service.submit(request);
            });
            start.countDown();

            SubmissionResult firstResult = first.get();
            SubmissionResult secondResult = second.get();
            assertNotEquals(firstResult.duplicate(), secondResult.duplicate());
            assertEquals(1, transactions.findAll().stream()
                    .filter(transaction -> transaction.getTransactionId().equals(id)).count());
            assertEquals(1, duplicateSubmissions.countByTransactionId(id));
            assertEquals(duplicateCountBefore + 1, service.summary().get("DUPLICATE"));

            service.runProcessingNow();
            assertAmount("40", accounts.findById(account).orElseThrow().getBalance());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentWorkerTriggersDoNotApplyTheSameTransactionTwice() throws Exception {
        String suffix = suffix();
        String account = "ACC-WORKER-" + suffix;
        String id = "WORKER-" + suffix;
        service.submit(request(id, "REQ-" + suffix, account, 1,
                TransactionType.CREDIT, "60", false));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                await(start);
                service.runProcessingNow();
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                service.runProcessingNow();
            });
            start.countDown();
            first.get();
            second.get();

            TransactionRecord processed = transactions.findByTransactionId(id).orElseThrow();
            assertEquals(TransactionStatus.PROCESSED, processed.getStatus());
            assertEquals(1, processed.getAttempts());
            assertAmount("60", accounts.findById(account).orElseThrow().getBalance());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void outOfOrderTransactionWaitsForMissingSequenceThenProcessesInOrder() {
        String suffix = suffix();
        String account = "ACC-ORDER-" + suffix;
        String secondId = "T2-" + suffix;

        service.submit(request(secondId, "R2-" + suffix, account, 2,
                TransactionType.DEBIT, "10", false));
        service.runProcessingNow();
        assertEquals(TransactionStatus.PENDING_ORDER,
                transactions.findByTransactionId(secondId).orElseThrow().getStatus());

        service.submit(request("T1-" + suffix, "R1-" + suffix, account, 1,
                TransactionType.CREDIT, "30", false));
        service.runProcessingNow();

        assertEquals(TransactionStatus.PROCESSED,
                transactions.findByTransactionId("T1-" + suffix).orElseThrow().getStatus());
        assertEquals(TransactionStatus.PROCESSED,
                transactions.findByTransactionId(secondId).orElseThrow().getStatus());
        assertAmount("20", accounts.findById(account).orElseThrow().getBalance());
    }

    @Test
    void transientFailureIsDeferredAndRetryHasOneBusinessEffect() {
        String suffix = suffix();
        String account = "ACC-RETRY-" + suffix;
        String id = "RETRY-" + suffix;

        service.submit(request(id, "R-" + suffix, account, 1, TransactionType.CREDIT, "75", true));
        service.runProcessingNow();

        TransactionRecord retry = transactions.findByTransactionId(id).orElseThrow();
        assertEquals(TransactionStatus.RETRY_PENDING, retry.getStatus());
        assertEquals(1, retry.getAttempts());
        assertAmount("0", accounts.findById(account).orElseThrow().getBalance());

        service.runProcessingNow();

        TransactionRecord completed = transactions.findByTransactionId(id).orElseThrow();
        assertEquals(TransactionStatus.PROCESSED, completed.getStatus());
        assertEquals(2, completed.getAttempts());
        assertAmount("75", accounts.findById(account).orElseThrow().getBalance());
    }

    @Test
    void repeatedTransientFailuresStopAtConfiguredAttemptLimit() {
        String suffix = suffix();
        String account = "ACC-MAX-RETRY-" + suffix;
        String id = "MAX-RETRY-" + suffix;

        service.submit(new TransactionRequest(id, "REQ-" + suffix, account, TransactionType.CREDIT,
                new BigDecimal("25"), "INR", 1, false, 5));

        service.runProcessingNow();
        service.runProcessingNow();
        service.runProcessingNow();

        TransactionRecord failed = transactions.findByTransactionId(id).orElseThrow();
        assertEquals(TransactionStatus.FAILED, failed.getStatus());
        assertEquals(3, failed.getAttempts());
        assertAmount("0", accounts.findById(account).orElseThrow().getBalance());
        assertEquals(2, accounts.findById(account).orElseThrow().getNextSequence());
    }

    @Test
    void invalidHttpRequestIsRejectedWithoutBlockingValidTransactions() throws Exception {
        String id = "VALID-" + suffix();
        String account = "ACC-VALID-" + suffix();
        long failedCountBefore = failedSubmissions.count();
        long failedTransactionCountBefore = transactions.countByStatus(TransactionStatus.FAILED);
        String invalidRequest = """
                {
                  "transactionId":"%s",
                  "requestId":"REQ-BAD",
                  "accountId":"%s",
                  "transactionType":"CREDIT",
                  "amount":-5,
                  "currency":"INR",
                  "sequenceNumber":1
                }
                """.formatted(id, account);

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request validation failed"));
        assertTrue(transactions.findByTransactionId(id).isEmpty());
        assertEquals(failedCountBefore + 1, failedSubmissions.count());
        assertEquals(failedTransactionCountBefore + failedCountBefore + 1, service.summary().get("FAILED"));

        String validRequest = """
                {
                  "transactionId":"%s",
                  "requestId":"REQ-GOOD",
                  "accountId":"%s",
                  "transactionType":"CREDIT",
                  "amount":10.00,
                  "currency":"INR",
                  "sequenceNumber":1
                }
                """.formatted(id, account);
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        service.runProcessingNow();
        assertAmount("10", accounts.findById(account).orElseThrow().getBalance());
    }

    @Test
    void batchSubmissionProcessesValidRecordsAndReportsDuplicatesAndInvalidRecords() throws Exception {
        String suffix = suffix();
        String account = "ACC-BATCH-" + suffix;
        String firstId = "BATCH-1-" + suffix;
        String secondId = "BATCH-2-" + suffix;
        String invalidId = "BATCH-BAD-" + suffix;
        long duplicateCountBefore = duplicateSubmissions.count();
        long failedCountBefore = failedSubmissions.count();
        long uniqueCountBefore = transactions.count();
        long failedTransactionCountBefore = transactions.countByStatus(TransactionStatus.FAILED);
        String batch = """
                [
                  {
                    "transactionId":"%s",
                    "requestId":"REQ-2-%s",
                    "accountId":"%s",
                    "transactionType":"DEBIT",
                    "amount":10.00,
                    "currency":"INR",
                    "sequenceNumber":2
                  },
                  {
                    "transactionId":"%s",
                    "requestId":"REQ-1-%s",
                    "accountId":"%s",
                    "transactionType":"CREDIT",
                    "amount":50.00,
                    "currency":"INR",
                    "sequenceNumber":1
                  },
                  {
                    "transactionId":"%s",
                    "requestId":"REQ-DUP-%s",
                    "accountId":"%s",
                    "transactionType":"CREDIT",
                    "amount":50.00,
                    "currency":"INR",
                    "sequenceNumber":1
                  },
                  {
                    "transactionId":"%s",
                    "requestId":"REQ-BAD-%s",
                    "accountId":"ACC-BATCH-BAD-%s",
                    "transactionType":"CREDIT",
                    "amount":-5.00,
                    "currency":"INR",
                    "sequenceNumber":1
                  }
                ]
                """.formatted(secondId, suffix, account, firstId, suffix, account, firstId, suffix, account,
                invalidId, suffix, suffix);

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.results[2].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.results[3].status").value("FAILED"));

        assertTrue(transactions.findByTransactionId(invalidId).isEmpty());
        assertEquals(1, duplicateSubmissions.countByTransactionId(firstId));
        assertEquals(failedCountBefore + 1, failedSubmissions.count());
        service.runProcessingNow();

        assertEquals(TransactionStatus.PROCESSED, transactions.findByTransactionId(firstId).orElseThrow().getStatus());
        assertEquals(TransactionStatus.PROCESSED, transactions.findByTransactionId(secondId).orElseThrow().getStatus());
        assertAmount("40", accounts.findById(account).orElseThrow().getBalance());
        assertEquals(duplicateCountBefore + 1, service.summary().get("DUPLICATE"));
        assertEquals(failedTransactionCountBefore + failedCountBefore + 1, service.summary().get("FAILED"));
        assertEquals(uniqueCountBefore + 2, service.summary().get("uniqueTransactions"));
        assertEquals(uniqueCountBefore + duplicateCountBefore + failedCountBefore + 4,
                service.summary().get("total"));
        assertEquals(0L, ((Number) service.summary().get("RECEIVED")).longValue());
        assertFalse(service.submit(request(invalidId, "REQ-CORRECTED-" + suffix,
                "ACC-BATCH-BAD-" + suffix, 1, TransactionType.CREDIT, "5", false)).duplicate());
    }

    @Test
    void accountsEndpointListsAllProcessedAccountBalances() throws Exception {
        String suffix = suffix();
        String firstAccount = "ACC-LIST-A-" + suffix;
        String secondAccount = "ACC-LIST-B-" + suffix;
        service.submit(request("LIST-A-" + suffix, "REQ-A-" + suffix, firstAccount, 1,
                TransactionType.CREDIT, "125", false));
        service.submit(request("LIST-B-" + suffix, "REQ-B-" + suffix, secondAccount, 1,
                TransactionType.CREDIT, "75", false));
        service.runProcessingNow();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.accountId == '%s')].balance".formatted(firstAccount))
                        .value(org.hamcrest.Matchers.contains(125.0)))
                .andExpect(jsonPath("$[?(@.accountId == '%s')].nextExpectedSequence".formatted(firstAccount))
                        .value(org.hamcrest.Matchers.contains(2)))
                .andExpect(jsonPath("$[?(@.accountId == '%s')].balance".formatted(secondAccount))
                        .value(org.hamcrest.Matchers.contains(75.0)));
    }

    @Test
    void failedDebitConsumesItsSequenceSoLaterValidTransactionCanProceed() {
        String suffix = suffix();
        String account = "ACC-FAIL-" + suffix;
        String debitId = "DEBIT-" + suffix;
        String creditId = "CREDIT-" + suffix;

        service.submit(request(debitId, "RD-" + suffix, account, 1,
                TransactionType.DEBIT, "20", false));
        service.submit(request(creditId, "RC-" + suffix, account, 2,
                TransactionType.CREDIT, "30", false));
        service.runProcessingNow();

        assertEquals(TransactionStatus.FAILED,
                transactions.findByTransactionId(debitId).orElseThrow().getStatus());
        assertEquals(TransactionStatus.PROCESSED,
                transactions.findByTransactionId(creditId).orElseThrow().getStatus());
        assertAmount("30", accounts.findById(account).orElseThrow().getBalance());
    }

    private TransactionRequest request(String id, String requestId, String account, long sequence,
            TransactionType type, String amount, boolean failOnce) {
        return new TransactionRequest(id, requestId, account, type, new BigDecimal(amount),
                "INR", sequence, failOnce, failOnce ? 1 : 0);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test submission was interrupted", exception);
        }
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
