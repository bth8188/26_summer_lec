# 강사 전용 — Day3 M3.2B 라이브 데모 진행 가이드

> **학생은 이 데모를 직접 실행하지 않는다.** 강사 시연 전용이다.
> 이유: ① 엔티티 추출에 7B 이상 모델이 필요해 8GB 노트북에서는 아예 안 됨(아래 3절)
> ② 문서 7개 색인에 십수 분이 걸려 수업 시간에 못 기다림
> ③ ApeRAG는 컨테이너 9개짜리라 강의장 노트북에 깔 수 없음
> 학생에게는 화면으로 보여주고, 슬라이드의 수치를 근거로 설명한다.

---

## 0. 사전 준비 (강의 D-1에 반드시 완료)

```bash
# ① 모델 준비 (16GB 이상 강사 노트북 기준)
ollama pull qwen3:8b        # ApeRAG 시연용 (사용자 지정 모델)
ollama pull qwen2.5:7b      # LightRAG 색인용 (검증 완료 모델)
ollama pull bge-m3          # 임베딩 (Day1~2와 동일)

# ② LightRAG 설치 + 색인 (십수 분 걸림 — 반드시 전날에)
cd rag-day3-lightrag-demo
./run.sh install
./run.sh start
./run.sh index
./run.sh status      # busy가 false가 될 때까지 확인
./run.sh graph       # 엔티티 51개가 나오면 정상

# ③ 강의 당일 아침: 서버만 다시 띄우면 색인은 남아 있다
./run.sh start
```

강의 직전에 지표표 갱신:
```bash
./scripts/fetch_repo_stats.sh    # 슬라이드 D3-55 / D3-56 수치를 이 출력으로 갱신
```

---

## 1. 시연 순서 (약 12분)

| 순서 | 화면 | 대사 요지 | 슬라이드 |
|---|---|---|---|
| 1 | 터미널 `./run.sh start` | "설치 한 줄, 기동 한 줄" — 그리고 `--with ollama` 함정 이야기 | D3-87 |
| 2 | `logs/llama3.2-3b-extract-failure.log` 열기 | **3B 모델로는 그래프가 아예 안 만들어졌다** (포맷 오류 23회, 엔티티 0개) | D3-88 |
| 3 | `./run.sh graph` | qwen2.5:7b로는 엔티티 51개 — 그런데 같은 대상이 갈라진 것을 짚기 | D3-89 |
| 4 | 브라우저 `http://localhost:9621/webui` → 그래프 탭 | 노드 51·엣지 68을 실제로 보여줌. 엣지를 클릭하면 관계 설명이 뜬다 | D3-90 |
| 5 | `./run.sh compare "정해준이 ..."` | **naive가 제일 빠르고 정확했다**는 반전 결과 | D3-91 |
| 6 | ApeRAG 화면 (아래 2절) | 채팅 답변 옆에 참조 그래프가 함께 뜨는 UI | D3-91C |

### 시연 팁
- 5번의 `compare`는 4모드 합계 4~5분이 걸린다. **수업 중에는 미리 저장한 결과(`results/`)를 보여주고**, 라이브로는 `./run.sh ask hybrid "..."` 한 번만 돌리는 게 안전하다.
- WebUI 그래프 화면은 헤드리스 캡처가 안 된다(sigma.js 렌더링). 실제 브라우저로 띄워야 한다.
- 그래프 화면에서 `모두의커피` 노드와 `모두의커피주식회사` 노드를 나란히 보여주면 엔티티 정규화 설명이 한 번에 끝난다.

---

## 2. ApeRAG 시연 (강사 노트북 전용)

ApeRAG는 답변과 함께 참조된 지식그래프를 화면에서 보여준다. 그래프 파트의 하이라이트로 쓴다.
**아래 우회 설정 없이는 Apple Silicon Mac에서 기본 compose가 뜨지 않는다.**

### 2-1. 기동
```bash
git clone --depth 1 https://github.com/apecloud/ApeRAG.git
cd ApeRAG
cp envs/env.template .env
cp <이 폴더>/aperag/docker-compose.override.yml .    # 아래 우회 3건이 들어 있음
docker compose pull                                  # 이미지가 크다. 반드시 전날에
docker compose up -d
# → 프런트엔드 http://localhost:3001 , API 문서 http://localhost:8000/docs , flower http://localhost:5555
```

컨테이너 9개가 뜬다: `api` `frontend` `celeryworker` `celerybeat` `flower` `postgres` `redis` `qdrant` `es`
(Neo4j·DocRay·Jaeger는 옵션 프로필이라 기본 기동에는 없다. 그래프는 PostgreSQL에 저장된다.)

### 2-2. 우회 3건 (override 파일에 이미 반영)

