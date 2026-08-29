@echo off
setlocal
set "REPOSITORY_ROOT=%~dp0.."
set "ASSEMBLY_JAR=%REPOSITORY_ROOT%\modules\app\target\ocelot-harness.jar"
if not exist "%ASSEMBLY_JAR%" (
  1>&2 echo ERROR: viewer package is missing; run scripts\sbtw.cmd "harnessApp/assembly" first
  exit /b 1
)
java -cp "%ASSEMBLY_JAR%" ocelot.harness.app.OcelotViewer %*
exit /b %ERRORLEVEL%
