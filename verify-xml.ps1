$f1 = 'E:\v2borad_tv_vpn\app\src\main\res\values\strings.xml'
$f2 = 'E:\v2borad_tv_vpn\app\src\main\AndroidManifest.xml'

$b1 = [System.IO.File]::ReadAllBytes($f1)
$bad1 = $b1 | Where-Object { $_ -lt 9 -and $_ -ne 0 }
if ($bad1) { Write-Host "STRINGS_BAD" } else { Write-Host "STRINGS_CLEAN" }

$b2 = [System.IO.File]::ReadAllBytes($f2)
$bad2 = $b2 | Where-Object { $_ -lt 9 -and $_ -ne 0 }
if ($bad2) { Write-Host "MANIFEST_BAD" } else { Write-Host "MANIFEST_CLEAN" }