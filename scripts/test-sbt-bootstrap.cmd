@echo off
setlocal EnableExtensions
set "ROOT=%~dp0.."
set "TEST_DIR=%TEMP%\ocelot-harness-bootstrap-test-%RANDOM%-%RANDOM%"
set "SBT_BOOTSTRAP_CACHE=%TEST_DIR%\cache"
mkdir "%SBT_BOOTSTRAP_CACHE%" >nul 2>&1
>"%SBT_BOOTSTRAP_CACHE%\sbt-launch-1.8.3.jar" echo not the pinned launcher

call "%ROOT%\scripts\sbtw.cmd" --version >"%TEST_DIR%\output.txt" 2>&1
set "STATUS=%ERRORLEVEL%"
if "%STATUS%"=="0" (
  echo FAIL: bootstrap accepted a launcher with the wrong checksum 1>&2
  rmdir /s /q "%TEST_DIR%"
  exit /b 1
)

findstr /C:"checksum mismatch" "%TEST_DIR%\output.txt" >nul
if errorlevel 1 (
  echo FAIL: bootstrap did not report the checksum mismatch 1>&2
  type "%TEST_DIR%\output.txt" 1>&2
  rmdir /s /q "%TEST_DIR%"
  exit /b 1
)

echo PASS: Windows bootstrap rejects a launcher with the wrong checksum

mkdir "%TEST_DIR%\fake-bin" >nul 2>&1
>"%TEST_DIR%\fake-bin\java.cmd" echo @echo off
>>"%TEST_DIR%\fake-bin\java.cmd" echo echo openjdk version "17.0.10" 1^>^&2
>>"%TEST_DIR%\fake-bin\java.cmd" echo exit /b 0
set "ORIGINAL_PATH=%PATH%"
set "PATH=%TEST_DIR%\fake-bin;%PATH%"
call "%ROOT%\scripts\sbtw.cmd" --version >"%TEST_DIR%\output.txt" 2>&1
set "STATUS=%ERRORLEVEL%"
set "PATH=%ORIGINAL_PATH%"
if "%STATUS%"=="0" (
  echo FAIL: bootstrap accepted a non-Java-8 runtime 1>&2
  rmdir /s /q "%TEST_DIR%"
  exit /b 1
)

findstr /C:"Java 8 is required" "%TEST_DIR%\output.txt" >nul
if errorlevel 1 (
  echo FAIL: bootstrap did not report the Java 8 requirement 1>&2
  type "%TEST_DIR%\output.txt" 1>&2
  rmdir /s /q "%TEST_DIR%"
  exit /b 1
)

echo PASS: Windows bootstrap rejects a non-Java-8 runtime
rmdir /s /q "%TEST_DIR%"
exit /b 0
