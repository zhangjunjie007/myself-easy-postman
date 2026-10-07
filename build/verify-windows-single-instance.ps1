param(
    [Parameter(Mandatory = $true)]
    [string]$AppExe,

    [int]$StartupWaitSeconds = 8,

    [Alias('SecondaryExitTimeoutSeconds')]
    [int]$TakeoverTimeoutSeconds = 15
)

$ErrorActionPreference = 'Stop'
$resolvedAppExe = (Resolve-Path $AppExe).Path
$projectRoot = Split-Path -Parent $PSScriptRoot
$smokeRoot = Join-Path $projectRoot "target\single-instance-smoke-$([Guid]::NewGuid().ToString('N'))"
$dataRoot = Join-Path $smokeRoot 'data'
$logRoot = Join-Path $smokeRoot 'logs'
$normalizedDataRoot = $dataRoot.Replace('\', '/')
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$primaryProcess = $null
$secondaryProcess = $null
$recoveredProcess = $null

function Start-EasyPostmanProcess {
    param([string]$Name)

    $stdoutPath = Join-Path $logRoot "$Name-stdout.log"
    $stderrPath = Join-Path $logRoot "$Name-stderr.log"
    return Start-Process `
        -FilePath $resolvedAppExe `
        -PassThru `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath
}

<# Checks that the GUI remains alive and exposes a main window after the startup wait. #>
function Assert-ProcessStillRunning {
    param(
        [System.Diagnostics.Process]$Process,
        [string]$Description
    )

    $Process.Refresh()
    if ($Process.HasExited) {
        throw "$Description exited unexpectedly with code $($Process.ExitCode)"
    }
    # jpackage's outer launcher has no window; metadata identifies its actual child JVM.
    $metadataPath = Join-Path $dataRoot '.runtime\gui-instance.properties'
    $pidLine = Get-Content -LiteralPath $metadataPath | Where-Object { $_ -match '^pid=\d+$' } | Select-Object -First 1
    if (-not $pidLine) {
        throw "$Description did not publish its GUI process PID"
    }
    $guiPid = [int]($pidLine.Substring(4))
    $guiProcess = Get-Process -Id $guiPid
    $guiDetails = Get-CimInstance Win32_Process -Filter "ProcessId = $guiPid"
    if ($guiPid -ne $Process.Id -and $guiDetails.ParentProcessId -ne $Process.Id) {
        throw "$Description did not become the data-directory GUI owner"
    }
    if ($guiProcess.MainWindowHandle -eq [IntPtr]::Zero) {
        throw "$Description is running without a visible main window"
    }
}

<# Stops only this test launcher's matching child JVM, then waits for redirected files to close. #>
function Stop-ProcessIfRunning {
    param([AllowNull()][System.Diagnostics.Process]$Process)

    if ($null -eq $Process) {
        return
    }
    $Process.Refresh()
    if (-not $Process.HasExited) {
        $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $($Process.Id)"
        foreach ($child in $children) {
            if ([StringComparer]::OrdinalIgnoreCase.Equals($child.ExecutablePath, $resolvedAppExe)) {
                Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue
                $childProcess = Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
                if ($childProcess) {
                    $childProcess.WaitForExit(5000) | Out-Null
                }
            }
        }
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
    $Process.WaitForExit()
    $Process.Dispose()
}

function Write-SmokeLogs {
    if (-not (Test-Path $logRoot)) {
        return
    }
    Get-ChildItem $logRoot -File | Sort-Object Name | ForEach-Object {
        Write-Host "===== $($_.Name) ====="
        Get-Content $_.FullName -ErrorAction SilentlyContinue
    }
}

try {
    New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
    $env:JAVA_TOOL_OPTIONS = "-Djava.awt.headless=false -DeasyPostman.data.dir=`"$normalizedDataRoot`""

    Write-Host "Starting primary EasyPostman instance..."
    $primaryProcess = Start-EasyPostmanProcess -Name 'primary'
    Start-Sleep -Seconds $StartupWaitSeconds
    Assert-ProcessStillRunning -Process $primaryProcess -Description 'Primary instance'

    Write-Host "Starting secondary EasyPostman instance..."
    $secondaryProcess = Start-EasyPostmanProcess -Name 'secondary'
    if (-not $primaryProcess.WaitForExit($TakeoverTimeoutSeconds * 1000)) {
        throw 'Previous instance did not exit after the new instance took over'
    }
    Start-Sleep -Seconds $StartupWaitSeconds
    Assert-ProcessStillRunning -Process $secondaryProcess -Description 'New instance after takeover'
    Write-Host "Single-instance takeover passed: the previous instance exited and the new instance stayed alive."

    Write-Host "Force-terminating the new instance to verify crash recovery..."
    Stop-ProcessIfRunning -Process $secondaryProcess
    $secondaryProcess = $null

    $recoveredProcess = Start-EasyPostmanProcess -Name 'recovered'
    Start-Sleep -Seconds $StartupWaitSeconds
    Assert-ProcessStillRunning -Process $recoveredProcess -Description 'Recovered instance'
    Write-Host "Crash recovery passed: a new instance started after forced termination."
} catch {
    Write-Host "Windows single-instance smoke test failed: $($_.Exception.Message)"
    Write-SmokeLogs
    throw
} finally {
    Stop-ProcessIfRunning -Process $secondaryProcess
    Stop-ProcessIfRunning -Process $primaryProcess
    Stop-ProcessIfRunning -Process $recoveredProcess
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    if (Test-Path $smokeRoot) {
        $resolvedSmokeRoot = (Resolve-Path -LiteralPath $smokeRoot).Path
        $expectedSmokeRoot = [System.IO.Path]::GetFullPath($smokeRoot)
        if ($resolvedSmokeRoot -ne $expectedSmokeRoot -or
                -not $resolvedSmokeRoot.StartsWith([System.IO.Path]::GetFullPath($projectRoot) + '\target\')) {
            throw "Unexpected smoke-test cleanup path: $resolvedSmokeRoot"
        }
        # Redirected log handles can close shortly after the native launcher exits on Windows.
        for ($cleanupAttempt = 0; ; $cleanupAttempt++) {
            try {
                Remove-Item -LiteralPath $resolvedSmokeRoot -Recurse -Force
                break
            } catch {
                if ($cleanupAttempt -ge 14) {
                    throw
                }
                Start-Sleep -Milliseconds 200
            }
        }
    }
}