| # | 증상 | 원인 | 우회 |
|---|---|---|---|
| 1 | `Bind for 0.0.0.0:5432 failed: port is already allocated` | Day2 PGVector 컨테이너가 5432 점유 | postgres 호스트 포트를 `5433:5432` 로 (`ports: !override` 필요 — 병합되면 5432가 남는다) |
| 2 | `aperag-es` 가 exit 134 로 무한 재시작, 로그에 `SIGILL ... java.lang.System.registerNatives` | 이 환경이 SVE(ARM 벡터 확장) 길이 조회에 실패하고, Elastic 번들 JDK가 그 상황을 처리하지 못해 즉사. Temurin JDK는 SVE를 자동 비활성화해서 살아남는다 | ES를 8.17.0(JDK 23)으로 올리고 `ES_JAVA_OPTS`·`CLI_JAVA_OPTS` 양쪽에 `-XX:UseSVE=0`. **CLI 쪽을 빼면 IK 플러그인 설치 단계에서 그대로 죽는다** (플러그인 설치는 별도 CLI JVM) |
| 3 | `Ports are not available: ... 0.0.0.0:3000` | Day3 캡스톤 프론트엔드(Next.js)가 3000 점유 | frontend 호스트 포트를 `3001:3000` 으로 |

### 2-3. Ollama 연결 (공식 문서는 UI 클릭 안내 — 아래는 API로 한 번에)
```bash
# ① 관리자 계정 (최초 1회) — 첫 가입자가 자동으로 admin
curl -s -c /tmp/ck.txt -X POST http://localhost:8000/api/v1/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"instructor","email":"instructor@lecture.local","password":"local-demo-only"}'

# ② Ollama 프로바이더 — 컨테이너 안에서 호스트를 보려면 host.docker.internal 이어야 한다
#    (공식 문서에는 localhost:11434 라고 적혀 있어서 그대로 하면 연결 실패)
curl -s -b /tmp/ck.txt -X POST http://localhost:8000/api/v1/llm_providers \
  -H 'Content-Type: application/json' -d '{
    "name":"local-ollama","label":"Local Ollama (강의용)",
    "base_url":"http://host.docker.internal:11434/v1",
    "completion_dialect":"openai","embedding_dialect":"openai","rerank_dialect":"jina",
    "api_key":"ollama-no-auth-needed"}'

# ③ 모델 등록 — Ollama는 OpenAI 호환이라 custom_llm_provider 가 openai
curl -s -b /tmp/ck.txt -X POST http://localhost:8000/api/v1/llm_providers/local-ollama/models \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3:8b","api":"completion","custom_llm_provider":"openai","tags":["agent","collection"]}'
curl -s -b /tmp/ck.txt -X POST http://localhost:8000/api/v1/llm_providers/local-ollama/models \
  -H 'Content-Type: application/json' \
  -d '{"model":"bge-m3:latest","api":"embedding","custom_llm_provider":"openai","tags":["collection"]}'
```

### 2-4. 컬렉션 생성 + 문서 색인
`scripts/setup_aperag.sh` 를 실행하면 위 ①~③과 아래 과정을 한 번에 처리한다.
```bash
./scripts/setup_aperag.sh      # 계정→프로바이더→모델→컬렉션→문서7개 업로드→색인 시작
```
수동으로 할 때 주의할 점만 정리하면:
- `config.language` 는 `ko-KR` 로 (허용값이 `zh-CN`/`en-US`/`ja-JP`/`ko-KR` 4개뿐 — **ApeRAG는 한국어를 정식 지원 언어로 갖고 있다**. 슬라이드 D3-78의 "한국어 처리 확인" 항목의 좋은 사례)
- 인덱스는 `enable_vector` `enable_fulltext` `enable_knowledge_graph` 3개만 켠다 (summary·vision은 시연에 불필요하고 LLM 호출만 늘어남)
- 업로드는 `/documents/upload` 로 올린 뒤 `/documents/confirm` 에 **document_ids 배열을 반드시 넣어야** 색인이 시작된다 (빈 body면 422)

### 2-5. 시연 포인트
- 채팅에서 질문 → 답변과 함께 참조된 그래프가 화면에 표시된다. LightRAG WebUI의 별도 그래프 탭보다 학생에게 직관적이다.
- **엔티티 병합 제안**: ApeRAG는 `/collections/{id}/graphs/merge-suggestions` API와 UI를 제공한다. LightRAG 데모에서 본 "모두의커피 ↔ 모두의커피주식회사" 문제를 플랫폼이 어떻게 다루는지 바로 대비해서 보여줄 수 있다 (슬라이드 D3-68과 직결).
- 시연이 끝나면 `docker compose down` 으로 반드시 내릴 것. 컨테이너 9개가 메모리를 계속 잡고 있고, 다음 세션의 PGVector 데모와 경합한다.

### 2-6. 색인 시간을 반드시 계산에 넣을 것
ApeRAG + `qwen3:8b` 조합에서 **문서 1개(7KB) 엔티티 추출에 약 29분**이 걸렸다
(워커 로그: `Graph Index function duration: extract_entities: 1724.003s`).
문서 7개면 몇 시간이다. 벡터·전문검색 인덱스는 몇 분이면 끝나지만 그래프 인덱스가 병목이다.
→ **강의 이틀 전에 걸어두고 자는 게 안전하다.** 상태 확인:
```bash
curl -s -b /tmp/ck.txt "$API/collections/$CID/documents" \
  | python3 -c "import sys,json,collections;d=json.load(sys.stdin);print(collections.Counter(x.get('graph_index_status') for x in d['items']))"
```

