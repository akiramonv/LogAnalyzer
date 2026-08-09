@echo off
chcp 65001 >nul
setlocal
rem log-analyzer launcher. Examples:
rem   log-analyzer analyze -i app.log
rem   log-analyzer analyze --paste
rem   log-analyzer analyze --clipboard
rem The project is built automatically on first run.
rem NOTE: keep this file ASCII-only - cmd.exe misreads non-ASCII characters.

set "ROOT=%~dp0"
set "JAR=%ROOT%cli\build\libs\log-analyzer.jar"

rem PATH may contain an old Java (e.g. 8), so prefer JAVA_HOME.
set "JAVACMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVACMD=%JAVA_HOME%\bin\java.exe"

if not exist "%JAR%" (
    echo Building log-analyzer... [first run only]
    call "%ROOT%gradlew.bat" -q :cli:fatJar
    if errorlevel 1 (
        echo.
        echo Build failed. Try: gradlew.bat build
        exit /b 1
    )
)

"%JAVACMD%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%" %*
