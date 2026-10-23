# Network 1.0.0 — "reach things, and let things reach me"

## The product (user's words → decision)

A GUI for the networking CLIs that are hard to remember and hard to read on a phone:
`ssh`, `ssh-keygen`, `ssh -L/-R/-D`, `curl`, and a throw-away web server. Not a terminal
replacement: a **cockpit** — the same job Git does for `git`, Servers for `ps`, Sync for `tar`.

The id is `network` (new). The older `ssh` data card stays installed and untouched.

## What this device taught us (probed here, read-only)

| Fact | Evidence | Consequence |
|---|---|---|
| `ssh` 10.3, `ssh-keygen`, `ssh-keyscan`, `scp`, `curl`, `nc`, `ping`, `python3` exist; **`ss`, `autossh`, `dig`, `socat` do not** | `command -v` | build on ssh/ssh-keygen/curl/python3 only; no ss, no autossh |
| `~/.ssh` holds only `known_hosts` (no keys, no config) | `ls -la ~/.ssh` | first-run state matters: "no keys yet" is the common case |
| **`/proc/net/tcp` is Permission denied** to the app UID; `ip` cannot use netlink | `cat /proc/net/tcp`, `ip addr` | cannot list listeners from /proc/net. The host's verified `ServerProbe` (connect-verify + attribution) is the source, as in Servers |
| Guest and app share ONE network namespace | Servers' HttpCheck design; local `curl 127.0.0.1` reached a guest server | an app-side `Socket.connect(127.0.0.1:p)` is a kernel fact for "is the tunnel up"; no exec needed |
| `/etc/resolv.conf` is a real file in the rootfs, managed by PocketShell | `cat /etc/resolv.conf` | the guest's resolver list is readable host-side; we can query each nameserver ourselves |
| `ssh -G host` resolves effective config; `ssh-keygen -lf` prints fingerprints | ran both | SHA256 fingerprints are computed in Kotlin from the public key (no exec) and cross-checked in a test |
| Plugins exec under `PACKAGE_OPERATION` (minimal mounts, no /proc) | `GuestExecutionProfile` | ssh must be proven to run there — **not verifiable from this shell**; the app reports it as its own error ("sandbox cannot reach the network"), not as a bad host |

## Cost discipline (the lesson of Git 3.3–3.4)

- MINI loads **only local file reads** (parse `~/.ssh/config`, list `~/.ssh/*.pub`, read the
  tunnels file) once the card has stayed on screen — no network I/O, no exec, no polling.
- FULL touches the network **only on a user action** (CHECK, TEST, a host's reachability dot on
  tab open — bounded, 2 s, 6 parallel). Nothing runs while the page is off screen.
- Exec happens only for: key generation, a host TEST, host-key scan, starting a tunnel, starting a
  share. Tunnels and shares are plugin-scope foreground execs (proot is `--kill-on-exit`).
- No lock is ever held across I/O on the main thread (the freeze fixed in Git 3.4.1).

## Data model (pure, JVM-tested)

- **`SshConfig`** — line-preserving parse of `~/.ssh/config`. A host is a `Host alias` block; edit
  replaces exactly that block's lines, delete removes them, add appends. Everything else
  (comments, `Include`, `Match`, `Host *`, multi-alias blocks) is preserved byte-for-byte and shown
  read-only. Unmodelled directives inside an edited block are kept. Every value is validated so a
  newline can never smuggle in a `ProxyCommand`. A `.pre-network` copy is made before the first write.
- **`SshKeys`** — public key parse → type, bits, **SHA256 fingerprint** (sha256 of the decoded blob,
  base64 without padding), comment; private key present? passphrase-protected (cipher name in the
  `openssh-key-v1` header)? permissions too open?
- **`SshOutcome`** — classify `(exit code, stderr)` of a BatchMode `ssh` into: OK (with the
  service's greeting, e.g. GitHub's "Hi user!"), AUTH_FAILED (reachable, key refused),
  HOST_KEY_UNKNOWN, **HOST_KEY_CHANGED (loud)**, DNS, REFUSED, TIMEOUT, NO_ROUTE, PORT_IN_USE,
  OTHER(last line). Fixtures are real stderr captured on this device.
