@echo off
rem Runs the Faultline CLI. Build it first: cd tool ^&^& mvnw.cmd -q package -DskipTests
setlocal
set "JAR=%~dp0tool\faultline-cli\target\faultline.jar"
if not exist "%JAR%" (
  echo faultline.jar not found. Build it first: cd tool ^&^& mvnw.cmd -q package -DskipTests 1>&2
  exit /b 2
)
java -jar "%JAR%" %*
