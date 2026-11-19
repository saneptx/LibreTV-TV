@rem Gradle 启动脚本（Windows）：用 gradle\wrapper 里的 wrapper 自动下载并运行 Gradle
@echo off
setlocal
set DIRNAME=%~dp0
if defined JAVA_HOME (set JAVA_EXE=%JAVA_HOME%\bin\java.exe) else (set JAVA_EXE=java.exe)
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
