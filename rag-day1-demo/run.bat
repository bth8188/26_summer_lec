@echo off
chcp 65001 >nul
setlocal

rem 프로필 이름을 몰라도, 데모 이름만 알면 실행할 수 있게 만든 래퍼 스크립트 (Windows용).
rem 사용법: run.bat live-demo   (Swagger UI, http://localhost:8080/swagger-ui/index.html)
rem        run.bat lab11 ^| lab12 ^| lab13 ^| lab14
rem        run.bat hallucination-console ^| compare-console

cd /d "%~dp0"

if "%~1"=="live-demo" (
    echo Swagger UI 데모 실행 중... 뜨면 http://localhost:8080/swagger-ui/index.html 접속
    call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=api
    goto :eof
)

if "%~1"=="lab14" (
    echo Lab1.4 PDF 업로드 RAG API 실행 중... 뜨면 http://localhost:8080/swagger-ui/index.html 접속
    call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=lab14
    goto :eof
)

if "%~1"=="lab11" call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=%~1 & goto :eof
if "%~1"=="lab12" call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=%~1 & goto :eof
if "%~1"=="lab13" call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=%~1 & goto :eof

if "%~1"=="hallucination-console" (
    call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=m1-hallucination
    goto :eof
)

if "%~1"=="compare-console" (
    call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=m1-compare
    goto :eof
)

echo 사용법: run.bat ^<live-demo^|lab11^|lab12^|lab13^|hallucination-console^|compare-console^>
exit /b 1
