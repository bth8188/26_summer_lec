#!/usr/bin/env bash
# Day3 M3.2B 라이브 데모 — LightRAG 실행 헬퍼
# 사용법: ./run.sh <명령>
set -euo pipefail
cd "$(dirname "$0")"

BASE=http://localhost:9621
LIGHTRAG=${LIGHTRAG_BIN:-$HOME/.local/bin/lightrag-server}

ask() {  # ask <mode> <question>
  curl -s -X POST "$BASE/query" -H 'Content-Type: application/json' \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"query": sys.argv[2], "mode": sys.argv[1]}))' "$1" "$2")" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["response"])'
}

case "${1:-help}" in
  install)
    # ollama 파이썬 패키지를 --with로 같이 넣어야 함 (안 넣으면 기동 시 ModuleNotFoundError)
    uv tool install "lightrag-hku[api]" --with ollama --force
    # .env는 git에서 제외되므로 예제에서 복사해온다
    [ -f .env ] || cp .env.example .env
    # LightRAG가 원본을 inputs/__parsed__/ 로 옮기므로, 항상 source-docs가 원본이다
    cp -n source-docs/*.txt inputs/ 2>/dev/null || true
    ;;
  start)
    "$LIGHTRAG" > server.log 2>&1 &
    echo "LightRAG 기동 중... 로그: server.log"
    until curl -s "$BASE/health" > /dev/null 2>&1; do sleep 2; done
    echo "준비 완료 → WebUI: $BASE/webui   API 문서: $BASE/docs"
    ;;
  stop)
    pkill -f lightrag-server && echo "중지됨" || echo "실행 중인 서버 없음"
    ;;
  index)
    # inputs/ 안의 문서를 색인. 주의: LightRAG가 원본을 inputs/__parsed__/ 로 옮긴다
    curl -s -X POST "$BASE/documents/scan"; echo
    echo "진행 상황을 보려면: ./run.sh status"
    ;;
  status)
    curl -s "$BASE/documents/pipeline_status" | python3 -m json.tool
    ;;
  graph)
    echo "== 추출된 엔티티 목록 =="
    curl -s "$BASE/graph/label/list" | python3 -c 'import sys,json;l=json.load(sys.stdin);print(len(l),"개");[print(" -",x) for x in l]'
    ;;
  ask)  # ./run.sh ask hybrid "질문"
    ask "${2:-hybrid}" "${3:?질문을 입력하세요}"
    ;;
  compare)  # 같은 질문을 4가지 모드로 — 이 데모의 핵심
    Q="${2:?질문을 입력하세요}"
    for m in naive local global hybrid; do
      echo "==================== mode=$m ===================="
      ask "$m" "$Q"
      echo
    done
    ;;
  reset)
    pkill -f lightrag-server 2>/dev/null || true
    rm -rf rag_storage
    [ -f inputs/__parsed__/company.txt ] && mv inputs/__parsed__/company.txt inputs/ || true
    rmdir inputs/__parsed__ 2>/dev/null || true
    echo "색인 초기화 완료"
    ;;
  *)
    cat <<'USAGE'
사용법: ./run.sh <명령>
  install   LightRAG 설치 (ollama 파이썬 패키지 포함)
  start     서버 기동 (http://localhost:9621)
  stop      서버 중지
  index     inputs/ 문서 색인 시작
  status    색인 진행 상황
  graph     추출된 엔티티 목록
  ask <mode> "<질문>"   단일 질의 (mode: naive|local|global|hybrid)
  compare "<질문>"      같은 질문을 4가지 모드로 비교 (핵심 데모)
  reset     색인 삭제하고 원본 복구
USAGE
    ;;
esac
