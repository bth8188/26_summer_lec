#!/usr/bin/env bash
# 프로필 이름을 몰라도, 데모 이름만 알면 실행할 수 있게 만든 래퍼 스크립트.
# 사용법: ./run.sh chunking-strategies   (M2.1 청킹 기법 4종 비교, 콘솔)
#        ./run.sh mmr                   (M2.3 MMR — 순수 Top-K vs MMR 비교, 콘솔)
#        ./run.sh lab21                  (PGVector 인덱싱, 콘솔 — 먼저 docker compose up -d 필요)
#        ./run.sh lab22                  (리랭크 데모, 콘솔)
#        ./run.sh query-transform        (M2.5 QueryTransformer — 재작성 + Transform/Retrieve/Rerank 파이프라인, 콘솔)
#        ./run.sh lab23                  (RAG를 도구로 쓰기 — Agentic RAG, 콘솔)
#        ./run.sh lab24                  (콘솔 RAG 챗봇 — Advisor+Tool 조합, 대화형)
#        ./run.sh lab24-api              (같은 챗봇의 Swagger 버전, 브라우저)
#        ./run.sh lab25                  (사서 챗봇 — 관계 테이블 SQL + 벡터 검색 도구 2개, 대화형)
set -euo pipefail
cd "$(dirname "$0")"

case "${1:-}" in
  lab24-api)
    echo "Lab2.4 챗봇 Swagger 버전 실행 중... 뜨면 http://localhost:8080/swagger-ui/index.html 접속"
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=lab24-api
    ;;
  chunking-strategies|mmr|lab21|lab22|query-transform|lab23|lab24|lab25)
    ./mvnw spring-boot:run -Dspring-boot.run.profiles="$1"
    ;;
  *)
    echo "사용법: ./run.sh <chunking-strategies|mmr|lab21|lab22|query-transform|lab23|lab24|lab24-api|lab25>"
    exit 1
    ;;
esac