### 2-7. 색인 중에는 ApeRAG 화면 자체가 느리다
16GB 맥북에서 컨테이너 9개 + 호스트의 Ollama 8B가 동시에 돌면, 색인이 진행되는 동안
관리 API 응답이 2분을 넘기기도 한다(`/collections/{id}/graphs` 조회, 문서 목록 조회 모두).
→ **색인은 반드시 전날에 끝내둘 것.** 수업 중에 색인을 걸어놓고 화면을 돌아다니면 아무것도 안 뜬다.

### 2-8. 두 데모를 동시에 돌리지 말 것
LightRAG와 ApeRAG는 **같은 Ollama 인스턴스**를 공유한다. ApeRAG가 그래프 색인을 돌리는 동안
LightRAG에 질의하면 Ollama가 포화돼서 응답이 5분을 넘기고, Spring 브리지가
`HttpTimeoutException: Request cancelled` 로 500을 반환한다.
- 색인은 **전날에 양쪽 다 끝내둘 것**
- 시연은 한 번에 하나만. LightRAG 파트가 끝나면 `./run.sh stop`, ApeRAG 파트가 끝나면 `docker compose down`
- `ollama ps` 로 지금 어떤 모델이 로드돼 있는지 확인하는 습관을 들이면 화면 앞에서 당황하지 않는다

## 3. 슬라이드에 들어간 수치와 그 재현 조건

| 항목 | 값 | 조건 |
|---|---|---|
| llama3.2:3b 포맷 오류 | 23회 / 엔티티 0개 | 문서 1개(3.7KB), 청크 1개 |
| qwen2.5:7b 포맷 오류 | 0회 | 같은 문서 |
| 지식그래프 규모 | 노드 51 / 엣지 68 | 문서 7개(7KB), 청크 7개 |
| 엔티티 타입 오염 | 51개 중 12개 | `UNKNOWN` 또는 타입 칸에 문장이 들어간 것 |
| 질의 소요시간 | naive 36초 / local 73초 / global 83초 / hybrid 75초 | qwen2.5:7b, max_async=2, 16GB Mac |
| 정답률(정해준 3개 프로젝트) | naive 3/3 · local 2/3 · global 3/3 · hybrid 2/3 | 아래 해석 참고 |

### 이 결과를 어떻게 설명할 것인가 (중요)
naive(순수 벡터)가 가장 빠르고 정확했다. 이건 데모 실패가 아니라 **가르쳐야 할 결론**이다.
- 문서가 7개뿐이라 청크 전체가 컨텍스트에 들어간다 → 검색이 사실상 필요 없는 규모
- 그래프 모드는 엔티티/관계를 먼저 훑고 관련 청크를 고르는데, 그 과정에서 오히려 일부를 놓쳤다
- 즉 **그래프 RAG는 규모가 커질 때 의미가 있고, 작은 문서에서는 순손실**이다
- 학생에게 던질 질문: "그럼 우리 캡스톤 문서 몇 개인가요? 그래프가 필요한가요?"

## 4. 시연 중 튀어나올 수 있는 것 두 가지

### 4-1. 답변에 중국어가 섞여 나올 수 있다
`qwen2.5:7b`/`qwen3:8b` 는 중국 팀이 만든 모델이라, `.env` 에 `SUMMARY_LANGUAGE=Korean` 을 줘도
간헐적으로 중국어를 섞는다. 실제로 겪은 응답:
```
세정테크는 ... 2020년부터 시작되었습니다 (参考文献：[1] 06-协力公司.txt)。
具体来说，塞正科技主要为MCM-200及后续型号提供温度传感器和加热模块。
```
파일명까지 중국어로 번역해버린다. 다른 질문에서는 한국어로 정상이라 **산발적**이다.
- 시연 대응: 미리 돌려서 한국어로 잘 나온 질문을 골라두고 그 질문으로 시연할 것
  (`results/` 에 검증해둔 질문·응답이 있다)
- 학생이 지적하면 그대로 가르칠 거리다 — "모델의 출신 언어 편향"은 M3.4 프로덕션 고려사항과 연결된다

### 4-2. 같은 질문을 두 번 하면 즉답이 온다
LightRAG는 LLM 응답 캐시가 기본 활성화(`enable_llm_cache=true`)라, 같은 질문을 다시 던지면
LLM을 호출하지 않고 캐시에서 바로 답한다.
- 시연 팁: 느린 질의는 **미리 한 번 돌려서 캐시를 채워두면** 수업 중에는 즉시 답이 나온다
- 반대로 "모드별 속도 차이"를 보여줄 때는 캐시가 결과를 왜곡한다 — 그때는 질문을 조금씩 바꿔서 물을 것
