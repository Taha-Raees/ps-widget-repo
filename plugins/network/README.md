# network — a cockpit for the networking CLIs

SSH hosts, keys, port forwards, folder sharing and connectivity checks, as one
PocketShell Home application. It does not replace `ssh`: it reads and writes the same
files (`~/.ssh/config`, `~/.ssh/*`, `known_hosts`), runs the same commands, and shows
the result in words a person can act on. Design + device evidence: `PLAN.md`.

| | what it shows |
|---|---|
| **MINI** (the card) | `Network`, one status line (`wlan0 192.168.1.20` or `offline`), and four numbers: `HOSTS · KEYS · TUNNELS up/total · SHARING`. A pure view of a few small local file reads — no network traffic, no exec, no list. First-run hints say what to do next. |
| **MAXIMUM** (the maximized page) | five tabs behind a bottom bar (side rail when wide): **HOSTS** (from `~/.ssh/config`: reachability, TEST, TRUST KEY with fingerprint confirmation, CONNECT, add/edit/delete), **KEYS** (type, bits, SHA256 fingerprint, passphrase / permission flags, COPY PUBLIC KEY, **TEST WITH GITHUB / GITLAB**, create, delete), **TUNNELS** (`-L` / `-R` / SOCKS, live state, reason when dropped), **SHARE** (serve a folder on the Wi-Fi with a countdown; listening dev servers with OPEN and a Wi-Fi reachability verdict), **CHECKS** (addresses, each Linux DNS server, internet by address, captive-portal probe, one verdict). |

Flat PocketShell style: hairline rules, mono small-caps, accent text. The maximize toggle is host chrome.

## Safety rules (each is tested)

- `~/.ssh/config` edits replace only the edited block; comments, `Include`, `Match`, `Host *` and
  multi-alias blocks are preserved byte-for-byte. A `config.pre-network` copy is kept. Values that could
  inject a directive (newlines, `ProxyCommand`) are refused.
- Host keys are trusted only after the user compares the fingerprints shown; only plain key lines are stored.
- A tunnel binds `127.0.0.1` only. Process arguments are built from validated parts, with no shell text.
- Sharing refuses `/`, the home folder, `/etc`, and any folder containing `.ssh`, `.gnupg`, `.aws`, `.env`,
  private keys…; binds the Wi-Fi address only; always has an end time (5 / 15 / 60 min); is plain HTTP and says so.
- Deleting a key requires typing its name. Keys are created without a passphrase (stated in the dialog).

## Cost

Nothing polls. The card reads its small files once after it has stayed on screen 0.8 s. The network is touched
only by an explicit action in the maximized page. Exec happens only to create a key, test a host, scan a host key,
and run a tunnel or a share.

## Build

```
tools/build-plugin.sh network 1.1.0 --update-catalog
./gradlew :plugins:network:testDebugUnitTest
```

## 1.1.0 — limitations solved in the app

- **Sharing needs no python**: the folder is served by a small read-only HTTP server inside the app (`ShareServer`).
  Dotfiles and secret names are never served, a path cannot leave the folder (symlinks included), GET/HEAD only.
- **CONNECT types the command** at the terminal prompt (`openTerminalWith`; never Enter). An older app only opens the
  terminal, so the command is also copied.
- **Host jobs** keep tunnels and shares alive in the background (see below).
- `ss` and `ip addr` now work in the Linux shell (host bridge, app release required) - see the app's `docs/PLUGIN-HOST-SERVICES.md`.

## Known limits

- Whether `ssh` runs under the plugin's exec profile on a device was **not verified in this session**; if it
  cannot, the app says "ssh could not run here" instead of blaming the host.
- Tunnels and shares are host JOBS (1.1.0): on an app with the job service they keep running when you switch apps and the
  notification offers STOP; on an older app they live only while PocketShell is in front. Either way they end when
  PocketShell is closed, and there is no auto-reconnect.
- No `ss`: the listening list is the host probe's verified listeners.
