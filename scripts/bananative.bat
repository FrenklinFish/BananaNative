@echo off
rem Banana Native's packer on Windows: double-click for the window, or run it in a terminal. See scripts\bananative.ps1 for the commands.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bananative.ps1" %*
set rc=%ERRORLEVEL%
rem Opened by a double-click (no arguments): keep the console open on an error so it can be read
if "%~1"=="" if not %rc%==0 pause
exit /b %rc%
