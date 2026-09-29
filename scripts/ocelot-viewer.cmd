@echo off
call "%~dp0run-harness.cmd" ocelot.harness.app.OcelotViewer %*
exit /b %ERRORLEVEL%
