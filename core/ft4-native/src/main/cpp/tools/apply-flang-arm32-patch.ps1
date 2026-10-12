param(
    [Parameter(Mandatory = $true)]
    [string]$LlvmSourceRoot,
    [string]$GitPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$patchFile = Join-Path $scriptDir 'patches\flang-22.1-arm32-codegen.patch'
$targetFile = Join-Path $LlvmSourceRoot 'flang\lib\Optimizer\CodeGen\Target.cpp'
if (-not (Test-Path -LiteralPath $targetFile)) {
    throw "Invalid LLVM source root: $LlvmSourceRoot"
}
if (-not (Test-Path -LiteralPath $patchFile)) {
    throw "Missing Flang ARM32 patch: $patchFile"
}

if (-not $GitPath) {
    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    if (-not $gitCommand) { throw 'git is required to apply the Flang ARM32 patch' }
    $GitPath = $gitCommand.Source
}

$normalizedPatch = [IO.Path]::GetTempFileName()
$patchText = [IO.File]::ReadAllText($patchFile).Replace("`r`n", "`n")
[IO.File]::WriteAllText($normalizedPatch, $patchText, [Text.UTF8Encoding]::new($false))
Push-Location $LlvmSourceRoot
try {
    # Windows checkout can use CRLF while the pinned LLVM archive uses LF.
    & $GitPath apply --reverse --check $normalizedPatch 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Flang ARM32 patch is already applied: $LlvmSourceRoot"
        return
    }
    & $GitPath apply --check $normalizedPatch
    if ($LASTEXITCODE -ne 0) {
        throw "Flang ARM32 patch does not match this LLVM source: $LlvmSourceRoot"
    }
    & $GitPath apply $normalizedPatch
    if ($LASTEXITCODE -ne 0) { throw 'Failed to apply the Flang ARM32 patch' }
    Write-Host "Flang ARM32 patch applied: $LlvmSourceRoot"
} finally {
    Pop-Location
    Remove-Item -LiteralPath $normalizedPatch -Force
}
