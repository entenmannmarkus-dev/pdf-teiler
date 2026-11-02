@echo off
cd /d "%~dp0"
python -m pip install -q -r requirements.txt
start "" pythonw pdf_splitter_gui.py
