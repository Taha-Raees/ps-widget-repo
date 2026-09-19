#!/usr/bin/env bash
# tools/build-plugin.sh — the CANONICAL build + publish pipeline for the
# compiled widget plugins in this repo (ps-widget-repo). This is the exact
# pipeline the PocketShell app used before the sources moved here
# (pocketshell tools/build-hello-plugin.sh), generalized to every plugin:
#
#   source   : plugins/<id>/src  (this repo)
#   classes  : plugins/<id>/build/tmp/kotlin-classes/debug
#   jar      : the module's own classes, one jar
#   dex      : d8 --release --min-api 26  ->  classes.dex
#   artifact : releases/<id>/<version>/plugin.jar   (classes.dex zipped)
#   catalog  : (--update-catalog) pins version + dex path + sha256
#
# Usage:
#   tools/build-plugin.sh <plugin-id> <version> [--update-catalog]
# Example (the worked one):
#   tools/build-plugin.sh hello 1.0.0 --update-catalog
#
# Known benign noise: d8 on build-tools 36.1.0 emits "malformed kotlin.Metadata"
# warnings on Kotlin 2.4 metadata — the metadata is stripped in dex output.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_DIR"

usage() { echo "usage: $0 <plugin-id> <version> [--update-catalog]" >&2; exit 2; }

[ $# -ge 2 ] || usage
PLUGIN_ID="$1"
VERSION="$2"
UPDATE_CATALOG=0
[ "${3:-}" = "--update-catalog" ] && UPDATE_CATALOG=1
[ $# -le 3 ] || usage

# --- argument sanity: both land in filesystem paths --------------------------------
echo "$PLUGIN_ID" | grep -Eq '^[a-z][a-z0-9-]{0,31}$' || { echo "FATAL: bad plugin id '$PLUGIN_ID'" >&2; exit 2; }
echo "$VERSION"  | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "FATAL: version '$VERSION' is not semver X.Y.Z" >&2; exit 2; }
[ -d "plugins/$PLUGIN_ID/src" ] || { echo "FATAL: no plugins/$PLUGIN_ID module in this repo" >&2; exit 2; }

# --- toolchain ---------------------------------------------------------------------
SDK_DIR=""
if [ -f local.properties ]; then
    SDK_DIR=$(grep -E '^sdk\.dir=' local.properties | cut -d= -f2-)
fi
SDK_DIR="${SDK_DIR:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
D8="$SDK_DIR/build-tools/36.1.0/d8"
ANDROID_JAR="$SDK_DIR/platforms/android-36/android.jar"
[ -x "$D8" ] || { echo "FATAL: d8 not found at $D8 (build-tools 36.1.0)" >&2; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "FATAL: android.jar not found at $ANDROID_JAR (platform android-36)" >&2; exit 1; }

# --- 1. compile ---------------------------------------------------------------------
echo "== [1/4] gradlew :plugins:$PLUGIN_ID:assembleDebug"
./gradlew --console=plain -q ":plugins:$PLUGIN_ID:assembleDebug"

CLASSES_DIR="plugins/$PLUGIN_ID/build/tmp/kotlin-classes/debug"
[ -d "$CLASSES_DIR" ] || { echo "FATAL: $CLASSES_DIR missing after build" >&2; exit 1; }

# --- 2. jar the module's own classes ------------------------------------------------
echo "== [2/4] jar $CLASSES_DIR -> classes jar"
WORK="plugins/$PLUGIN_ID/build/plugin"
rm -rf "$WORK"
mkdir -p "$WORK/dex"
( cd "$CLASSES_DIR" && zip -qr "$OLDPWD/$WORK/$PLUGIN_ID-classes.jar" . )

# --- 3. dex --------------------------------------------------------------------------
echo "== [3/4] d8 -> classes.dex"
"$D8" --release --min-api 26 \
    --lib "$ANDROID_JAR" \
    --classpath "$WORK/$PLUGIN_ID-classes.jar" \
    --output "$WORK/dex" \
    "$WORK/$PLUGIN_ID-classes.jar"
[ -f "$WORK/dex/classes.dex" ] || { echo "FATAL: d8 produced no classes.dex" >&2; exit 1; }

# --- 4. publish ----------------------------------------------------------------------
echo "== [4/4] publish releases/$PLUGIN_ID/$VERSION/plugin.jar"
DEST_DIR="releases/$PLUGIN_ID/$VERSION"
mkdir -p "$DEST_DIR"
rm -f "$DEST_DIR/plugin.jar" "$WORK/plugin.jar"
( cd "$WORK/dex" && zip -q ../plugin.jar classes.dex )
cp "$WORK/plugin.jar" "$DEST_DIR/plugin.jar"

SHA=$(sha256sum "$DEST_DIR/plugin.jar" | cut -d' ' -f1)
echo "ARTIFACT: $DEST_DIR/plugin.jar"
echo "SHA-256 : $SHA"

# --- optional: pin the catalog --------------------------------------------------------
if [ "$UPDATE_CATALOG" = 1 ]; then
    python3 - "$PLUGIN_ID" "$VERSION" "$SHA" <<'PYEOF'
import json, sys
plugin_id, version, sha = sys.argv[1], sys.argv[2], sys.argv[3]
path = "catalog.json"
with open(path, encoding="utf-8") as f:
    catalog = json.load(f)
entry = next((w for w in catalog["widgets"] if w["id"] == plugin_id), None)
if entry is None:
    sys.exit(f"FATAL: catalog.json has no entry with id '{plugin_id}'")
if "plugin" not in entry:
    sys.exit(f"FATAL: catalog entry '{plugin_id}' has no plugin block (add className first)")
entry["version"] = version
entry["plugin"]["dex"] = f"releases/{plugin_id}/{version}/plugin.jar"
entry["plugin"]["sha256"] = sha
with open(path, "w", encoding="utf-8") as f:
    json.dump(catalog, f, indent=2, ensure_ascii=False)
    f.write("\n")
print("CATALOG : updated entry:")
print(json.dumps(entry, indent=2))
PYEOF
fi
