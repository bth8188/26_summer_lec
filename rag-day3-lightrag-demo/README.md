# rag-day3-lightrag-demo — Day3 M3.2B 라이브 데모

Day3 `M3.2B 오픈소스 RAG 프로젝트 투어` 모듈에서 강사가 실제로 띄워 보여주는 데모.
**LightRAG**(HKUDS, MIT)를 로컬 Ollama로 돌려서, Day1~2에 우리가 직접 만든 벡터 RAG와
그래프 기반 RAG를 **같은 문서·같은 모델로** 비교한다.

## 왜 LightRAG인가
- 설치가 한 줄, DB 없이 파일 스토리지로 동작 → 강의장에서 재현 가능
- LLM/임베딩을 Ollama로 지정 가능 → Day1의 `bge-m3`, 16GB 티어 모델 `qwen2.5:7b` 재사용, API 비용 0원
- `naive`(순수 벡터) / `local` / `global` / `hybrid` 4가지 모드를 파라미터로 전환 → **naive가 곧 우리가 Day1에 만든 것**이라 대비가 즉시 보임

## 실행
```bash
./run.sh install     # uv tool install "lightrag-hku[api]" --with ollama
./run.sh start       # http://localhost:9621 (WebUI: /webui, API 문서: /docs)
./run.sh index       # inputs/ 문서 색인 시작
./run.sh status      # 색인 진행 상황
./run.sh graph       # 추출된 엔티티 목록
./run.sh compare "질문"          # 같은 질문을 4가지 모드로 (핵심 데모)
./run.sh ask hybrid "질문"       # 단일 모드 질의
./run.sh stop / reset
```

## 강의 전 반드시 확인할 것

### 1. `--with ollama` 없이 설치하면 기동 실패
`uv tool install "lightrag-hku[api]"`만 하면 서버 기동 시점에 터진다.
```
ModuleNotFoundError: No module named 'ollama'
Exception: Failed to create LLM for role 'extract': No module named 'ollama'
```
LightRAG가 런타임에 `pip install ollama`를 자체적으로 시도하지만, uv tool 격리 환경에서는 그 설치가 실패한다.
→ `run.sh install`은 `--with ollama`를 붙여둠.

### 2. 엔티티 추출은 작은 모델로 안 된다 (가장 중요)
`llama3.2:3b`(Day1의 8GB 티어 모델)로 색인하면 엔티티/관계 추출 출력 형식을 지키지 못한다.
청크 1개 처리 중 **포맷 오류 23회**, 최종 추출 엔티티 **0개**. 로그는 `logs/llama3.2-3b-extract-failure.log`에 보존.
```
WARNING: ...: LLM output format error; found 3/4 fields on ENTITY `Organization` @ ...
WARNING: ...: LLM output format error; found 24/5 fields on RELATION `MCM-200`~`MCM-350`
```
`qwen2.5:7b`로 바꾸면 포맷 오류 **0회**로 정상 추출된다. → `.env`의 `LLM_MODEL=qwen2.5:7b` 유지.
**교육 포인트**: 그래프 RAG의 실질적 진입 장벽은 그래프 이론이 아니라 추출용 LLM의 지시 준수 능력이다.

### 3. LightRAG는 원본 파일을 옮긴다
색인이 끝나면 `inputs/` 안의 원본이 `inputs/__parsed__/`로 **이동**한다(복사가 아님).
→ 이 데모는 원본을 `source-docs/`에 따로 보관하고 `inputs/`로 복사해서 쓴다.
**교육 포인트**: 슬라이드 D3-81(데이터 잠금)에서 말한 "원본은 항상 플랫폼 밖에 보관"의 실제 사례.

### 4. 색인 시간을 라이브로 기다리지 말 것
문서 7개(약 7KB) 색인에 `qwen2.5:7b` + `max_async=2` 기준으로 수 분~십수 분이 걸린다.
→ **강의 전에 미리 색인을 끝내두고**, 수업에서는 질의 비교만 라이브로 할 것.
(색인 과정을 보여주고 싶으면 `./run.sh status`로 진행 로그만 잠깐 띄우는 정도)

### 5. 청크가 1개면 그래프의 이점이 안 보인다
처음에 문서 1개(3.7KB)로 테스트했을 때 청크가 1개만 생겨서, `naive` 모드도 문서 전체를 컨텍스트에 넣고
정확히 답해버렸다. → 문서를 실제 사내 위키처럼 7개로 쪼개서 흩어진 정보를 종합해야 하는 상황을 만들었다.
**교육 포인트**: 문서가 적을 때는 그래프 RAG가 오버엔지니어링이다. 이것도 그대로 가르칠 내용.

## 문서 구성 (`source-docs/`)
가상 회사 "모두의커피"의 사내 위키. Day1~2 데모의 `MCM-200 사용 설명서`와 같은 세계관이라
"제품 매뉴얼(Day1~2) → 조직·프로젝트 위키(Day3)"로 문서가 확장된 형태로 이어진다.

| 파일 | 내용 | 데모에서의 역할 |
|---|---|---|
| `01-조직도.txt` | 4개 조직, 팀장, 보고 라인 | 인물-조직 관계 |
| `02-제품계보.txt` | MCM-100/200/350/400 계보 | 제품-부품-협력사 관계 |
| `03~05-프로젝트-*.txt` | 아틀라스·오아시스·등대 | **한 사람이 여러 문서에 흩어짐**(정해준이 3개 프로젝트에 참여) |
| `06-협력사.txt` | 세정테크·대림정밀·한빛물류 | 단일 공급처 의존 리스크 |
| `07-이슈로그.txt` | 2026년 3·6·7·8월 이슈 | 시간순 사건과 협력사 연결 |

## 데모 질문 (대비가 잘 드러나는 것)
```bash
# ① 여러 문서에 흩어진 관계 추적 — 정해준은 3개 프로젝트에 각각 다른 역할로 등장
./run.sh compare "정해준이 참여하고 있는 프로젝트를 전부 알려주고, 각각에서 어떤 역할인지 설명해주세요"

# ② 전역·종합형 질문 — 문서 전체를 종합해야 답이 나옴
./run.sh compare "이 회사가 협력사 때문에 겪고 있는 리스크를 전체적으로 종합해주세요"

# ③ 단일 사실 확인 — 이건 naive(우리가 만든 것)로도 충분하다는 것을 보여주는 대조군
./run.sh ask naive "세정테크가 공급하는 부품은 무엇인가요?"
```

## 환경
- `.env` / `.env.example`: LLM `qwen2.5:7b`, 임베딩 `bge-m3`(1024차원, Day1~2와 동일), 스토리지는 파일 기반
- 스토리지를 PGVector/Neo4j로 바꾸려면 `.env`의 `LIGHTRAG_*_STORAGE` 값을 교체 (Day2의 PGVector 컨테이너 재사용 가능)
- 색인 데이터는 `rag_storage/`(git 제외). 지식그래프는 `rag_storage/graph_chunk_entity_relation.graphml`
