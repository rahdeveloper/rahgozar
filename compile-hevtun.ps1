# Windows port of compile-hevtun.sh.
#
# The upstream script is written for Linux/macOS: it calls the shell
# `ndk-build`, which the Windows NDK does not ship (only ndk-build.cmd), and it
# symlinks the source tree into jni/, which needs privileges Windows does not
# grant by default. This script copies instead of symlinking and drives
# ndk-build.cmd.
#
# Usage:
#   $env:NDK_HOME = "D:\brandvpn\tools\android-sdk\ndk\29.0.14206865"
#   .\compile-hevtun.ps1
#
# PkgName must match the package that owns TProxyService. It is baked into the
# JNI symbol names, so it has to be updated when the app is rebranded.

[CmdletBinding()]
param(
    [string]$NdkHome  = $env:NDK_HOME,
    [string]$PkgName  = 'com/rahgozar/app/service',
    [string]$Abis     = 'armeabi-v7a arm64-v8a x86 x86_64',
    [string]$Platform = 'android-24',
    # Short path on purpose: ndk-build creates deep object trees that overrun
    # the Windows path limit when built under a long temp directory.
    [string]$BuildDir = 'D:\brandvpn\tools\hevbuild'
)

$ErrorActionPreference = 'Stop'

if (-not $NdkHome -or -not (Test-Path $NdkHome)) {
    throw "Android NDK not found. Set NDK_HOME or pass -NdkHome. Got: '$NdkHome'"
}
$ndkBuild = Join-Path $NdkHome 'ndk-build.cmd'
if (-not (Test-Path $ndkBuild)) { throw "ndk-build.cmd not found at $ndkBuild" }

$root = $PSScriptRoot
$src  = Join-Path $root 'hev-socks5-tunnel'
if (-not (Test-Path (Join-Path $src 'Android.mk'))) {
    throw "hev-socks5-tunnel sources missing at $src (clone it with --recursive)"
}

Remove-Item $BuildDir -Recurse -Force -ErrorAction SilentlyContinue
$jni = Join-Path $BuildDir 'jni'
New-Item -ItemType Directory -Force -Path $jni | Out-Null

Write-Host "Staging sources into $jni ..."
Copy-Item $src (Join-Path $jni 'hev-socks5-tunnel') -Recurse -Force

# 1) JNI shared library (libhev-socks5-tunnel.so) — loaded in-process by
#    TProxyService for the VpnService hev tun mode.
Set-Content -Path (Join-Path $jni 'Android.mk') `
    -Value 'include $(call all-subdir-makefiles)' -Encoding ascii

Push-Location $BuildDir
try {
    Write-Host "Building JNI library ..."
    & $ndkBuild `
        "NDK_PROJECT_PATH=." `
        "APP_BUILD_SCRIPT=jni/Android.mk" `
        "APP_ABI=$Abis" `
        "APP_PLATFORM=$Platform" `
        "NDK_LIBS_OUT=$BuildDir\libs" `
        "NDK_OUT=$BuildDir\obj" `
        "APP_CFLAGS=-O3 -DPKGNAME=$PkgName" `
        "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"
    if ($LASTEXITCODE -ne 0) { throw "ndk-build (JNI library) failed with $LASTEXITCODE" }

    # 2) Standalone executable (libhevsockstun.so) — run as a separate root
    #    process for the Root run mode. Same sources, but hev-main.c's main()
    #    is built and linked with BUILD_EXECUTABLE.
    $execMk = @'
TOP_PATH := $(call my-dir)/hev-socks5-tunnel

ifeq ($(filter $(modules-get-list),yaml),)
    include $(TOP_PATH)/third-part/yaml/Android.mk
endif
ifeq ($(filter $(modules-get-list),lwip),)
    include $(TOP_PATH)/third-part/lwip/Android.mk
endif
ifeq ($(filter $(modules-get-list),hev-task-system),)
    include $(TOP_PATH)/third-part/hev-task-system/Android.mk
endif

LOCAL_PATH := $(TOP_PATH)
SRCDIR := $(LOCAL_PATH)/src

include $(CLEAR_VARS)
include $(LOCAL_PATH)/build.mk
LOCAL_MODULE    := hevsockstun
LOCAL_SRC_FILES := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))
LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/src/misc \
	$(LOCAL_PATH)/src/core/include \
	$(LOCAL_PATH)/third-part/yaml/include \
	$(LOCAL_PATH)/third-part/lwip/src/include \
	$(LOCAL_PATH)/third-part/lwip/src/ports/include \
	$(LOCAL_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED
LOCAL_CFLAGS += $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
include $(BUILD_EXECUTABLE)
'@
    Set-Content -Path (Join-Path $jni 'exec.mk') -Value $execMk -Encoding ascii

    Write-Host "Building standalone executable ..."
    & $ndkBuild `
        "NDK_PROJECT_PATH=." `
        "APP_BUILD_SCRIPT=jni/exec.mk" `
        "APP_ABI=$Abis" `
        "APP_PLATFORM=$Platform" `
        "NDK_LIBS_OUT=$BuildDir\libs-exec" `
        "NDK_OUT=$BuildDir\obj-exec" `
        "APP_CFLAGS=-O3" `
        "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"
    if ($LASTEXITCODE -ne 0) { throw "ndk-build (executable) failed with $LASTEXITCODE" }
}
finally {
    Pop-Location
}

# Stage both artifacts under app/app/libs/<abi>/. The executable is renamed
# to lib*.so so the installer extracts it into nativeLibraryDir as an
# executable, with a filename distinct from the JNI library above.
$out = Join-Path $root 'app\app\libs'
New-Item -ItemType Directory -Force -Path $out | Out-Null
Copy-Item (Join-Path $BuildDir 'libs\*') $out -Recurse -Force

foreach ($abi in $Abis.Split(' ')) {
    $exe = Join-Path $BuildDir "libs-exec\$abi\hevsockstun"
    if (-not (Test-Path $exe)) { throw "missing build output: $exe" }
    Copy-Item $exe (Join-Path $out "$abi\libhevsockstun.so") -Force
}

Write-Host "`nDone. Artifacts in $out :"
Get-ChildItem $out -Recurse -Filter *.so |
    Select-Object @{n = 'abi'; e = { $_.Directory.Name } }, Name, Length |
    Format-Table -AutoSize
