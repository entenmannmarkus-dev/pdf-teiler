#!/bin/sh
# Baut die APK ohne Gradle: aapt -> javac -> dx -> zipalign -> apksigner
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
[ -f "$SDK" ] || SDK=$(ls /usr/lib/android-sdk/platforms/*/android.jar | head -1)
. ./version.txt
# pdfbox-android (Apache-Lizenz) bei Bedarf herunterladen
if [ ! -f libs/aar/classes.jar ]; then
  mkdir -p libs/aar
  curl -sSL -o libs/pdfbox-android.aar https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/2.0.27.0/pdfbox-android-2.0.27.0.aar
  (cd libs/aar && unzip -qo ../pdfbox-android.aar)
fi
KEYSTORE=${KEYSTORE:-keystore.jks}
rm -rf build && mkdir -p build/gen build/classes build/apk
sed -e "s/@VC@/$VERSION_CODE/" -e "s/@VN@/$VERSION_NAME/" AndroidManifest.xml > build/AndroidManifest.xml
aapt package -f -m -J build/gen -M build/AndroidManifest.xml -S res -A libs/aar/assets -I "$SDK" \
     --min-sdk-version 24 --target-sdk-version 34 --version-code $VERSION_CODE --version-name $VERSION_NAME \
     -F build/apk/app.unsigned.apk
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK" -cp libs/aar/classes.jar \
      -d build/classes build/gen/de/pdfteiler/R.java src/de/pdfteiler/*.java 2>&1 | grep -v "^Picked\|^warning\|^Note\|^1 warning" || true
dalvik-exchange --dex --min-sdk-version=24 --output=build/apk/classes.dex build/classes libs/aar/classes.jar
(cd build/apk && zip -q app.unsigned.apk classes.dex)
zipalign -f -p 4 build/apk/app.unsigned.apk build/apk/app.aligned.apk
[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -storepass pdfteiler -keypass pdfteiler \
     -alias pdfteiler -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PDF-Teiler, O=privat, C=DE" >/dev/null 2>&1
apksigner sign --ks "$KEYSTORE" --ks-pass pass:pdfteiler --key-pass pass:pdfteiler --ks-key-alias pdfteiler \
     --min-sdk-version 24 --out build/PDF-Teiler-fuer-Claude.apk build/apk/app.aligned.apk
apksigner verify --print-certs build/PDF-Teiler-fuer-Claude.apk | head -2
ls -la build/PDF-Teiler-fuer-Claude.apk
