@echo off
setlocal
set DIRNAME=%~dp0
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set JAVA_HOME=%JAVA_HOME%
if not defined JAVA_HOME (
    where java >nul 2>nul
    if errorlevel 1 (
        echo ERROR: JAVA_HOME not set and java not found
        exit /b 1
    )
)

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
set JAVA_EXE=java
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

"%JAVA_EXE%" -Xmx2048m -Dfile.encoding=UTF-8 -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
