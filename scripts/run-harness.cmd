@echo off
setlocal
set "REPOSITORY_ROOT=%~dp0.."
set "SELECTED_JAVA="
for /f "usebackq delims=" %%J in (`powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0java-common.ps1" -JavaSelectionRoot "%REPOSITORY_ROOT%" -JavaSelectionMode runtime`) do set "SELECTED_JAVA=%%J"
if not defined SELECTED_JAVA exit /b 1
set "ASSEMBLY_JAR=%REPOSITORY_ROOT%\lib\ocelot-harness.jar"
if not exist "%ASSEMBLY_JAR%" set "ASSEMBLY_JAR=%REPOSITORY_ROOT%\modules\app\target\ocelot-harness.jar"
if not exist "%ASSEMBLY_JAR%" (
  1>&2 echo ERROR: application package is missing; re-extract the download or build source with scripts\sbtw.cmd "harnessApp/assembly"
  exit /b 1
)
"%SELECTED_JAVA%" -cp "%ASSEMBLY_JAR%" %*
exit /b %ERRORLEVEL%
