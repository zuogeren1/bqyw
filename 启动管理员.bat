@echo off
cd /d "%~dp0"
python elevate.py %*
if errorlevel 1 pause
