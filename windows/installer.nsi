; Splitt-Tool – Installationsprogramm für Windows (NSIS, Unicode)
; Installiert nur für den aktuellen Benutzer, ohne Adminrechte, nach %LOCALAPPDATA%\Programs\Splitt-Tool.
; Gebaut von build-windows.sh --setup:  makensis -DVERSION=2.6 -DSRC=<Ordner Splitt-Tool> -DOUT=<Datei> installer.nsi
Unicode true
SetCompressor /SOLID lzma
RequestExecutionLevel user
!include "MUI2.nsh"
!include "LogicLib.nsh"

!define APPNAME "Splitt-Tool"
!define UNINST "Software\Microsoft\Windows\CurrentVersion\Uninstall\Splitt-Tool"
!define CTX "Software\Classes\SystemFileAssociations\.pdf\shell\SplittTool"

Name "${APPNAME} ${VERSION}"
OutFile "${OUT}"
InstallDir "$LOCALAPPDATA\Programs\Splitt-Tool"
InstallDirRegKey HKCU "${UNINST}" "InstallLocation"
BrandingText "Splitt-Tool ${VERSION} – PDFs für Claude aufteilen"
VIProductVersion "${VERSION}.0.0"
VIAddVersionKey "ProductName" "Splitt-Tool"
VIAddVersionKey "FileDescription" "Splitt-Tool Installation"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "github.com/entenmannmarkus-dev/pdf-teiler"

!define MUI_ICON "launcher\splitt-tool.ico"
!define MUI_UNICON "launcher\splitt-tool.ico"
!define MUI_WELCOMEPAGE_TITLE "Splitt-Tool ${VERSION} installieren"
!define MUI_WELCOMEPAGE_TEXT "Das Splitt-Tool teilt große PDFs (bis 1000 MB) kapitelweise in Teile, die Claude annimmt.$\r$\n$\r$\nInhalte werden nie verändert: Seiten werden 1:1 kopiert und bitgenau geprüft.$\r$\n$\r$\nEs werden keine Adminrechte gebraucht. Java ist bereits enthalten.$\r$\n$\r$\nIst eine ältere Version installiert, wird sie ersetzt. Deine Einstellungen bleiben erhalten."
!define MUI_FINISHPAGE_RUN "$INSTDIR\Splitt-Tool.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Splitt-Tool jetzt starten"
!define MUI_ABORTWARNING

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "German"

Section "Splitt-Tool" SecMain
  SetShellVarContext current
  ; Alte Programmdateien entfernen. Schlägt das fehl, läuft das Splitt-Tool noch.
  retry:
  ClearErrors
  Delete "$INSTDIR\app\splitt-tool-*.jar"
  ${If} ${Errors}
    MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "Das Splitt-Tool ist noch geöffnet.$\r$\nBitte schließen und dann „Wiederholen“ klicken." IDRETRY retry
    Abort "Abgebrochen: Splitt-Tool war noch geöffnet."
  ${EndIf}
  RMDir /r "$INSTDIR\runtime"

  SetOutPath "$INSTDIR"
  File /r "${SRC}\*.*"
  Delete "$INSTDIR\Einrichten.cmd"
  WriteUninstaller "$INSTDIR\Deinstallieren.exe"

  CreateShortcut "$SMPROGRAMS\Splitt-Tool.lnk" "$INSTDIR\Splitt-Tool.exe" "" "$INSTDIR\Splitt-Tool.exe" 0 SW_SHOWNORMAL "" "PDFs für Claude aufteilen"
  CreateShortcut "$DESKTOP\Splitt-Tool.lnk" "$INSTDIR\Splitt-Tool.exe" "" "$INSTDIR\Splitt-Tool.exe" 0 SW_SHOWNORMAL "" "PDFs für Claude aufteilen"

  ; Rechtsklick auf eine PDF: „Mit Splitt-Tool aufteilen“ (ändert nicht das Standardprogramm für PDFs)
  WriteRegStr HKCU "${CTX}" "" "Mit Splitt-Tool aufteilen"
  WriteRegStr HKCU "${CTX}" "Icon" "$INSTDIR\Splitt-Tool.exe"
  WriteRegStr HKCU "${CTX}\command" "" '"$INSTDIR\Splitt-Tool.exe" "%1"'

  ; Eintrag unter Einstellungen → Apps (Deinstallieren)
  WriteRegStr HKCU "${UNINST}" "DisplayName" "Splitt-Tool"
  WriteRegStr HKCU "${UNINST}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST}" "Publisher" "Splitt-Tool (pdf-teiler)"
  WriteRegStr HKCU "${UNINST}" "DisplayIcon" "$INSTDIR\Splitt-Tool.exe"
  WriteRegStr HKCU "${UNINST}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST}" "URLInfoAbout" "https://github.com/entenmannmarkus-dev/pdf-teiler"
  WriteRegStr HKCU "${UNINST}" "UninstallString" '"$INSTDIR\Deinstallieren.exe"'
  WriteRegStr HKCU "${UNINST}" "QuietUninstallString" '"$INSTDIR\Deinstallieren.exe" /S'
  WriteRegDWORD HKCU "${UNINST}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST}" "NoRepair" 1
  WriteRegDWORD HKCU "${UNINST}" "EstimatedSize" 160000
SectionEnd

Section "Uninstall"
  SetShellVarContext current
  Delete "$SMPROGRAMS\Splitt-Tool.lnk"
  Delete "$DESKTOP\Splitt-Tool.lnk"
  DeleteRegKey HKCU "${CTX}"
  DeleteRegKey HKCU "${UNINST}"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\Splitt-Tool.exe"
  Delete "$INSTDIR\LIESMICH.txt"
  Delete "$INSTDIR\Deinstallieren.exe"
  RMDir "$INSTDIR"
SectionEnd
