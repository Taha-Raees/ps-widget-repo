# ps-widget-repo

PocketShell's widget distribution repository. Home Applications marked
**installable** in the app download their manifests from here — one
place to publish and update widgets outside the app build.

## Trust model (non-negotiable)

Widgets are **DATA, never code**. A manifest can only reference a
built-in probe primitive (`probe.kind` from the fixed vocabulary:
`proc.net.listen`, `ssh.guest`, `storage.rootfs`) and a small card
template. Nothing in a manifest is executed, no binaries are ever
downloaded, and there is no escape hatch to run shell commands.

## Layout

- `catalog.json` — the index the app fetches (HTTPS only)
- `widgets/*.json` — one manifest per widget

## Manifest schema (v1)

See `app/src/main/java/app/pocketshell/widget/external/WidgetManifest.kt`
in the PocketShell app: id, name, version (semver), summary, author,
minAppVersion, capabilities, probe{kind,params}, card{headline,
emptyLine,itemTemplate,maxLines}. Template fields per kind:
`proc.net.listen` → `{port}`, `{process}`, `{text}`; `ssh.guest` →
`{target}`, `{text}`.
