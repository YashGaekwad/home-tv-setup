# Saves your MGVCL login on the TV, inside the Electricity app (encrypted with the TV's Android Keystore).
# Run it yourself; it asks for the username and the password (hidden) and sends them straight to the TV.
#
#   powershell -ExecutionPolicy Bypass -File tools\set-login.ps1 -TvHost <tv ip>
#   powershell -ExecutionPolicy Bypass -File tools\set-login.ps1 -TvHost <tv ip> -Clear   # forget it
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [switch]$Clear
)
$ErrorActionPreference = 'Stop'
$target = "$TvHost`:5555"
& adb connect $target | Out-Null
$receiver = 'com.yashg.electricity/.LoginReceiver'

if ($Clear) {
    & adb -s $target shell am broadcast -n $receiver --ez clear true
    return
}

$user = Read-Host 'MGVCL username'
$secure = Read-Host 'MGVCL password' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $pass = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $u64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($user.Trim()))
    $p64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pass))
    $out = & adb -s $target shell am broadcast -n $receiver --es u64 $u64 --es p64 $p64
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $pass = $null; $p64 = $null
}
if ($out -match 'data="saved"') {
    Write-Host 'Saved on the TV. Open Electricity on the TV (or press OK in it) to refresh.'
} else {
    Write-Host "The TV did not confirm: $out"
}
