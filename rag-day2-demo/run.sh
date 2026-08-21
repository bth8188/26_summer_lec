#!/usr/bin/env bash
# 프로필 이름을 몰라도, 데모 이름만 알면 실행할 수 있게 만든 래퍼 스크립트.
# 사용법: ./run.sh chunking-strategies   (M2.1 청킹 기법 4종 비교, 콘솔)
#        ./run.sh lab21                  (PGVector 인덱싱, 콘솔 — 먼저 docker compose up -d 필요)
#        ./run.sh lab22                  (리랭크 데모, 콘솔)
#        ./run.sh lab23                  (RAG를 도구로 쓰기 — Agentic RAG, 콘솔)
#        ./run.sh chatbot                (제주·김치 CLI 챗봇 — 먼저 docker compose up -d 필요)
#        ./run.sh api                    (같은 챗봇을 REST로 — http://localhost:8080/swagger-ui.html)
set -euo pipefail
cd "$(dirname "$0")"

case "${1:-}" in
  chunking-strategies|lab21|lab22|lab23|chatbot|api)
    ./mvnw spring-boot:run -Dspring-boot.run.profiles="$1"
    ;;
  *)
    echo "사용법: ./run.sh <chunking-strategies|lab21|lab22|lab23|chatbot|api>"
    exit 1
    ;;
esac
