@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0sbt-bootstrap.ps1" %*
exit /b %ERRORLEVEL%
