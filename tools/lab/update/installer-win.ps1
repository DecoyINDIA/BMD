# Exercise the real automatic update window and installer handoff with a separate Inno AppId.
param(
  [Parameter(Mandatory)][string]$OldExe,
  [Parameter(Mandatory)][string]$NewExe,
  [Parameter(Mandatory)][string]$Iscc,
  [int]$Port = 8296
)
$ErrorActionPreference = 'Stop'
$OldExe = (Resolve-Path -LiteralPath $OldExe).ProviderPath
$NewExe = (Resolve-Path -LiteralPath $NewExe).ProviderPath
$oldVersion = (Get-Item -LiteralPath $OldExe).VersionInfo.ProductVersion
$newVersion = (Get-Item -LiteralPath $NewExe).VersionInfo.ProductVersion
if ([version]$oldVersion -ge [version]$newVersion) { throw 'Expected two different app versions' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).ProviderPath
$runId = [guid]::NewGuid().ToString('N')
$work = Join-Path $root "target/update-installer-$runId"
$install = Join-Path $work 'installed'
$data = Join-Path $work 'data'
$feed = Join-Path $work 'feed'
New-Item -ItemType Directory -Path $data,$feed,$install | Out-Null
$program = Join-Path $install 'bmd.exe'
$uninstaller = Join-Path $install 'unins000.exe'

function Setup([string]$source, [string]$version, [string]$dest) {
  $name = "BMD-$version-setup"
  & $Iscc /Q "/O$dest" "/F$name" "/DAppVersion=$version" "/DTestAppId=BmdUpdaterTest-$runId" "/DTestInstallDir=$install" "/DTestExe=$source" (Join-Path $PSScriptRoot 'isolated-setup.iss') | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "Failed to compile $name" }
  Join-Path $dest "$name.exe"
}

function Wait-For([string]$label, [scriptblock]$check, [int]$seconds = 45) {
  $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
  do {
    $result = & $check
    if ($result) { return $result }
    Start-Sleep -Milliseconds 250
  } while ([DateTime]::UtcNow -lt $deadline)
  throw "Timed out waiting for $label ($work)"
}

Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
$automation = [System.Windows.Automation.AutomationElement]::RootElement
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class BmdUpdateKey {
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr window, uint msg, IntPtr key, IntPtr state);
}
'@
$server = $null
$origin = "http://127.0.0.1:$Port"
try {
  $startingSetup = Setup $OldExe $oldVersion $work
  $candidateSetup = Setup $NewExe $newVersion $feed
  $asset = Split-Path -Leaf $candidateSetup
  $sha = (Get-FileHash -LiteralPath $candidateSetup -Algorithm SHA256).Hash.ToLowerInvariant()
  Set-Content -LiteralPath (Join-Path $feed 'SHA256SUMS.txt') -Encoding ascii -Value "$sha  $asset"
  @{
    tag_name = "v$newVersion"; html_url = "$origin/release"; body = 'Isolated update test.'
    assets = @(
      @{ name = $asset; browser_download_url = "$origin/$asset" }
      @{ name = 'SHA256SUMS.txt'; browser_download_url = "$origin/SHA256SUMS.txt" }
    )
  } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $feed 'latest.json') -Encoding utf8NoBOM
  $seed = Start-Process -FilePath $startingSetup -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',"/DIR=`"$install`"" -WindowStyle Hidden -Wait -PassThru
  if ($seed.ExitCode -ne 0 -or !(Test-Path -LiteralPath $uninstaller)) { throw 'Initial test installation failed' }
  @{
    next_id = 1; items = @(); version = 2
    settings = @{ language = 'en'; language_asked = $true; helpers_asked = $true; extension_never_ask = $true
      update_check = $true; torrent_incoming = $false; torrent_upnp = $false; torrent_dht = $false; torrent_lsd = $false }
  } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $data 'downloads.json') -Encoding utf8NoBOM
  $server = Start-Process -FilePath python -ArgumentList '-m','http.server',"$Port",'--bind','127.0.0.1','--directory',"`"$feed`"" -WindowStyle Hidden -PassThru
  Wait-For 'the update feed' {
    try { (Invoke-RestMethod "$origin/latest.json" -TimeoutSec 2).tag_name -eq "v$newVersion" }
    catch { $false }
  } | Out-Null
  $env:BMD_OFFSCREEN = '1'
  $env:BMD_DATA_DIR = $data
  $env:BMD_UPDATE_FEED = "$origin/latest.json"
  $old = Start-Process -FilePath $program -PassThru
  $condition = [System.Windows.Automation.AndCondition]::new(
    [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::ProcessIdProperty, $old.Id),
    [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Check for updates')
  )
  $popup = Wait-For 'the automatic update pop-up' {
    $automation.FindFirst([System.Windows.Automation.TreeScope]::Children, $condition)
  }
  Write-Host "Automatic update pop-up found for $oldVersion -> $newVersion"
  $buttonName = [System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NameProperty, 'Update now')
  $button = $popup.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $buttonName)
  $invoke = $null
  if ($button -and $button.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$invoke)) {
    $invoke.Invoke()
  } else {
    $handle = [IntPtr]$popup.Current.NativeWindowHandle
    [BmdUpdateKey]::PostMessage($handle, 0x100, [IntPtr]13, [IntPtr]0x001C0001) | Out-Null
    [BmdUpdateKey]::PostMessage($handle, 0x101, [IntPtr]13, [IntPtr]0xC01C0001) | Out-Null
  }
  $new = Wait-For 'BMD installed and relaunched' {
    if (!$old.HasExited -or !(Test-Path -LiteralPath $program)) { return }
    Get-CimInstance Win32_Process -Filter "Name='bmd.exe'" |
      Where-Object { $_.ExecutablePath -eq $program -and $_.CommandLine -like '*/updated*' } |
      Select-Object -First 1
  } 90
  if ((Get-FileHash -LiteralPath $program).Hash -ne (Get-FileHash -LiteralPath $NewExe).Hash) {
    throw 'The relaunched executable does not match the update payload'
  }
  if (!(Test-Path -LiteralPath (Join-Path $data 'downloads.json'))) { throw 'The data folder was lost' }
  Write-Host "PASS: BMD downloaded $asset, replaced $oldVersion with $newVersion, and relaunched (PID $($new.ProcessId))."
} finally {
  Get-CimInstance Win32_Process -Filter "Name='bmd.exe'" | Where-Object ExecutablePath -EQ $program |
    ForEach-Object { Stop-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
  if ($server -and !$server.HasExited) { Stop-Process -Id $server.Id -ErrorAction SilentlyContinue }
  Remove-Item Env:BMD_OFFSCREEN,Env:BMD_DATA_DIR,Env:BMD_UPDATE_FEED -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $uninstaller) {
    Start-Process -FilePath $uninstaller -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART' -WindowStyle Hidden -Wait
  }
}
