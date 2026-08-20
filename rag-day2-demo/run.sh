#!/usr/bin/env bash
# 프로필 이름을 몰라도, 데모 이름만 알면 실행할 수 있게 만든 래퍼 스크립트.
# 사용법: ./run.sh chunking-strategies   (M2.1 청킹 기법 4종 비교, 콘솔)
#        ./run.sh mmr                   (M2.3 MMR — 순수 Top-K vs MMR 비교, 콘솔)
#        ./run.sh lab21                  (PGVector 인덱싱, 콘솔 — 먼저 docker compose up -d 필요)
#        ./run.sh lab22                  (리랭크 데모, 콘솔)
#        ./run.sh query-transform        (M2.5 QueryTransformer — 재작성 + Transform/Retrieve/Rerank 파이프라인, 콘솔)
#        ./run.sh lab23                  (RAG를 도구로 쓰기 — Agentic RAG, 콘솔)
#        ./run.sh wiki-rag               (제주 Tool + 김치 Advisor, CLI)
#        ./run.sh wiki-rag-api           (제주 Tool + 김치 Advisor, Swagger API)
set -euo pipefail
cd "$(dirname "$0")"

case "${1:-}" in
  wiki-rag)
    ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=wiki-rag -Dspring-boot.run.arguments="--spring.main.web-application-type=none --spring.main.banner-mode=off --logging.level.root=WARN"
    ;;
  wiki-rag-api)
    echo "Wiki RAG API 실행 중... http://localhost:8080/swagger-ui/index.html"
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=wiki-rag-api
    ;;
  chunking-strategies|mmr|lab21|lab22|query-transform|lab23)
    ./mvnw spring-boot:run -Dspring-boot.run.profiles="$1"
    ;;
  *)
    echo "사용법: ./run.sh <chunking-strategies|mmr|lab21|lab22|query-transform|lab23|wiki-rag|wiki-rag-api>"
    exit 1
    ;;
esac