- **`Tunnel`** — LOCAL (`-L`), REMOTE (`-R`), DYNAMIC (`-D` SOCKS). Local binds are always
  `127.0.0.1` and ≥ 1024. argv is built from validated parts; the pid-file wrapper takes every
  variable as a positional argument (nothing user-controlled is spliced into shell text).
  Persisted as a tab-free text file `~/.config/pocketshell/network-tunnels.txt`, so Sync backs it up.
- **`ShareGuard`** — refuses to serve `/`, `/root`, `/etc`, or any folder whose subtree contains
  `.ssh`, `.gnupg`, `.aws`, `.env*`, `id_*` (bounded scan). Binds to the Wi-Fi address only, auto-stops
  (5 / 15 / 60 min, never unlimited), says plainly it is HTTP without a password.
- **`Checks`** — host-side: interface addresses, **UDP DNS query to each resolver in the guest's
  resolv.conf** (hand-built packet, parsed RCODE/answers), TCP connect to `1.1.1.1:443`, the Android
  captive-portal probe (`generate_204` → anything but 204 = captive/filtered). A pure verdict:
  ONLINE · CAPTIVE PORTAL · DNS BROKEN · NO INTERNET · OFFLINE.

## UI — two modes, one flat language (hairlines, mono small-caps, accent text, no cards)

**MINI (card).** Same shape as Git's: header `Network`, one line of status (`wlan0 192.168.1.20`
or `offline`), terminal + refresh icons, then the 4-stat strip
`HOSTS n · KEYS n · TUNNELS u/t up · SHARING off|on`. Nothing else, no folders, no lists.
Empty/first-run states say what to do (`No keys yet — create one`).

**FULL (maximized) — five tabs (bottom bar / side rail by width):**
`HOSTS · KEYS · TUNNELS · SHARE · CHECKS`
- **HOSTS** — rows from `~/.ssh/config` (alias, `user@host:port`, key, reachability dot). Row detail:
  TEST (BatchMode ssh, classified), TRUST HOST KEY (scan → show fingerprint → confirm → append to
  known_hosts), CONNECT (copies `ssh alias` + opens the terminal — the nav seam cannot pre-type),
  EDIT, DELETE (typed confirm). ADD HOST form.
- **KEYS** — each key: type/bits, fingerprint, comment, `passphrase`/`open perms` tags; COPY PUBLIC
  KEY, **TEST WITH GITHUB / GITLAB** (the one-tap "is my key accepted"), CREATE KEY (ed25519, no
  passphrase — stated), DELETE (typed confirm, irreversible).
- **TUNNELS** — saved forwards with live state (starting / up / dropped + reason), START/STOP,
  OPEN (for local forwards → companion browser), ADD/EDIT/DELETE.
- **SHARE** — (1) serve a folder on the Wi-Fi with a URL, countdown and STOP; (2) **listening
  servers** from the host probe with OPEN and a LAN-reachable verdict (connect to the Wi-Fi address).
- **CHECKS** — RUN CHECKS → the verdict and each line with timing.

## Mutations (all explicit, none automatic)

Write `~/.ssh/config` (backup first) · write `known_hosts` (after fingerprint confirm) · create /
delete a key (typed confirm for delete) · start / stop a tunnel or share. Nothing is ever sent
anywhere by the plugin itself; ssh talks only to the host the user chose.

## Tests

Config round-trip + edit/delete/add preservation + injection refusals; key fingerprint fixtures
(ed25519 + rsa generated here, fingerprints cross-checked with `ssh-keygen -lf`) and
encrypted-key detection; outcome classifier on real stderr; tunnel argv safety and codec; share
guard; DNS packet build/parse; verdict matrix; layout tiers; contract pins (no `while(true)` poll,
no lock across I/O, exec only in `NetworkOps`).

## Known limits (stated, not hidden)

- Whether `ssh` runs under the plugin exec profile on the device is **not verified in this session**.
- Keys are created without a passphrase (passing one on argv would leak it); passphrase-protected
  keys are detected and flagged, but BatchMode tests cannot unlock them.
- Tunnels and shares end when PocketShell is closed (proot kills them with the app); since 1.1.0 they are host jobs and survive switching apps. No auto-reconnect.
- No `ss`: the listening list is the host probe's verified listeners, not every socket.
- Sharing is plain HTTP with no password; it is bounded by Wi-Fi-only bind, a folder guard and an auto-stop. Since 1.1.0 it is served by an in-app Kotlin server (no python3, no guest command).
