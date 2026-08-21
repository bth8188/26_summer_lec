#!/usr/bin/env bash
# Day3 M3.2B 강사 시연용 — ApeRAG를 로컬 Ollama에 붙이고 위키 문서 7개를 색인한다.
# 전제: ApeRAG가 이미 떠 있고(docker compose up -d), 이 폴더의 aperag/docker-compose.override.yml 을 적용했을 것.
#       Ollama에 qwen3:8b 와 bge-m3 가 있을 것.
set -euo pipefail
cd "$(dirname "$0")/.."

API=${APERAG_API:-http://localhost:8000/api/v1}
CK=${APERAG_COOKIE:-/tmp/aperag-instructor.ck}
USER=${APERAG_USER:-instructor}
# 로컬(localhost) 전용 데모 계정. 외부에 노출되는 서버라면 반드시 APERAG_PASS 로 덮어쓸 것
PASS=${APERAG_PASS:-local-demo-only}
LLM=${APERAG_LLM:-qwen3:8b}
EMB=${APERAG_EMB:-bge-m3:latest}

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1',''))"; }

echo "① 계정 (이미 있으면 로그인으로 넘어감)"
curl -s -c "$CK" -X POST "$API/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"email\":\"$USER@lecture.local\",\"password\":\"$PASS\"}" > /dev/null || true
curl -s -c "$CK" -X POST "$API/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" | head -c 120; echo

echo "② Ollama 프로바이더 (컨테이너에서 호스트를 보려면 host.docker.internal)"
curl -s -b "$CK" -X POST "$API/llm_providers" -H 'Content-Type: application/json' -d '{
  "name":"local-ollama","label":"Local Ollama (강의용)",
  "base_url":"http://host.docker.internal:11434/v1",
  "completion_dialect":"openai","embedding_dialect":"openai","rerank_dialect":"jina",
  "api_key":"ollama-no-auth-needed"}' > /dev/null || true

echo "③ 모델 등록 — Ollama는 OpenAI 호환이므로 custom_llm_provider=openai"
curl -s -b "$CK" -X POST "$API/llm_providers/local-ollama/models" -H 'Content-Type: application/json' \
  -d "{\"model\":\"$LLM\",\"api\":\"completion\",\"custom_llm_provider\":\"openai\",\"tags\":[\"agent\",\"collection\"]}" > /dev/null || true
curl -s -b "$CK" -X POST "$API/llm_providers/local-ollama/models" -H 'Content-Type: application/json' \
  -d "{\"model\":\"$EMB\",\"api\":\"embedding\",\"custom_llm_provider\":\"openai\",\"tags\":[\"collection\"]}" > /dev/null || true

echo "④ 컬렉션 생성 (language는 ko-KR — 허용값 4개 중 하나)"
CID=$(curl -s -b "$CK" -X POST "$API/collections" -H 'Content-Type: application/json' -d "{
  \"title\":\"모두의커피 사내 위키\",
  \"description\":\"Day3 M3.2B 강사 시연용\",
  \"type\":\"document\",
  \"config\":{
    \"source\":\"system\",\"language\":\"ko-KR\",
    \"enable_vector\":true,\"enable_fulltext\":true,\"enable_knowledge_graph\":true,
    \"enable_summary\":false,\"enable_vision\":false,
    \"embedding\":{\"model\":\"$EMB\",\"model_service_provider\":\"local-ollama\",\"custom_llm_provider\":\"openai\"},
    \"completion\":{\"model\":\"$LLM\",\"model_service_provider\":\"local-ollama\",\"custom_llm_provider\":\"openai\"}
  }}" | j id)
echo "   COLLECTION_ID=$CID"

echo "⑤ 문서 업로드 (source-docs/*.txt)"
for f in source-docs/*.txt; do
  curl -s -b "$CK" -X POST "$API/collections/$CID/documents/upload" -F "file=@$f" > /dev/null
  echo "   + $(basename "$f")"
done

echo "⑥ 색인 시작 — confirm 에는 document_ids 배열이 반드시 필요하다 (빈 body면 422)"
IDS=$(curl -s -b "$CK" "$API/collections/$CID/documents/staged" \
  | python3 -c "import sys,json;print(json.dumps([x['document_id'] for x in json.load(sys.stdin)['documents']]))")
curl -s -b "$CK" -X POST "$API/collections/$CID/documents/confirm" -H 'Content-Type: application/json' \
  -d "{\"document_ids\":$IDS}"; echo

echo
echo "완료. 진행 상황:"
echo "  curl -s -b $CK \"$API/collections/$CID/documents\" | python3 -m json.tool | grep status"
echo "화면: http://localhost:3001  (로그인 $USER / $PASS)"
echo "그래프: 컬렉션 → Graph 탭,  병합 제안: $API/collections/$CID/graphs/merge-suggestions"
