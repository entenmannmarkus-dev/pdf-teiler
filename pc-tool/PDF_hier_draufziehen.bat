@echo off
REM PDF-Datei(en) mit der Maus auf diese Datei ziehen.
cd /d "%~dp0"
python -m pip install -q -r requirements.txt
:loop
if "%~1"=="" goto ende
python pdf_splitter.py "%~1"
shift
goto loop
:ende
pause
