#!/bin/sh
# Neue Version veröffentlichen: version.txt erhöhen, dann ./release.sh "Was ist neu"
set -e
cd "$(dirname "$0")"
. ./version.txt
./build.sh
# APK liegt versioniert im Repository unter dist/ (GitHub-Releases sind hier nicht nötig)
mkdir -p dist
cp build/Splitt-Tool.apk "dist/Splitt-Tool-$VERSION_NAME.apk"
python3 - "$VERSION_CODE" "$VERSION_NAME" "$1" <<'PY'
import json, sys
d = json.load(open("update.json"))
d.update(versionCode=int(sys.argv[1]), versionName=sys.argv[2], notes=sys.argv[3] or d.get("notes", ""),
         apkUrl="https://raw.githubusercontent.com/entenmannmarkus-dev/pdf-teiler/main/dist/Splitt-Tool-%s.apk" % sys.argv[2])
json.dump(d, open("update.json", "w"), ensure_ascii=False, indent=2)
PY
git add -A && git commit -m "Version $VERSION_NAME" && git push origin main
