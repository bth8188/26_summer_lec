@echo off
chcp 65001 >nul
setlocal

rem 프로필 이름을 몰라도, 데모 이름만 알면 실행할 수 있게 만든 래퍼 스크립트 (Windows용).
rem 사용법: run.bat chunking-strategies   (M2.1 청킹 기법 4종 비교, 콘솔)
rem        run.bat mmr                    (M2.3 MMR — 순수 Top-K vs MMR 비교, 콘솔)
rem        run.bat lab21                  (PGVector 인덱싱, 콘솔 — 먼저 docker compose up -d 필요)
rem        run.bat lab22                  (리랭크 데모, 콘솔)
rem        run.bat query-transform        (M2.5 QueryTransformer — 재작성 + Transform/Retrieve/Rerank 파이프라인, 콘솔)
rem        run.bat lab23                  (RAG를 도구로 쓰기 — Agentic RAG, 콘솔)

cd /d "%~dp0"

if "%~1"=="chunking-strategies" call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=%~1 & goto :eof
if "%~1"=="mmr" call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=%~1 & goto :eof
if "%~1"=="lab21" call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=%~1 & goto :eof
if "%~1"=="lab22" call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=%~1 & goto :eof
if "%~1"=="query-transform" call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=%~1 & goto :eof
if "%~1"=="lab23" call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=%~1 & goto :eof

echo 사용법: run.bat ^<chunking-strategies^|mmr^|lab21^|lab22^|query-transform^|lab23^>
exit /b 1
