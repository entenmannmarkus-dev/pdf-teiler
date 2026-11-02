#!/bin/sh
# Baut das Splitt-Tool für Windows: gleicher Kern (SplitCore) mit Apache PDFBox, Swing-Oberfläche.
# Ergebnis: build/win/splitt-tool-<VERSION_CODE>.jar und (mit --zip) build/win/Splitt-Tool-Windows.zip
# Der Windows-Starter (Splitt-Tool.exe) wird mit mingw-w64 gebaut; die Java-Laufzeit (Temurin JRE) wird mitgeliefert.
set -e
cd "$(dirname "$0")/.."
. ./version.txt
M=https://maven-central.storage-download.googleapis.com/maven2
mkdir -p libs
for j in org/apache/pdfbox/pdfbox/2.0.32/pdfbox-2.0.32.jar org/apache/pdfbox/fontbox/2.0.32/fontbox-2.0.32.jar \
         commons-logging/commons-logging/1.2/commons-logging-1.2.jar org/json/json/20240303/json-20240303.jar; do
  [ -f "libs/$(basename $j)" ] || curl -sSfL -o "libs/$(basename $j)" "$M/$j"
done
W=build/win
rm -rf $W && mkdir -p $W/src/de/pdfteiler/desktop $W/classes
for f in SplitCore ChannelRead; do
  sed 's/com\.tom_roush\.pdfbox/org.apache.pdfbox/g' src/de/pdfteiler/$f.java > $W/src/de/pdfteiler/$f.java
done
cp windows/src/de/pdfteiler/desktop/*.java $W/src/de/pdfteiler/desktop/
cat > $W/src/de/pdfteiler/desktop/Version.java <<J
package de.pdfteiler.desktop;
final class Version { static final int CODE = $VERSION_CODE; static final String NAME = "$VERSION_NAME"; }
J
CP=libs/pdfbox-2.0.32.jar:libs/fontbox-2.0.32.jar:libs/commons-logging-1.2.jar:libs/json-20240303.jar
javac -nowarn --release 17 -encoding UTF-8 -cp $CP -d $W/classes $(find $W/src -name '*.java') 2>&1 | grep -v "^Picked\|^Note" || true
cp windows/icon.png $W/classes/icon.png
(cd $W/classes && for j in pdfbox-2.0.32 fontbox-2.0.32 commons-logging-1.2 json-20240303; do unzip -qo ../../../libs/$j.jar -x 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/MANIFEST.MF' 'module-info.class' 2>/dev/null || true; done)
printf 'Main-Class: de.pdfteiler.desktop.SplittTool\nImplementation-Version: %s\n' "$VERSION_NAME" > $W/manifest.txt
jar cfm $W/splitt-tool-$VERSION_CODE.jar $W/manifest.txt -C $W/classes .
ls -la $W/splitt-tool-$VERSION_CODE.jar

if [ "$1" = "--zip" ]; then
  JRE=${JRE_ZIP:-$HOME/.cache/splitt-tool/jre-windows.zip}
  if [ ! -f "$JRE" ]; then
    mkdir -p "$(dirname "$JRE")"
    curl -sSfL -o "$JRE" "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jre_x64_windows_hotspot_21.0.5_11.zip"
  fi
  (cd windows/launcher && x86_64-w64-mingw32-windres launcher.rc -O coff -o launcher.res \
     && x86_64-w64-mingw32-gcc -O2 -municode -mwindows -s -o Splitt-Tool.exe launcher.c launcher.res)
  P=$W/Splitt-Tool
  mkdir -p $P/app
  cp windows/launcher/Splitt-Tool.exe windows/Einrichten.cmd windows/LIESMICH.txt $P/
  cp $W/splitt-tool-$VERSION_CODE.jar $P/app/
  (cd $W && unzip -q "$JRE" && mv jdk-*-jre Splitt-Tool/runtime)
  (cd $W && rm -f Splitt-Tool-Windows.zip && zip -qr9 Splitt-Tool-Windows.zip Splitt-Tool)
  ls -la $W/Splitt-Tool-Windows.zip
fi
