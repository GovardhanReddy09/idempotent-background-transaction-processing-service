package com.example.transactions.api;

import com.example.transactions.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransactionController {
    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping("/transactions")
    public ResponseEntity<?> submit(@Valid @RequestBody TransactionRequest request) {
        var result = service.submit(request);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }

    @PostMapping("/transactions/batch")
    public ResponseEntity<?> submitBatch(@RequestBody List<TransactionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Submit at least one transaction.");
        }
        if (requests.size() > 500) {
            throw new IllegalArgumentException("A batch may contain at most 500 transactions.");
        }

        var result = service.submitBatch(requests);
        HttpStatus status = result.accepted() > 0 ? HttpStatus.ACCEPTED
                : result.duplicates() > 0 ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/transactions")
    public List<?> all() {
        return service.findAll();
    }

    @GetMapping("/transactions/{transactionId}")
    public Object one(@PathVariable String transactionId) {
        return service.findOne(transactionId);
    }

    @PostMapping("/processing/run")
    public Map<String, Object> runNow() {
        return service.runProcessingNow();
    }

    @GetMapping("/processing/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    @GetMapping("/accounts")
    public List<?> allAccounts() {
        return service.allAccountBalances();
    }

    @GetMapping("/accounts/{accountId}")
    public Object account(@PathVariable String accountId) {
        return service.accountBalance(accountId);
    }
}
