# Materialise git symlinks that were checked out as plain text files.
#
# hev-socks5-core publishes its public headers as include/*.h symlinks pointing
# at ../src/*.h. Without core.symlinks (which needs Developer Mode or admin on
# Windows) git writes the link target as the file's content instead, so the
# compiler reads "../src/hev-rbtree.h" as if it were C and every type in it
# comes out undefined. Replacing each placeholder with a real copy makes the
# tree build the same way it does on Linux.
#
# Safe to re-run: a file is only touched when its entire content is a single
# relative path that resolves to a different existing file.

[CmdletBinding()]
param(
    [string]$Root = (Join-Path $PSScriptRoot 'hev-socks5-tunnel')
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Root)) { throw "Path not found: $Root" }

$fixed = 0
$skipped = @()

Get-ChildItem $Root -Recurse -File -Force |
    Where-Object { $_.Length -gt 0 -and $_.Length -le 512 -and $_.FullName -notmatch '\\\.git\\' } |
    ForEach-Object {
        $raw = [System.IO.File]::ReadAllText($_.FullName)
        if ($raw -match "[`r`n`0]") { return }          # real content, not a link
        $candidate = $raw.Trim()
        if ($candidate -eq '' -or $candidate -notmatch '[/\\]') { return }

        $target = Join-Path $_.DirectoryName ($candidate -replace '/', '\')
        if (-not (Test-Path $target -PathType Leaf)) {
            $skipped += "$($_.FullName) -> $candidate (target missing)"
            return
        }
        $resolved = (Resolve-Path $target).Path
        if ($resolved -eq $_.FullName) { return }

        Copy-Item $resolved $_.FullName -Force
        $script:fixed++
    }

Write-Host "Materialised $fixed symlink placeholder(s) under $Root"
if ($skipped.Count -gt 0) {
    Write-Host "Skipped (target not found):"
    $skipped | ForEach-Object { Write-Host "  $_" }
}
