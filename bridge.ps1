param(
  [int]$Port = 18765,
  [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

$adbPath = if ($env:ADB_PATH -and (Test-Path -LiteralPath $env:ADB_PATH)) {
  $env:ADB_PATH
} else {
  (Get-Command adb -ErrorAction Stop).Source
}

if (-not $Serial) {
  $lines = & $adbPath devices -l
  if ($LASTEXITCODE -ne 0) { throw "Не удалось получить список ADB-устройств" }
  $found = @($lines | Where-Object { $_ -match '^\S+\s+device(?:\s|$)' } |
    ForEach-Object { ($_ -split '\s+')[0] })
  if ($found.Count -ne 1) {
    throw "Нужен один подключённый Quest; найдено $($found.Count). Укажите -Serial."
  }
  $Serial = $found[0]
}

$deviceArgs = @('-s', $Serial)
$logDir = Join-Path $PSScriptRoot '.local'
[System.IO.Directory]::CreateDirectory($logDir) | Out-Null
$logPath = Join-Path $logDir ("diagnostics-{0}.jsonl" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
$utf8 = [System.Text.UTF8Encoding]::new($false)

function Write-Diagnostic([hashtable]$Record) {
  $Record.timestamp = (Get-Date).ToString('o')
  $line = $Record | ConvertTo-Json -Depth 8 -Compress
  [System.IO.File]::AppendAllText($logPath, $line + [Environment]::NewLine, $utf8)
}

function Invoke-Adb([string[]]$AdbArgs, [bool]$LogCommand = $true, [bool]$AllowFailure = $false) {
  $output = & $adbPath @deviceArgs @AdbArgs 2>&1
  $code = $LASTEXITCODE
  $text = ($output | Out-String).Trim()
  if ($LogCommand) {
    Write-Diagnostic @{ type = 'adb'; args = $AdbArgs; exitCode = $code; output = $text }
  }
  if ($code -ne 0 -and -not $AllowFailure) { throw "ADB $($AdbArgs -join ' '): $text" }
  return $text
}

function Parse-Kilobytes([string]$Value) {
  return [long]($Value -replace ',', '')
}

function Assert-PackageName([string]$Package) {
  if ($Package -notmatch '^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$') {
    throw 'Некорректное имя пакета'
  }
}

function Assert-ManageablePackage([string]$Package) {
  Assert-PackageName $Package
  if ($Package -eq 'com.adaptivecodex.quest.manager' -or
      $Package -match '^(com\.oculus\.|com\.meta\.|com\.facebook\.|com\.android\.|com\.google\.)') {
    throw 'Управление этим пакетом недоступно'
  }
  $thirdParty = Invoke-Adb -AdbArgs @('shell', 'pm', 'list', 'packages', '-3') -LogCommand $false
  if (-not (($thirdParty -split "`r?`n") -ccontains "package:$Package")) {
    throw 'Управление доступно только для сторонних приложений'
  }
}

function Get-PackageStatus([string]$Package) {
  Assert-PackageName $Package
  $path = Invoke-Adb -AdbArgs @('shell', 'pm', 'path', $Package) -LogCommand $false -AllowFailure $true
  if ($path -notmatch '(?m)^package:') { throw 'Пакет не установлен' }
  $details = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'package', $Package) -LogCommand $false
  $userLine = ($details -split "`r?`n" | Where-Object { $_ -match '^\s*User 0:' } | Select-Object -First 1)
  if (-not $userLine) { throw 'Состояние пакета для пользователя 0 недоступно' }
  $stoppedMatch = [regex]::Match($userLine, '\bstopped=(true|false)')
  $suspendedMatch = [regex]::Match($userLine, '\bsuspended=(true|false)')
  if (-not $stoppedMatch.Success -or -not $suspendedMatch.Success) {
    throw 'Не удалось прочитать stopped/suspended state'
  }
  $processText = Invoke-Adb -AdbArgs @('shell', 'ps', '-A', '-o', 'PID,NAME') -LogCommand $false
  $pids = @()
  foreach ($line in ($processText -split "`r?`n")) {
    if ($line -match '^\s*(\d+)\s+(.+)$') {
      $name = $Matches[2].Trim()
      if ($name -ceq $Package -or $name.StartsWith("${Package}:")) { $pids += [int]$Matches[1] }
    }
  }
  $resolve = Invoke-Adb -AdbArgs @('shell', 'cmd', 'package', 'resolve-activity',
    '--brief', '-a', 'android.intent.action.MAIN', '-c',
    'android.intent.category.LAUNCHER', '-p', $Package) -LogCommand $false -AllowFailure $true
  $component = ($resolve -split "`r?`n" | Where-Object {
    $_ -match ('^' + [regex]::Escape($Package) + '/[A-Za-z0-9_.$]+$')
  } | Select-Object -Last 1)
  return @{
    package = $Package; running = ($pids.Count -gt 0); pids = $pids
    stopped = ($stoppedMatch.Groups[1].Value -eq 'true')
    suspended = ($suspendedMatch.Groups[1].Value -eq 'true')
    launchComponent = if ($component) { $component } else { $null }
  }
}

function Invoke-PackageAction([string]$Package, [string]$Action) {
  Assert-ManageablePackage $Package
  if ($Action -cnotin @('stop', 'restart', 'suspend', 'unsuspend')) {
    throw 'Неизвестное действие над пакетом'
  }
  $before = Get-PackageStatus $Package
  switch ($Action) {
    'stop' {
      Invoke-Adb -AdbArgs @('shell', 'am', 'force-stop', $Package) | Out-Null
    }
    'restart' {
      if ($before.suspended) { throw 'Сначала разрешите запуск пакета' }
      if (-not $before.launchComponent) { throw 'У пакета нет запускаемой Activity' }
      Invoke-Adb -AdbArgs @('shell', 'am', 'force-stop', $Package) | Out-Null
      $launch = Invoke-Adb -AdbArgs @('shell', 'am', 'start', '-n', $before.launchComponent)
      if ($launch -match '(?m)^Error:') { throw "Запуск не выполнен: $launch" }
      Start-Sleep -Milliseconds 500
    }
    'suspend' {
      Invoke-Adb -AdbArgs @('shell', 'pm', 'suspend', $Package) | Out-Null
    }
    'unsuspend' {
      Invoke-Adb -AdbArgs @('shell', 'pm', 'unsuspend', $Package) | Out-Null
    }
  }
  $after = Get-PackageStatus $Package
  if ($Action -eq 'restart') {
    for ($attempt = 0; $attempt -lt 4 -and -not $after.running; $attempt++) {
      Start-Sleep -Milliseconds 500
      $after = Get-PackageStatus $Package
    }
  }
  $verified = switch ($Action) {
    'stop' { $after.stopped -and -not $after.running }
    'restart' { $after.running -and -not $after.stopped }
    'suspend' { $after.suspended }
    'unsuspend' { -not $after.suspended }
  }
  Write-Diagnostic @{ type = 'packageAction'; package = $Package; action = $Action
    before = $before; after = $after; verified = [bool]$verified }
  return @{ ok = $true; verified = [bool]$verified; status = $after }
}

function Get-Snapshot {
  $packagesText = Invoke-Adb -AdbArgs @('shell', 'pm', 'list', 'packages')
  $systemPackagesText = Invoke-Adb -AdbArgs @('shell', 'pm', 'list', 'packages', '-s')
  $systemPackages = @{}
  foreach ($line in ($systemPackagesText -split "`r?`n")) {
    if ($line -match '^package:([A-Za-z0-9_.]+)$') { $systemPackages[$Matches[1]] = $true }
  }
  $processesText = Invoke-Adb -AdbArgs @('shell', 'ps', '-A', '-o', 'PID,RSS,NAME')
  $processes = @()
  foreach ($line in ($processesText -split "`r?`n")) {
    $parts = $line.Trim() -split '\s+', 3
    if ($parts.Count -ne 3) { continue }
    $pidValue = 0
    $rssValue = [long]0
    if (-not [int]::TryParse($parts[0], [ref]$pidValue)) { continue }
    if (-not [long]::TryParse($parts[1], [ref]$rssValue)) { continue }
    $processes += @{ pid = $pidValue; rssKb = $rssValue; name = $parts[2] }
  }

  $memoryReport = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'meminfo') -LogCommand $false
  $pssByPid = @{}
  $inPssSection = $false
  foreach ($line in ($memoryReport -split "`r?`n")) {
    if ($line -match '^Total PSS by process:') { $inPssSection = $true; continue }
    if ($inPssSection -and $line -match '^Total PSS by ') { break }
    if ($inPssSection -and $line -match '^\s*([\d,]+)K:.*\(pid\s+(\d+)') {
      $pssByPid[[int]$Matches[2]] = Parse-Kilobytes $Matches[1]
    }
  }
  $usedMatch = [regex]::Match($memoryReport,
    '(?m)^\s*Used RAM:\s*([\d,]+)K\s*\(\s*([\d,]+)K used pss \+\s*([\d,]+)K ZRAM \+\s*([\d,]+)K kernel\)')
  $memoryBreakdown = [ordered]@{
    usedPssKb = if ($usedMatch.Success) { Parse-Kilobytes $usedMatch.Groups[2].Value } else { [long]0 }
    kernelKb = if ($usedMatch.Success) { Parse-Kilobytes $usedMatch.Groups[4].Value } else { [long]0 }
    zramKb = if ($usedMatch.Success) { Parse-Kilobytes $usedMatch.Groups[3].Value } else { [long]0 }
  }

  $apps = @()
  $matchedPids = @{}
  foreach ($line in ($packagesText -split "`r?`n")) {
    if ($line -notmatch '^package:([A-Za-z0-9_.]+)$') { continue }
    $name = $Matches[1]
    $matched = @($processes | Where-Object {
      $_.name -eq $name -or $_.name.StartsWith("${name}:")
    })
    $rss = [long]0
    $pss = [long]0
    foreach ($entry in $matched) {
      $rss += [long]$entry.rssKb
      if ($pssByPid.ContainsKey($entry.pid)) { $pss += [long]$pssByPid[$entry.pid] }
      $matchedPids[$entry.pid] = $true
    }
    $pids = @($matched | ForEach-Object { [int]$_.pid })
    $kind = if ($systemPackages.ContainsKey($name)) { 'system' } else { 'user' }
    $apps += [ordered]@{
      package = $name; kind = $kind; processes = $matched.Count
      rssKb = $rss; pssKb = $pss; pids = $pids
    }
  }
  foreach ($entry in $processes) {
    if ($matchedPids.ContainsKey($entry.pid) -or $entry.rssKb -le 0) { continue }
    $pss = if ($pssByPid.ContainsKey($entry.pid)) { [long]$pssByPid[$entry.pid] } else { [long]0 }
    $apps += [ordered]@{
      package = $entry.name; kind = 'system_process'; processes = 1
      rssKb = [long]$entry.rssKb; pssKb = $pss; pids = @([int]$entry.pid)
    }
  }
  $apps = @($apps | Sort-Object -Property @(
    @{ Expression = { if ($_.processes -gt 0) { 0 } else { 1 } } },
    @{ Expression = { if ($_.processes -gt 0) { -[long]$_.pssKb } else { [long]0 } } },
    @{ Expression = { $_.package } }
  ))

  $memoryText = Invoke-Adb -AdbArgs @('shell', 'cat', '/proc/meminfo')
  $totalMatch = [regex]::Match($memoryText, '(?m)^MemTotal:\s+(\d+)\s+kB')
  $availableMatch = [regex]::Match($memoryText, '(?m)^MemAvailable:\s+(\d+)\s+kB')
  $device = [ordered]@{
    model = Invoke-Adb -AdbArgs @('shell', 'getprop', 'ro.product.model')
    android = Invoke-Adb -AdbArgs @('shell', 'getprop', 'ro.build.version.release')
    display = Invoke-Adb -AdbArgs @('shell', 'wm', 'size')
    density = Invoke-Adb -AdbArgs @('shell', 'wm', 'density')
    brightness = Invoke-Adb -AdbArgs @('shell', 'settings', 'get', 'system', 'screen_brightness')
    memoryTotalKb = if ($totalMatch.Success) { [long]$totalMatch.Groups[1].Value } else { [long]0 }
    memoryAvailableKb = if ($availableMatch.Success) { [long]$availableMatch.Groups[1].Value } else { [long]0 }
  }
  Write-Diagnostic @{
    type = 'snapshot'; appCount = $apps.Count
    systemCount = @($apps | Where-Object { $_.kind -eq 'system' }).Count
    processCount = @($apps | Where-Object { $_.kind -eq 'system_process' }).Count
    runningCount = @($apps | Where-Object { $_.processes -gt 0 }).Count
    pssProcessCount = $pssByPid.Count
  }
  return @{ ok = $true; device = $device; apps = $apps; memoryBreakdown = $memoryBreakdown }
}

$script:lastCpuTicks = $null

function Get-CpuTicks {
  $statText = Invoke-Adb -AdbArgs @('shell', 'cat', '/proc/stat') -LogCommand $false
  $ticks = @{}
  foreach ($line in ($statText -split "`r?`n")) {
    if ($line -notmatch '^(cpu\d*)\s+(.+)$') { continue }
    $name = $Matches[1]
    $values = @($Matches[2].Trim() -split '\s+' | Select-Object -First 8)
    if ($values.Count -lt 5) { continue }
    $total = [long]0
    foreach ($value in $values) { $total += [long]$value }
    $ticks[$name] = @{ total = $total; idle = ([long]$values[3] + [long]$values[4]) }
  }
  return $ticks
}

function Get-CpuPercent([hashtable]$Previous, [hashtable]$Current, [string]$Name) {
  if (-not $Previous.ContainsKey($Name) -or -not $Current.ContainsKey($Name)) { return $null }
  $total = $Current[$Name].total - $Previous[$Name].total
  $idle = $Current[$Name].idle - $Previous[$Name].idle
  if ($total -le 0) { return $null }
  return [math]::Round((100.0 * ($total - $idle) / $total), 1)
}

function Get-Performance {
  if ($null -eq $script:lastCpuTicks) {
    $script:lastCpuTicks = Get-CpuTicks
    Start-Sleep -Milliseconds 300
  }
  $currentTicks = Get-CpuTicks
  $cpuPercent = Get-CpuPercent $script:lastCpuTicks $currentTicks 'cpu'
  $cores = @($currentTicks.Keys | Where-Object { $_ -match '^cpu\d+$' } |
    Sort-Object { [int]($_ -replace '^cpu', '') } | ForEach-Object {
      @{ core = $_; percent = Get-CpuPercent $script:lastCpuTicks $currentTicks $_ }
    })
  $script:lastCpuTicks = $currentTicks

  $gpuText = Invoke-Adb -AdbArgs @('shell', 'cat', '/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage') -LogCommand $false -AllowFailure $true
  $gpuMatch = [regex]::Match($gpuText, '^\s*(\d+(?:\.\d+)?)\s*%?')
  $gpuPercent = if ($gpuMatch.Success) { [double]$gpuMatch.Groups[1].Value } else { $null }

  $memoryText = Invoke-Adb -AdbArgs @('shell', 'cat', '/proc/meminfo') -LogCommand $false
  $totalMatch = [regex]::Match($memoryText, '(?m)^MemTotal:\s+(\d+)\s+kB')
  $availableMatch = [regex]::Match($memoryText, '(?m)^MemAvailable:\s+(\d+)\s+kB')
  $memoryTotalKb = if ($totalMatch.Success) { [long]$totalMatch.Groups[1].Value } else { [long]0 }
  $memoryAvailableKb = if ($availableMatch.Success) { [long]$availableMatch.Groups[1].Value } else { [long]0 }

  $gpuMemoryText = Invoke-Adb -AdbArgs @('shell', 'gpumeminfo', '-m', '-o', '-l') -LogCommand $false -AllowFailure $true
  $processNames = @{}
  $processText = Invoke-Adb -AdbArgs @('shell', 'ps', '-A', '-o', 'PID,NAME') -LogCommand $false
  foreach ($line in ($processText -split "`r?`n")) {
    if ($line -match '^\s*(\d+)\s+(\S+)') { $processNames[[int]$Matches[1]] = $Matches[2] }
  }
  $gpuProcesses = @()
  $currentPid = 0
  foreach ($line in ($gpuMemoryText -split "`r?`n")) {
    if ($line -match '^\d+\.\s+PID:\s+(\d+)') {
      $currentPid = [int]$Matches[1]
    } elseif ($currentPid -gt 0 -and $line -match '^total,(\d+)') {
      $name = if ($processNames.ContainsKey($currentPid)) { $processNames[$currentPid] } else { "PID $currentPid" }
      $gpuProcesses += @{ pid = $currentPid; name = $name; bytes = [long]$Matches[1] }
      $currentPid = 0
    }
  }
  $gpuAllocatedBytes = [long]0
  foreach ($process in $gpuProcesses) { $gpuAllocatedBytes += [long]$process.bytes }
  Write-Diagnostic @{
    type = 'performance'; cpuPercent = $cpuPercent; gpuPercent = $gpuPercent
    memoryTotalKb = $memoryTotalKb; memoryAvailableKb = $memoryAvailableKb
    gpuAllocatedBytes = $gpuAllocatedBytes; gpuProcessCount = $gpuProcesses.Count
  }
  return @{
    ok = $true
    performance = @{
      cpuPercent = $cpuPercent; cpuCores = $cores; gpuPercent = $gpuPercent
      memoryTotalKb = $memoryTotalKb; memoryAvailableKb = $memoryAvailableKb
      gpuAllocatedBytes = $gpuAllocatedBytes; gpuProcesses = $gpuProcesses
    }
  }
}

$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 16
$random.GetBytes($bytes)
$random.Dispose()
$token = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()

try {
  Invoke-Adb -AdbArgs @('shell', 'pm', 'path', 'com.adaptivecodex.quest.manager') | Out-Null
  Invoke-Adb -AdbArgs @('reverse', "tcp:$Port", "tcp:$Port") | Out-Null
  Invoke-Adb -AdbArgs @(
    'shell', 'am', 'broadcast',
    '-n', 'com.adaptivecodex.quest.manager/.BridgeConfigReceiver',
    '-a', 'com.adaptivecodex.quest.manager.CONFIGURE_BRIDGE',
    '--es', 'token', $token
  ) -LogCommand $false | Out-Null
  Invoke-Adb -AdbArgs @('shell', 'am', 'start', '-n', 'com.adaptivecodex.quest.manager/.ManagerActivity') | Out-Null

  Write-Host "Quest Manager: $Serial, порт $Port"
  Write-Host "Диагностика: $logPath"
  Write-Host "Оставьте это окно открытым; Ctrl+C завершит мост."

  while ($true) {
    $client = $listener.AcceptTcpClient()
    try {
      $stream = $client.GetStream()
      $stream.ReadTimeout = 10000
      $stream.WriteTimeout = 10000
      $reader = [System.IO.StreamReader]::new($stream, $utf8)
      $writer = [System.IO.StreamWriter]::new($stream, $utf8)
      $writer.AutoFlush = $true
      $response = $null
      try {
        $line = $reader.ReadLine()
        if (-not $line -or $line.Length -gt 4096) { throw 'Некорректный запрос' }
        $request = $line | ConvertFrom-Json
        if ($request.token -cne $token) { throw 'Нет доступа к мосту' }
        switch ($request.op) {
          'snapshot' {
            $response = Get-Snapshot
          }
          'performance' {
            $response = Get-Performance
          }
          'packageStatus' {
            $response = @{ ok = $true; status = (Get-PackageStatus ([string]$request.package)) }
          }
          'packageAction' {
            $response = Invoke-PackageAction ([string]$request.package) ([string]$request.action)
          }
          'forceStop' {
            if ($request.package -cne 'com.adaptivecodex.quest') {
              throw 'Этот пакет не включён в тестовый allowlist'
            }
            Invoke-Adb -AdbArgs @('shell', 'am', 'force-stop', 'com.adaptivecodex.quest') | Out-Null
            $pidText = Invoke-Adb -AdbArgs @('shell', 'pidof', 'com.adaptivecodex.quest') -AllowFailure $true
            $stopped = -not $pidText
            Write-Diagnostic @{ type = 'forceStop'; package = 'com.adaptivecodex.quest'; stopped = $stopped }
            $response = @{ ok = $true; stopped = $stopped }
          }
          default { throw 'Неизвестная операция' }
        }
      } catch {
        $response = @{ ok = $false; error = $_.Exception.Message }
        Write-Diagnostic @{ type = 'error'; message = $_.Exception.Message }
      }
      $writer.WriteLine(($response | ConvertTo-Json -Depth 8 -Compress))
      $writer.Dispose()
      $reader.Dispose()
    } catch {
      Write-Diagnostic @{ type = 'connectionError'; message = $_.Exception.Message }
    } finally {
      $client.Dispose()
    }
  }
} finally {
  $listener.Stop()
  try { Invoke-Adb -AdbArgs @('reverse', '--remove', "tcp:$Port") -LogCommand $false | Out-Null } catch {}
}
