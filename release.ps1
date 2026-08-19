# Builds a release and publishes the source it was built from, in that order.
#
# The order is the point. Rahgozar is a GPLv3 work, so everyone who receives a
# build is owed the source *that build* came from -- not the newest source, that
# one. A tag created after the fact, from a tree that has moved on, says
# something untrue. So this script refuses to build until the commit is public,
# and tags that exact commit.
#
#   .\release.ps1              # build the AAB for Play, tag it, push the tag
#   .\release.ps1 -Apk         # also build an APK for trying on a phone
#   .\release.ps1 -DryRun      # say what would happen, change nothing
#
# It does not create your signing key. See docs/RELEASE.md -- that key is
# permanent and cannot be replaced once an app is on Play.

param(
    [switch]$Apk,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Fail($message) { Write-Host "`n  $message`n" -ForegroundColor Red; exit 1 }
function Step($message) { Write-Host "`n> $message" -ForegroundColor Cyan }

# --- 1. the source must be public before the binary exists -----------------
Step 'Checking the source is committed and pushed'

if (git status --porcelain) {
    git status --short
    Fail 'Uncommitted changes. Commit and push them first -- the binary must have a public commit to correspond to.'
}

$head = (git rev-parse HEAD).Trim()
if (-not (git branch -r --contains $head)) {
    Fail "Commit $($head.Substring(0,7)) is not on the remote yet. Run: git push"
}
Write-Host "  ok -- $($head.Substring(0,7)) is published"

# --- 2. the version this release will carry --------------------------------
$gradleFile  = Get-Content 'app/app/build.gradle.kts' -Raw
$versionName = [regex]::Match($gradleFile, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($gradleFile, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $versionName -or -not $versionCode) { Fail 'Could not read versionName/versionCode.' }

$tag = "v$versionName+$versionCode"
Write-Host "  version $versionName ($versionCode) -> tag $tag"

if (git tag -l $tag) {
    $tagged = (git rev-list -n 1 $tag).Trim()
    if ($tagged -ne $head) {
        Fail "Tag $tag already exists on a different commit. Bump versionCode in app/app/build.gradle.kts."
    }
    Write-Host '  tag already exists on this commit'
}

if ($DryRun) { Write-Host "`n(dry run -- nothing built, nothing pushed)`n" -ForegroundColor Yellow; exit 0 }

# --- 3. build ---------------------------------------------------------------
Step 'Building the release bundle for Play'
Push-Location app
try {
    .\gradlew.bat :app:bundlePlaystoreRelease --console=plain
    if ($LASTEXITCODE -ne 0) { Fail 'Build failed. Nothing was tagged.' }
    if ($Apk) {
        .\gradlew.bat :app:assemblePlaystoreRelease --console=plain
        if ($LASTEXITCODE -ne 0) { Fail 'APK build failed. The bundle is fine; nothing was tagged.' }
    }
} finally { Pop-Location }

# --- 4. tag the commit the binary came from --------------------------------
Step 'Tagging and publishing'
if (-not (git tag -l $tag)) {
    git tag -a $tag -m "Rahgozar $versionName ($versionCode)"
}
git push origin $tag
if ($LASTEXITCODE -ne 0) { Fail "Built and tagged locally, but the tag did not reach the remote. Run: git push origin $tag" }

Write-Host "`nDone." -ForegroundColor Green
Write-Host "  bundle  app/app/build/outputs/bundle/playstoreRelease/"
Write-Host "  tag     $tag -> $($head.Substring(0,7)), pushed"
Write-Host "`nStill yours to do:"
Write-Host "  - upload the .aab in the Play Console"
Write-Host "  - register the signing certificate's SHA-256 in the panel (docs/RELEASE.md section 4-5)"
Write-Host ""
