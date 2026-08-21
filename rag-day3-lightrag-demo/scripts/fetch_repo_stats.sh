#!/usr/bin/env bash
# Day3 M3.2B 슬라이드용 — 오픈소스 RAG 프로젝트 지표를 GitHub API로 갱신
# 사용법: ./scripts/fetch_repo_stats.sh   (인증 없이 시간당 60회 제한)
set -uo pipefail

REPOS=(
  langgenius/dify langchain-ai/langchain infiniflow/ragflow run-llama/llama_index
  HKUDS/LightRAG microsoft/graphrag onyx-dot-app/onyx topoteretes/cognee
  deepset-ai/haystack Cinnamon/kotaemon HKUDS/RAG-Anything
  langchain4j/langchain4j spring-projects/spring-ai
  SciPhi-AI/R2R weaviate/Verba apecloud/ApeRAG
)

SINCE=$(python3 -c "import datetime;print((datetime.date.today()-datetime.timedelta(days=30)).isoformat())")
printf "%-32s %8s  %-16s %-12s %-22s %s\n" REPO STARS LICENSE LANG "LAST_PUSH" "COMMITS_30D/ARCHIVED"
for r in "${REPOS[@]}"; do
  meta=$(curl -s "https://api.github.com/repos/$r")
  commits=$(curl -s "https://api.github.com/repos/$r/commits?since=$SINCE&per_page=100" \
            | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else "?")
except Exception: print("?")')
  echo "$meta" | python3 -c "
import sys,json
d=json.load(sys.stdin)
lic=(d.get('license') or {}).get('spdx_id') or '-'
print('%-32s %8s  %-16s %-12s %-22s %s' % (
  d.get('full_name','?'), d.get('stargazers_count','?'), lic,
  d.get('language','-'), (d.get('pushed_at') or '')[:19],
  '$commits' + (' ARCHIVED' if d.get('archived') else '')))"
done
