# Aether — Cloudflare WARP over MASQUE

`libwhiteaesther_core.so` — the Aether core the app runs — is built from here.
This directory is the **corresponding source** for it, in the sense the licence
means: everything needed to reproduce the binary the app ships.

Aether is licensed **AGPL-3.0** (see `LICENSE`). We ship its `.so` unmodified,
so distributing it obliges us to offer this source. The binary is a build
product and is not checked in (`.gitignore` excludes `app/app/libs/**/*.so`), so
what lives here is a pinned upstream commit plus the recipe — the same thing in
hundreds of MB less.

## What it is

A re-implementation of the Cloudflare WARP client whose transport is **MASQUE
CONNECT-IP (RFC 9484) over HTTP/3 (QUIC)**, with an HTTP/2-over-TCP fallback for
networks that block UDP. It registers a free WARP device against
`api.cloudflareclient.com` and tunnels IP to `consumer-masque.cloudflareclient.com`.
There is no server of ours involved — it rides Cloudflare's own free WARP.

The engine is Rust (Cloudflare **quiche** + **boringssl** + **boringtun** +
**smoltcp**); the JNI surface the app binds to is
`com.whitedns.whiteaesther.core.NativeAetherBridge`.

## Source

Upstream, **unmodified** (no patch of ours — unlike the sing-box tree beside it):

- Repository: <https://github.com/WhiteDNS/WhiteAestherMobile>
- Pinned commit: `2151ccb22da71d99d597a9e71ba3b5f0d0f7fdfb`
  (2026-08-20, "Stop MASQUE enrolment from revoking the WireGuard key")

## Reproducing `libwhiteaesther_core.so`

Build on Linux (or WSL) — the Windows host toolchain is unreliable for this. The
Android JNI library is the `native/android-bridge` crate; its
`native/rust-toolchain.toml` pins Rust **1.88.0**.

```sh
git clone --recursive https://github.com/WhiteDNS/WhiteAestherMobile.git
cd WhiteAestherMobile
git checkout 2151ccb22da71d99d597a9e71ba3b5f0d0f7fdfb

# toolchain: rustup with the 1.88.0 targets, cargo-ndk, and an Android NDK.
rustup toolchain install 1.88.0 --profile minimal -c clippy -c rustfmt \
    -t aarch64-linux-android -t armv7-linux-androideabi -t x86_64-linux-android
cargo install cargo-ndk
# Android NDK (r28c used here); bindgen in boring-sys needs the NDK's libclang:
export ANDROID_NDK_HOME="$PWD/android-ndk"          # or your NDK path
export LIBCLANG_PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib"

cd native/android-bridge
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ./jniLibs build --release
```

The resulting `jniLibs/<abi>/libwhiteaesther_core.so` are what
`app/app/libs/<abi>/` carry in a build. `libquiche.so` and `libboringtun.so`
are build by-products and are not packaged (they are statically linked into
`libwhiteaesther_core.so`).
