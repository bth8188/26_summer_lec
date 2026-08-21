@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem Day3 M3.2B 라이브 데모 — LightRAG 실행 헬퍼 (Windows용)
rem 사용법: run.bat install ^| start ^| stop ^| index ^| status ^| graph
rem        run.bat ask <mode> "<질문>"      (mode: naive^|local^|global^|hybrid)
rem        run.bat compare "<질문>"

cd /d "%~dp0"
set BASE=http://localhost:9621

if "%~1"=="install" (
    rem ollama 파이썬 패키지를 --with로 같이 넣어야 함 (안 넣으면 기동 시 ModuleNotFoundError)
    uv tool install "lightrag-hku[api]" --with ollama --force
    goto :eof
)

if "%~1"=="start" (
    start "LightRAG" cmd /c "lightrag-server > server.log 2>&1"
    echo LightRAG 기동 중... 잠시 후 %BASE%/webui 접속
    goto :eof
)

if "%~1"=="stop" (
    taskkill /FI "WINDOWTITLE eq LightRAG*" /F >nul 2>&1
    echo 중지됨
    goto :eof
)

if "%~1"=="index" (
    curl -s -X POST %BASE%/documents/scan
    echo.
    echo 진행 상황: run.bat status
    goto :eof
)

if "%~1"=="status" (
    curl -s %BASE%/documents/pipeline_status
    goto :eof
)

if "%~1"=="graph" (
    curl -s %BASE%/graph/label/list
    goto :eof
)

if "%~1"=="ask" (
    python -c "import json,sys,urllib.request;req=urllib.request.Request('%BASE%/query',data=json.dumps({'query':sys.argv[2],'mode':sys.argv[1]}).encode(),headers={'Content-Type':'application/json'});print(json.load(urllib.request.urlopen(req,timeout=600))['response'])" %~2 %~3
    goto :eof
)

if "%~1"=="compare" (
    for %%m in (naive local global hybrid) do (
        echo ==================== mode=%%m ====================
        python -c "import json,sys,urllib.request;req=urllib.request.Request('%BASE%/query',data=json.dumps({'query':sys.argv[2],'mode':sys.argv[1]}).encode(),headers={'Content-Type':'application/json'});print(json.load(urllib.request.urlopen(req,timeout=600))['response'])" %%m %~2
        echo.
    )
    goto :eof
)

echo 사용법: run.bat install ^| start ^| stop ^| index ^| status ^| graph
echo        run.bat ask ^<mode^> "^<질문^>"
echo        run.bat compare "^<질문^>"
