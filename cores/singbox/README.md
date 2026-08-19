# sing-box, with the AmneziaWG graft

`libbox.aar` — the sing-box core the app runs — is built from here. This
directory is the **corresponding source** for it, in the sense GPLv3 means:
everything needed to reproduce the binary the app ships.

sing-box is licensed GPLv3-or-later. Our change to it is the AmneziaWG graft,
so the modified work is GPLv3 too, and that is why this directory is public.

## What is here

| | |
|---|---|
| `amneziawg.patch` | our change to sing-box — 21 files, 6 modified and 15 added |
| `wireguard-go-awg/` | the AmneziaWG-capable `wireguard-go`, MIT, vendored |
| `build-aar.sh` | the build |

The sing-box tree itself is **not** vendored — it is upstream's, unmodified
until the patch is applied, so a pinned commit plus the patch says the same
thing in 150 MB less.

## Reproducing `libbox.aar`

```sh
git clone https://github.com/SagerNet/sing-box.git
cd sing-box
git checkout 2fdd5384f74390e297bd0698c3d603a41b725a3f
git apply ../amneziawg.patch
bash ../build-aar.sh
```

Two things that will bite if changed:

**`wireguard-go-awg/` must stay where it is, beside the sing-box tree.** The
patch's `go.mod` hunk is `replace github.com/sagernet/wireguard-go =>
../wireguard-go-awg`, a *relative* path. Move it and the build silently
resolves the upstream module instead, then fails later at `IpcSet` on the
unknown `jc=` / `h1=` / `i1=` keys.

**The commit is a pin, not a suggestion.** It sits on upstream's `testing`
branch, 215 commits ahead of the newest stable tag at the time — that is where
the anti-censorship work lands first, and it is also a branch where fields get
removed without a deprecation window. Building against a different commit is
fine; building against a different commit and assuming the patch still applies
is not.

## Licences

* sing-box — GPL-3.0-or-later, © 2022 nekohasekai. See the LICENSE in the tree
  you clone.
* `wireguard-go-awg/` — MIT. Its LICENSE and copyright are kept in place.
