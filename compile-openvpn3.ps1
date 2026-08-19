# Build the openvpn3 core and its SWIG Java binding for Android.
#
# Companion to compile-hevtun.ps1 and follows the same conventions: sources and
# build trees live under tools/ (gitignored), finished artifacts land in
# app/app/libs/<abi>/.
#
# Usage:
#   $env:NDK_HOME = "D:\brandvpn\tools\android-sdk\ndk\29.0.14206865"
#   .\compile-openvpn3.ps1                      # arm64-v8a only (spike default)
#   .\compile-openvpn3.ps1 -Abis 'arm64-v8a','armeabi-v7a','x86','x86_64'
#
# Prerequisites the script does not install:
#   * CMake + Ninja   -> sdkmanager --install "cmake;4.1.2"
#   * SWIG for Windows-> tools/ovpn3/swigwin-4.4.1/swig.exe
#   * Perl and Python -> both needed by mbedTLS's source generators
#
# Why this does not just call openvpn3's own CMake:
#   openvpn3's build assumes Linux/macOS/Windows. findcoredeps.cmake calls
#   find_package(PkgConfig REQUIRED) unconditionally and hard-sets OPENVPN_PLAT
#   to linux/osx/amd64 with no Android branch. Its own comments also say the
#   core "requires compilation of all core files as part of the target that uses
#   the core library", so there is no static libopenvpn3 to link against anyway.
#   Compiling the handful of .cpp files directly is the supported shape.

[CmdletBinding()]
param(
    [string]$NdkHome = $env:NDK_HOME,
    [string[]]$Abis = @('arm64-v8a'),
    [string]$Platform = 'android-24',
    [string]$Root = 'D:\brandvpn\tools\ovpn3',
    [string]$CMakeDir = 'D:\brandvpn\tools\android-sdk\cmake\4.1.2\bin',
    [string]$SwigExe = 'D:\brandvpn\tools\ovpn3\swigwin-4.4.1\swig.exe',
    # openvpn3 pins asio to this version in deps/vcpkg-ports/asio/vcpkg.json and
    # patches it. Both matter — see the asio section below.
    [string]$AsioTag = 'asio-1-36-0',
    [string]$MbedTlsBranch = 'mbedtls-3.6'
)

$ErrorActionPreference = 'Stop'

if (-not $NdkHome -or -not (Test-Path $NdkHome)) {
    throw "Android NDK not found. Set NDK_HOME or pass -NdkHome. Got: '$NdkHome'"
}
$toolchain = Join-Path $NdkHome 'build\cmake\android.toolchain.cmake'
if (-not (Test-Path $toolchain)) { throw "NDK toolchain file missing: $toolchain" }

$cmake = Join-Path $CMakeDir 'cmake.exe'
$ninja = Join-Path $CMakeDir 'ninja.exe'
foreach ($t in @($cmake, $ninja, $SwigExe)) {
    if (-not (Test-Path $t)) { throw "Required tool missing: $t" }
}

$deps = Join-Path $Root 'deps'
$ovpn3 = Join-Path $Root 'openvpn3'
$spike = Join-Path $Root 'spike'
New-Item -ItemType Directory -Force -Path $deps | Out-Null

# Runs a native exe and judges it by its exit code alone.
#
# The dance with ErrorActionPreference is not optional. In PowerShell 5.1 any
# line a native program writes to stderr is wrapped in a NativeCommandError, and
# with 'Stop' in force that becomes a terminating error before the exit code is
# ever read. cmake prints deprecation warnings to stderr and git prints its
# progress there, so without this every clone and half the builds "fail" while
# actually returning 0.
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments, [string]$What)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @Arguments
    }
    finally {
        $ErrorActionPreference = $prev
    }
    if ($LASTEXITCODE -ne 0) { throw "$What failed with exit code $LASTEXITCODE" }
}

# ----------------------------------------------------------------- sources --

if (-not (Test-Path $ovpn3)) {
    Write-Host "Cloning openvpn3 ..."
    Invoke-Native 'git' @('clone', '--depth', '1', 'https://github.com/OpenVPN/openvpn3.git', $ovpn3) 'git clone openvpn3'
}

# asio: the version is pinned AND patched. openvpn3 ships six patches under
# deps/vcpkg-ports/asio/ (NAT64, an async_connect hook, kovpn route ids, and
# more). Building against stock asio is not the same library — findcoredeps.cmake
# says as much: "asio should go first since some of our code requires a patched
# version".
$asio = Join-Path $deps 'asio'
if (-not (Test-Path $asio)) {
    Write-Host "Cloning asio $AsioTag and applying openvpn3's patches ..."
    Invoke-Native 'git' @('clone', '--depth', '1', '--branch', $AsioTag, 'https://github.com/chriskohlhoff/asio.git', $asio) 'git clone asio'
    Push-Location $asio
    try {
        Get-ChildItem (Join-Path $ovpn3 'deps\vcpkg-ports\asio\*.patch') | Sort-Object Name | ForEach-Object {
            Invoke-Native 'git' @('apply', $_.FullName) "git apply $($_.Name)"
            Write-Host "  applied $($_.Name)"
        }
    }
    finally { Pop-Location }
}

