# Rahgozar — Android client

A VPN client for Android. It carries three tunnel engines — Xray, sing-box
(with AmneziaWG) and OpenVPN 3 — and takes its server list and settings from
its operator's panel rather than from files the user imports.

## This is a fork of v2rayNG

Rahgozar is derived from **[v2rayNG](https://github.com/2dust/v2rayNG)** by
2dust, which is licensed under the **GNU General Public License v3**. That
licence carries over to this work: the app is GPLv3, and this repository is
the corresponding source for the builds we distribute.

The upstream copyright notices are kept where they are. This repository is not
affiliated with or endorsed by the v2rayNG project.

The corresponding source for every released build lives at
<https://github.com/rahdeveloper/rahgozar>; each release is tagged there with the
version it was built from.

What is different from upstream, in short:

* the configuration import, editor, subscription, log and backup screens were
  removed — the app cannot be pointed at a server it was not given;
* the UI was redesigned and is English-only;
* sing-box and OpenVPN 3 were added as second and third engines;
* AmneziaWG was grafted onto sing-box (see `cores/singbox/`);
* routing is a single fixed ruleset rather than a user setting.

## Layout

| | |
|---|---|
| `app/` | the Android application (Gradle project) |
| `cores/singbox/` | our AmneziaWG graft on sing-box — the source `libbox.aar` is built from |
| `compile-hevtun.sh`, `compile-hevtun.ps1` | build `libhev-socks5-tunnel.so` |
| `compile-openvpn3.ps1` | build `libovpncli.so` and its SWIG Java bindings |
| `LICENSE` | GPLv3 |

## Building

```sh
cd app
./gradlew assemblePlaystoreRelease
```

That works only once the native pieces are in `app/app/libs/`. They are build
products and are deliberately not committed — GPL asks for the source they come
from, and that is what the directories above hold. Each is built differently:

| Artefact | How |
|---|---|
| `libbox.aar` (sing-box + AmneziaWG) | `cores/singbox/README.md` — clone the pinned commit, apply the patch, run the build |
| `libhev-socks5-tunnel.so` | clone [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) into `hev-socks5-tunnel/` at commit `64cc609f945253b0e9ebc56317d544268f3c68c1` (`--recursive` — it has submodules), then `bash compile-hevtun.sh` (needs the NDK) |
| `libovpncli.so` + Java bindings | `compile-openvpn3.ps1` (CMake + SWIG; pulls asio, mbedTLS, LZ4) |
| `libv2ray.aar` (Xray) | **not built here** — a prebuilt release of [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite), dropped into `app/app/libs/` as-is |

Signing needs a `keystore.properties` beside `app/build.gradle.kts`; see
`app/keystore.properties.example`. No key material is in this repository.

## Licences of what ships

| Component | Licence |
|---|---|
| this app (fork of v2rayNG) | GPL-3.0 |
| sing-box, with our AmneziaWG graft | GPL-3.0-or-later |
| wireguard-go (AmneziaWG) | MIT |
| AndroidLibXrayLite | LGPL-3.0 |
| Xray-core | MPL-2.0 |
| OpenVPN 3 Core | MPL-2.0 (of its `MPL-2.0 OR AGPL-3.0-only` dual licence) |
| hev-socks5-tunnel | MIT |
| MMKV | BSD-3-Clause |
| AndroidX, Compose, kotlinx, OkHttp, Gson, Tink, WorkManager | Apache-2.0 |

The app also links the Google Mobile Ads SDK, which is proprietary.
