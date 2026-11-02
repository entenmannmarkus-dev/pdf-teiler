#!/bin/sh
# Neue Version veröffentlichen: version.txt erhöhen, dann ./release.sh "Was ist neu"
set -e
cd "$(dirname "$0")"
. ./version.txt
./build.sh
# erst die APK veröffentlichen, danach update.json – sonst lädt die App die alte APK
git add -A && git commit -m "Version $VERSION_NAME" || true
git push origin main
gh release create "v$VERSION_NAME" build/PDF-Teiler-fuer-Claude.apk --title "Version $VERSION_NAME" --notes "$1"
python3 - "$VERSION_CODE" "$VERSION_NAME" "$1" <<'PY'
import json, sys
d = json.load(open("update.json"))
d.update(versionCode=int(sys.argv[1]), versionName=sys.argv[2], notes=sys.argv[3] or d.get("notes", ""))
json.dump(d, open("update.json", "w"), ensure_ascii=False, indent=2)
PY
git add update.json && git commit -m "update.json: Version $VERSION_NAME" && git push origin main