foreach ($r in @(
        @{ name = 'lz4'; url = 'https://github.com/lz4/lz4.git'; extra = @() },
        @{ name = 'fmt'; url = 'https://github.com/fmtlib/fmt.git'; extra = @() },
        @{ name = 'mbedtls'; url = 'https://github.com/Mbed-TLS/mbedtls.git'; extra = @('--branch', $MbedTlsBranch, '--recurse-submodules', '--shallow-submodules') }
    )) {
    $dir = Join-Path $deps $r.name
    if (-not (Test-Path $dir)) {
        Write-Host "Cloning $($r.name) ..."
        Invoke-Native 'git' (@('clone', '--depth', '1') + $r.extra + @($r.url, $dir)) "git clone $($r.name)"
    }
}

# mbedTLS ships some sources only in its release tarballs; a git checkout has to
# generate them or CMake fails with "No SOURCES given to target: mbedcrypto".
$mbedtls = Join-Path $deps 'mbedtls'
if (-not (Test-Path (Join-Path $mbedtls 'library\error.c'))) {
    Write-Host "Generating mbedTLS sources ..."
    Push-Location $mbedtls
    try {
        Invoke-Native 'python' @('scripts/generate_driver_wrappers.py') 'generate_driver_wrappers'
        Invoke-Native 'perl'   @('scripts/generate_errors.pl') 'generate_errors'
        Invoke-Native 'perl'   @('scripts/generate_features.pl') 'generate_features'
        Invoke-Native 'python' @('framework/scripts/generate_ssl_debug_helpers.py') 'generate_ssl_debug_helpers'
    }
    finally { Pop-Location }
}

# -------------------------------------------------------------- swig (once) --

# The binding is architecture-independent, so it is generated once and reused by
# every ABI. openvpn3's own CMake only wires SWIG up for Python, but ovpncli.i
# carries live #ifdef SWIGJAVA branches, so the Java path is maintained upstream.
$javaOut = Join-Path $spike 'java\net\openvpn\ovpn3'
if (-not (Test-Path (Join-Path $spike 'ovpncli_wrap.cxx'))) {
    Write-Host "Generating SWIG Java binding ..."
    New-Item -ItemType Directory -Force -Path $javaOut | Out-Null
    Push-Location (Join-Path $ovpn3 'client')
    try {
        Invoke-Native $SwigExe @(
            '-c++', '-java',
            '-package', 'net.openvpn.ovpn3',
            '-outdir', $javaOut,
            "-I$ovpn3",
            '-o', (Join-Path $spike 'ovpncli_wrap.cxx'),
            'ovpncli.i') 'swig'
    }
    finally { Pop-Location }
}

# ------------------------------------------------------------------ per ABI --

$strip = (Get-ChildItem (Join-Path $NdkHome 'toolchains\llvm\prebuilt') -Recurse -Filter 'llvm-strip.exe' |
    Select-Object -First 1).FullName

foreach ($abi in $Abis) {
    Write-Host "`n=== $abi ==="
    $out = Join-Path $Root "out\$abi"

    $common = @(
        '-G', 'Ninja',
        "-DCMAKE_MAKE_PROGRAM=$ninja",
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
        "-DANDROID_ABI=$abi",
        "-DANDROID_PLATFORM=$Platform",
        "-DCMAKE_INSTALL_PREFIX=$out",
        '-DCMAKE_BUILD_TYPE=Release'
    )

    foreach ($d in @(
            @{ name = 'lz4'; src = (Join-Path $deps 'lz4\build\cmake'); opts = @('-DBUILD_SHARED_LIBS=OFF', '-DBUILD_STATIC_LIBS=ON', '-DLZ4_BUILD_CLI=OFF') },
            @{ name = 'fmt'; src = (Join-Path $deps 'fmt'); opts = @('-DFMT_TEST=OFF', '-DFMT_DOC=OFF') },
            @{ name = 'mbedtls'; src = $mbedtls; opts = @('-DENABLE_TESTING=OFF', '-DENABLE_PROGRAMS=OFF', '-DUSE_SHARED_MBEDTLS_LIBRARY=OFF', '-DUSE_STATIC_MBEDTLS_LIBRARY=ON') }
        )) {
        $b = Join-Path $Root "build\$abi\$($d.name)"
        Write-Host "  building $($d.name) ..."
        Invoke-Native $cmake (@('-S', $d.src, '-B', $b) + $common + $d.opts) "configure $($d.name)"
        Invoke-Native $cmake @('--build', $b, '--target', 'install') "build $($d.name)"
    }

    Write-Host "  building libovpncli.so ..."
    $b = Join-Path $Root "build\$abi\spike"
    Invoke-Native $cmake (@('-S', $spike, '-B', $b) + $common) 'configure ovpncli'
    Invoke-Native $cmake @('--build', $b, '--target', 'ovpncli') 'build ovpncli'

    # Staged under tools/, NOT into app/app/libs/. That directory is on
    # jniLibs.srcDirs, so anything dropped there ships in every APK from that
    # moment on — and nothing calls this library yet. The copy into the app
    # belongs to the integration commit, not to the build script.
    $dest = Join-Path $Root "staged-libs\$abi"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $so = Join-Path $dest 'libovpncli.so'
    Copy-Item (Join-Path $b 'libovpncli.so') $so -Force
    if ($strip) { & $strip --strip-unneeded $so }
    Write-Host ("  -> {0}  ({1:N2} MB stripped)" -f $so, ((Get-Item $so).Length / 1MB))
}

Write-Host "`nJava binding: $javaOut"
Write-Host 'Done.'
