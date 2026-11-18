param(
    [string]$BaseUrl = "http://localhost:8081"
)

$sampleFile = Join-Path $PSScriptRoot "sample-transactions.json"
if (-not (Test-Path -LiteralPath $sampleFile)) {
    throw "Sample file not found: $sampleFile"
}

. (Join-Path $PSScriptRoot "api-auth.ps1")
$authHeaders = Get-ApiAuthorizationHeaders
$batch = Get-Content -LiteralPath $sampleFile -Raw
Write-Host "Submitting sample batch to $BaseUrl"
$batchResult = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/transactions/batch" `
    -Headers $authHeaders -ContentType "application/json" -Body $batch
$batchResult | ConvertTo-Json -Depth 8

Write-Host "Running the processor twice; TXN-2005 remains pending because its account has no sequences 1-4"
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/processing/run" -Headers $authHeaders | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/processing/run" -Headers $authHeaders | ConvertTo-Json -Depth 8

Write-Host "Final processing summary"
Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/processing/summary" -Headers $authHeaders | ConvertTo-Json -Depth 8

Write-Host "Account balances"
Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/accounts" -Headers $authHeaders | ConvertTo-Json -Depth 8

Write-Host "Stored sample transactions"
foreach ($transactionId in @("TXN-2001", "TXN-2002", "TXN-2003", "TXN-2005")) {
    Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/transactions/$transactionId" -Headers $authHeaders |
        ConvertTo-Json -Depth 8
}

Write-Host "TXN-2004 is invalid; its FAILED attempt is included in the summary, not stored as a business transaction."
