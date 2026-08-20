#!/usr/bin/env bash
# 프로필 이름을 몰라도, 데모 이름만 알면 실행할 수 있게 만든 래퍼 스크립트.
# 사용법: ./run.sh live-demo   (Swagger UI, http://localhost:8080/swagger-ui/index.html)
#        ./run.sh lab11 | lab12 | lab13 | lab14   (lab12는 PGVector 컨테이너가 필요함)
#        ./run.sh hallucination-console | compare-console
set -euo pipefail
cd "$(dirname "$0")"

case "${1:-}" in
  live-demo)
    echo "Swagger UI 데모 실행 중... 뜨면 http://localhost:8080/swagger-ui/index.html 접속"
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=api
    ;;
  lab14)
    echo "Lab1.4 PDF 업로드 RAG API 실행 중... 뜨면 http://localhost:8080/swagger-ui/index.html 접속"
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=lab14
    ;;
  lab12)
    echo "Lab1.2는 PGVector에 저장한다 — 먼저 컨테이너를 띄워둘 것 (rag-day2-demo 에서 docker compose up -d)"
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=lab12
    ;;
  lab11|lab13)
    ./mvnw spring-boot:run -Dspring-boot.run.profiles="$1"
    ;;
  hallucination-console)
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=m1-hallucination
    ;;
  compare-console)
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=m1-compare
    ;;
  *)
    echo "사용법: ./run.sh <live-demo|lab11|lab12|lab13|lab14|hallucination-console|compare-console>"
    exit 1
    ;;
esac
