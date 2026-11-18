function Get-ApiAuthorizationHeaders {
    $username = $env:APP_API_USERNAME
    $password = $env:APP_API_PASSWORD
    if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
        throw "Set APP_API_USERNAME and APP_API_PASSWORD in this PowerShell window first."
    }

    $pair = '{0}:{1}' -f $username, $password
    $encoded = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    return @{ Authorization = "Basic $encoded" }
}
