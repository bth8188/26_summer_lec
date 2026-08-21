@echo off
chcp 65001 >nul
setlocal

rem Day3 캡스톤 챗봇 백엔드 실행 스크립트 (Windows용).
rem 사용법: run.bat   (포트 8081, rag-day3-frontend가 이 포트를 호출함)

cd /d "%~dp0"
call mvnw.cmd spring-boot:run
