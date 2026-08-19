#!/usr/bin/env bash
# Build libbox.aar for all four Android ABIs.
# MUST run from bash: PowerShell splits -javapkg=io.nekohasekai at the dot.
# platforms/android-35 is a junction to android-37.0 — gomobile's Atoi rejects "37.0".
#
# `with_awg` is in the tag list and everything it turns on lives in files gated
# on it, so this is the same core it always was plus AmneziaWG. The obfuscation
# needs the vendored wireguard-go in ../wireguard-go-awg (go.mod replaces the
# module with it); a build that resolved the upstream module instead would fail
# at IpcSet on the unknown jc=/h1=/i1= keys rather than silently degrade.
set -euo pipefail

export JAVA_HOME="D:/brandvpn/tools/jdk17/jdk-17.0.20+8"
export ANDROID_HOME="D:/brandvpn/tools/android-sdk"
export ANDROID_NDK_HOME="D:/brandvpn/tools/android-sdk/ndk/29.0.14206865"
export PATH="$JAVA_HOME/bin:/c/Users/alifrm/go/bin:$PATH"
export GOTOOLCHAIN=auto

cd "D:/brandvpn/tools/singbox/sing-box"

# Flags/tags mirror cmd/internal/build_libbox main variant (AndroidAPI 24, full set).
exec gomobile bind -v -o libbox.aar -target android -androidapi 24 \
  -javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=2fdd538+awg -X runtime.godebugDefault=multipathtcp=0,tlssha1=1,tlsunsafeekm=1 -checklinkname=0 -s -w -buildid=" \
  -tags with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,with_usbip,with_openvpn,with_openconnect,badlinkname,tfogo_checklinkname0,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird,with_awg \
  ./experimental/libbox
