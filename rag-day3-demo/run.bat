@echo off
chcp 65001 >nul
setlocal

rem Day3 캡스톤 챗봇 백엔드 실행 스크립트 (Windows용).
rem 사용법: run.bat   (포트 8081, rag-day3-frontend가 이 포트를 호출함)

cd /d "%~dp0"

if "%OPENAI_API_KEY%"=="" (
    echo [오류] OPENAI_API_KEY 환경변수가 설정되어 있지 않습니다.
    echo   이 터미널에서 먼저 아래처럼 키를 설정한 뒤 run.bat을 다시 실행하세요.
    echo.
    echo   PowerShell:  ^$env:OPENAI_API_KEY = "sk-..."
    echo   cmd:         set OPENAI_API_KEY=sk-...
    echo.
    pause
    exit /b 1
)

call mvnw.cmd spring-boot:run
