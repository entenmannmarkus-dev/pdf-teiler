@echo off
chcp 65001 >nul
rem Splitt-Tool fuer den aktuellen Benutzer einrichten - ohne Adminrechte.
rem Kopiert den Ordner nach %LOCALAPPDATA%\Programs\Splitt-Tool und legt Verknuepfungen
rem im Startmenue und auf dem Desktop an.
set "ZIEL=%LOCALAPPDATA%\Programs\Splitt-Tool"
echo Splitt-Tool wird eingerichtet in:
echo   %ZIEL%
echo.
if /I "%~dp0"=="%ZIEL%\" goto verknuepfung
robocopy "%~dp0." "%ZIEL%" /E /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
  echo Kopieren fehlgeschlagen.
  pause
  exit /b 1
)
:verknuepfung
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$s=New-Object -ComObject WScript.Shell;" ^
  "foreach($p in @([Environment]::GetFolderPath('Programs'),[Environment]::GetFolderPath('Desktop'))){" ^
  "$l=$s.CreateShortcut((Join-Path $p 'Splitt-Tool.lnk'));" ^
  "$l.TargetPath='%ZIEL%\Splitt-Tool.exe';$l.WorkingDirectory='%ZIEL%';$l.Description='PDFs fuer Claude aufteilen';$l.Save()}"
echo.
echo Fertig. Splitt-Tool findest du jetzt im Startmenue und auf dem Desktop.
echo Den heruntergeladenen Ordner kannst du loeschen.
start "" "%ZIEL%\Splitt-Tool.exe"
timeout /t 5 >nul
