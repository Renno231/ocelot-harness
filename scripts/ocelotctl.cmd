@echo off
call "%~dp0run-harness.cmd" ocelot.harness.app.OcelotCtl %*
exit /b %ERRORLEVEL%
