#!/usr/bin/env bash
# Day3 캡스톤 챗봇 백엔드 실행 스크립트.
# 사용법: ./run.sh   (포트 8081, rag-day3-frontend가 이 포트를 호출함)
set -euo pipefail
cd "$(dirname "$0")"
./mvnw spring-boot:run
