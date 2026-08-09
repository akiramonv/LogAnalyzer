@echo off
chcp 65001 >nul
setlocal
rem ---------------------------------------------------------------
rem  Single entry point: double-click this file to run the project.
rem  It builds the project if needed and opens the web interface.
rem  NOTE: keep this file ASCII-only - cmd.exe misreads other bytes.
rem ---------------------------------------------------------------

cd /d "%~dp0"
set "JAR=cli\build\libs\log-analyzer.jar"

rem PATH may contain an old Java (e.g. 8) that cannot run the jar - prefer JAVA_HOME.
set "JAVACMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVACMD=%JAVA_HOME%\bin\java.exe"

"%JAVACMD%" -version >nul 2>&1
if errorlevel 1 (
    echo [!] Java not found.
    echo     Install JDK 21+ or set JAVA_HOME, then run this file again.
    echo.
    pause
    exit /b 1
)

if not exist "%JAR%" (
    echo Building the project, this takes about a minute...
    call gradlew.bat -q :cli:fatJar
    if errorlevel 1 (
        echo.
        echo [!] Build failed. Run "gradlew.bat build" to see the details.
        echo.
        pause
        exit /b 1
    )
    echo Build finished.
    echo.
)

echo Starting the log analyzer interface...
echo A browser tab will open at http://localhost:8321
echo Press Ctrl+C in this window to stop.
echo.

"%JAVACMD%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%" ui %*

if errorlevel 1 (
    echo.
    echo [!] The interface stopped with an error.
    pause
)
