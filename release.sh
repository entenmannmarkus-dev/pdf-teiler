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
# Windows: gleiche Versionsnummer, Programmdatei für das Update, Komplettpaket nur wenn nötig
if command -v x86_64-w64-mingw32-gcc >/dev/null 2>&1; then
  # Installationsprogramm und Komplettpaket bei jeder Version neu bauen (Update läuft über Setup.exe im Browser)
  ./windows/build-windows.sh --setup
  mkdir -p dist/windows
  cp build/win/splitt-tool-$VERSION_CODE.jar dist/windows/
  cp build/win/Splitt-Tool-Windows.zip build/win/Splitt-Tool-Setup.exe dist/windows/
  python3 - "$VERSION_CODE" "$VERSION_NAME" "$1" <<'PY'
import json, sys
d = json.load(open("update.json"))
d["windows"] = dict(versionCode=int(sys.argv[1]), versionName=sys.argv[2], notes=sys.argv[3] or d.get("notes", ""),
    jarUrl="https://raw.githubusercontent.com/entenmannmarkus-dev/pdf-teiler/main/dist/windows/splitt-tool-%s.jar" % sys.argv[1],
    zipUrl="https://raw.githubusercontent.com/entenmannmarkus-dev/pdf-teiler/main/dist/windows/Splitt-Tool-Windows.zip",
    setupUrl="https://raw.githubusercontent.com/entenmannmarkus-dev/pdf-teiler/main/dist/windows/Splitt-Tool-Setup.exe")
json.dump(d, open("update.json", "w"), ensure_ascii=False, indent=2)
PY
else
  echo "Hinweis: mingw-w64 fehlt – Windows-Version nicht gebaut (apt-get install gcc-mingw-w64-x86-64 nsis)"
fi
git add -A && git commit -m "Version $VERSION_NAME" ${COMMIT_TRAILER:+-m "$COMMIT_TRAILER"} && git push origin main
