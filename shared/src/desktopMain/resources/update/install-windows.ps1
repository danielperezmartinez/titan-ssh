# titan-ssh updater helper (ADR-0025). Started by the app right before it
# exits: waits for it to end, checks the MSI against the digest of the signed
# manifest once more, installs it per user, writes the msiexec exit code to
# $Result (3 if the file no longer matches) and, if asked, opens the new version.
param(
    [Parameter(Mandatory = $true)][int]$AppPid,
    [Parameter(Mandatory = $true)][string]$Msi,
    [Parameter(Mandatory = $true)][string]$Sha256,
    [Parameter(Mandatory = $true)][string]$Log,
    [Parameter(Mandatory = $true)][string]$Result,
    [string]$Relaunch = ''
)

$deadline = (Get-Date).AddMinutes(2)
while ((Get-Process -Id $AppPid -ErrorAction SilentlyContinue) -and ((Get-Date) -lt $deadline)) {
    Start-Sleep -Milliseconds 500
}

$code = 1
try {
    if ((Get-FileHash -LiteralPath $Msi -Algorithm SHA256).Hash -ne $Sha256.ToUpperInvariant()) {
        $code = 3
    } else {
        $arguments = @('/i', ('"{0}"' -f $Msi), '/qn', '/norestart', '/l*v', ('"{0}"' -f $Log))
        $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
        $code = $process.ExitCode
    }
} catch {
    $code = 1
}
Set-Content -LiteralPath $Result -Value $code -Encoding ascii

# 3010: installed, a reboot would finish it; the app itself is already replaced.
if ($Relaunch -and ($code -eq 0 -or $code -eq 3010)) {
    Start-Process -FilePath $Relaunch
}
