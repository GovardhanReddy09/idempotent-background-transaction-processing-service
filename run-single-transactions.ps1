param(
    [string]$BaseUrl = "http://localhost:8081"
)

$suffix = Get-Date -Format "yyyyMMddHHmmssfff"
$accountOne = "ACC-SINGLE-$suffix"
$accountThree = "ACC-RETRY-$suffix"
$transactionOne = "TXN-SINGLE-1-$suffix"
$transactionTwo = "TXN-SINGLE-2-$suffix"
$transactionThree = "TXN-SINGLE-3-$suffix"
$transactionFive = "TXN-SINGLE-5-$suffix"

function Submit-Transaction {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Transaction
    )

    $body = $Transaction | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/transactions" `
        -ContentType "application/json" -Body $body | ConvertTo-Json -Depth 8
}

Write-Host "Sending sequence 2 first; it will wait until sequence 1 arrives."
Submit-Transaction @{
    transactionId = $transactionTwo
    requestId = "REQ-2-$suffix"
    accountId = $accountOne
    transactionType = "DEBIT"
    amount = 40.00
    currency = "INR"
    sequenceNumber = 2
}

Write-Host "Sending sequence 1."
Submit-Transaction @{
    transactionId = $transactionOne
    requestId = "REQ-1-$suffix"
    accountId = $accountOne
    transactionType = "CREDIT"
    amount = 100.00
    currency = "INR"
    sequenceNumber = 1
}

Write-Host "Submitting sequence 2 again; this attempt should be DUPLICATE."
Submit-Transaction @{
    transactionId = $transactionTwo
    requestId = "REQ-2-DUP-$suffix"
    accountId = $accountOne
    transactionType = "DEBIT"
    amount = 40.00
    currency = "INR"
    sequenceNumber = 2
}

Write-Host "Sending sequence 3."
Submit-Transaction @{
    transactionId = $transactionThree
    requestId = "REQ-3-$suffix"
    accountId = $accountOne
    transactionType = "CREDIT"
    amount = 25.00
    currency = "INR"
    sequenceNumber = 3
}

Write-Host "Sending an invalid negative-amount transaction; HTTP 400 is expected."
$invalidBody = @{
    transactionId = "TXN-SINGLE-4-$suffix"
    requestId = "REQ-4-$suffix"
    accountId = "ACC-INVALID-$suffix"
    transactionType = "DEBIT"
    amount = -10.00
    currency = "INR"
    sequenceNumber = 1
} | ConvertTo-Json -Compress
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/transactions" `
        -ContentType "application/json" -Body $invalidBody
    throw "The invalid transaction was unexpectedly accepted."
} catch {
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 400) {
        Write-Host "Expected validation rejection: $($_.Exception.Message)"
    } else {
        throw
    }
}

Write-Host "Sending a transaction configured to fail once, then retry."
Submit-Transaction @{
    transactionId = $transactionFive
    requestId = "REQ-5-$suffix"
    accountId = $accountThree
    transactionType = "CREDIT"
    amount = 500.00
    currency = "INR"
    sequenceNumber = 1
    simulateFailureOnce = $true
}

Write-Host "Running the processor twice."
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/processing/run" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/processing/run" | ConvertTo-Json -Depth 8

Write-Host "Checking each accepted transaction."
foreach ($transactionId in @($transactionOne, $transactionTwo, $transactionThree, $transactionFive)) {
    Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/transactions/$transactionId" |
        ConvertTo-Json -Depth 8
}

Write-Host "Checking account balances and processing summary."
Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/accounts" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/processing/summary" | ConvertTo-Json -Depth 8
