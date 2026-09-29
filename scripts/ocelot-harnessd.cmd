@echo off
call "%~dp0run-harness.cmd" ocelot.harness.app.HarnessDaemon %*
exit /b %ERRORLEVEL%
