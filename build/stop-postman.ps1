param(
    [switch]$ListOnly,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Test-PostmanProcess {
    <#
    .SYNOPSIS
    Identifies desktop Postman/EasyPostman or Java running the EasyPostman JAR/main class.
    .PARAMETER Process
    Win32_Process snapshot; Java without a readable command line is never matched by name alone.
    .OUTPUTS
    Boolean. Other Java services, browsers and unrelated updater processes are excluded.
    #>
    param($Process)

    if ($Process.Name -match '^(?:easy-?postman|postman(?:[ -]?agent)?)\.exe$') {
        return $true
    }
    if ($Process.Name -notmatch '^javaw?\.exe$' -or -not $Process.CommandLine) {
        return $false
    }
    # Keep quoted arguments together; a classpath/property mentioning Postman is not an app launch.
    $arguments = @([regex]::Matches($Process.CommandLine, '(?:[^\s"]|"[^"\r\n]*")+') | ForEach-Object { $_.Value.Trim('"') })
    for ($index = 1; $index -lt $arguments.Count; $index++) {
        $argument = $arguments[$index]
        if ($argument -eq '-jar') {
            return $index + 1 -lt $arguments.Count -and
                $arguments[$index + 1] -match '(?:^|[\\/])easy-?postman[^\\/]*\.jar$'
        }
        if ($argument -in @('-cp', '-classpath', '--class-path', '-p', '--module-path', '--upgrade-module-path',
                '--source', '--add-modules', '--limit-modules', '--add-reads', '--add-exports', '--add-opens',
                '--patch-module', '--enable-native-access')) {
            $index++
        } elseif (-not $argument.StartsWith('-')) {
            return $argument -ceq 'com.laker.postman.App'
        }
    }
    return $false
}

$snapshot = @(Get-CimInstance -ClassName Win32_Process)
$targets = @{}
foreach ($process in $snapshot) {
    if ($process.ProcessId -ne $PID -and (Test-PostmanProcess $process)) {
        $targets[[int]$process.ProcessId] = $process
    }
}

# Include children from this snapshot, but reject a reused parent PID whose child predates it.
do {
    $added = $false
    foreach ($process in $snapshot) {
        $parent = $targets[[int]$process.ParentProcessId]
        if ($process.ProcessId -ne $PID -and -not $targets.ContainsKey([int]$process.ProcessId) -and
                $parent -and $parent.CreationDate -and $process.CreationDate -and
                $process.CreationDate -ge $parent.CreationDate) {
            $targets[[int]$process.ProcessId] = $process
            $added = $true
        }
    }
} while ($added)

if ($targets.Count -eq 0) {
    Write-Host 'No Postman or EasyPostman processes found.'
    exit 0
}

# Newest first: children normally start after their parent, so stop helpers before the app.
$orderedTargets = @($targets.Values | Sort-Object CreationDate -Descending)
$orderedTargets | Select-Object ProcessId, Name, CreationDate | Format-Table -AutoSize | Out-Host
if ($ListOnly) { exit 0 }

Write-Warning 'All listed Postman/EasyPostman instances will be forcibly stopped. Unsaved changes may be lost.'
if (-not $Force -and (Read-Host 'Type YES to stop these processes') -cne 'YES') {
    Write-Host 'Cancelled. No processes were stopped.'
    exit 0
}

$failed = 0
foreach ($target in $orderedTargets) {
    $live = $null
    try {
        # Keep an open process handle and verify its creation time so PID reuse cannot kill another app.
        $live = Get-Process -Id $target.ProcessId -ErrorAction SilentlyContinue
        if (-not $live) { continue }
        $null = $live.Handle
        if (-not $target.CreationDate -or
                [Math]::Abs(($live.StartTime.ToUniversalTime() - $target.CreationDate.ToUniversalTime()).TotalMilliseconds) -gt 1) {
            Write-Warning "Skipped PID $($target.ProcessId): process identity changed or cannot be verified."
            $failed++
            continue
        }
        Stop-Process -InputObject $live -Force -ErrorAction Stop
        Write-Host "Stopped $($target.Name) (PID $($target.ProcessId))."
    } catch {
        $failed++
        Write-Warning "Could not stop PID $($target.ProcessId): $($_.Exception.Message). Try Run as administrator."
    } finally {
        if ($live) { $live.Dispose() }
    }
}

if ($failed -gt 0) { exit 1 }
Write-Host 'Finished. You can start EasyPostman again.'
exit 0
