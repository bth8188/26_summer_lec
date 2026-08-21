# RAG 특강 Day3 캡스톤 — 백엔드

`rag-day3-frontend`(RAG 에이전트 콘솔)가 호출하는 Spring AI 백엔드. 문서 인덱싱과 RAG 파이프라인 실행을
"학생이 직접 갈아끼울 수 있는 구조"로 만들어 둔 캡스톤 실습용 프로젝트다.

실습 순서와 티어별 과제는 → **[CAPSTONE.md](CAPSTONE.md)**

## 사전 준비

**Ollama 설치**

macOS:
```bash
brew install ollama
brew services start ollama
```

Windows: [ollama.com/download](https://ollama.com/download)에서 인스톨러 받아서 실행하면 됨 — Docker/WSL 필요 없이 일반 데스크톱 앱처럼 설치되고, 설치 후 자동으로 백그라운드 서비스로 상시 실행됨(따로 켜는 명령 불필요).

**모델 다운로드** (OS 공통)
```bash
ollama pull bge-m3
ollama pull llama3.2:3b
```

**PGVector 실행** (Docker Desktop 필요)
```bash
docker compose up -d
```

Day2 데이터와 섞이지 않도록 Day3는 `localhost:5433/ragday3`를 사용한다. 접속 정보는
`RAG_DB_URL`, `RAG_DB_USERNAME`, `RAG_DB_PASSWORD` 환경 변수로 변경할 수 있다.

## 실행 방법
```bash
./run.sh        # macOS/Linux, 포트 8081
```
```bat
run.bat         :: Windows
```
프론트를 띄우면 상단 상태 표시등에서 Ollama·모델·문서 수를 바로 확인할 수 있다.

## 제출본 재현

이 제출에서 사용한 은행법 관련 원문 5개는 [`source-docs`](source-docs/)에 포함되어 있다.
최초 실행 때 백엔드와 프론트엔드를 실행한 뒤 왼쪽 지식 베이스 패널에서 5개 파일을 모두 업로드한다.
비교 기준은 `TOKEN`, 청크 크기 `400`이고, 제출 파이프라인 시연 권장은
법령 구조를 보존하는 커스텀 `LEGAL`, 청크 크기 `1400자`다. `TOKEN`과 `LEGAL`은 오버랩을 사용하지 않는다.
인덱싱 결과는 PGVector에 저장되므로 이후 백엔드를 재시작해도 문서와 청크가 그대로 복원된다.

## 구조

```
day3/
├─ agent/         프론트와 주고받는 DTO
│   ├─ AgentEvent      스트림 이벤트 한 줄 (step/token/sources/metric/…)
│   ├─ ChatRequest     질문 + 대화기록 + 옵션
│   ├─ RagOptions      topK·임계값·temperature·기능 토글
│   └─ SourceRef       답변 근거 청크 한 개
├─ knowledge/     문서 인덱싱
│   ├─ KnowledgeBase   PGVector 검색 + 재시작 시 청크/문서 목록 복원
│   ├─ IndexingService 읽기 → 청킹 → 임베딩 (진행 상황을 이벤트로 흘려보냄)
│   └─ ChunkingStrategy TOKEN / RECURSIVE / SLIDING / LEGAL(법령 조문 단위 커스텀)
├─ pipeline/      RAG 실행
│   ├─ RagPipeline        인터페이스 — @Component만 붙이면 UI 드롭다운에 자동 등록
│   ├─ AbstractRagPipeline 공통 흐름(검색→컨텍스트→스트리밍) + 확장 훅
│   ├─ BasicRagPipeline   브론즈: 기준선 파이프라인 (그대로 동작)
│   ├─ StudentRagPipeline ★ 실습 파일 — TODO 4개
│   ├─ MemoryAgentPipeline 대화는 검색에만 쓰고 문서 근거로만 답하는 확장 파이프라인
│   └─ RagPrompts         프롬프트/컨텍스트 조립
├─ eval/JudgeService   골드: LLM-as-judge 채점 (Day3 Lab3.1)
└─ web/               컨트롤러 3개
```

## API

응답이 스트림인 엔드포인트는 **NDJSON**(`application/x-ndjson`)이다 — JSON 객체 하나가 한 줄.
Spring MVC에서 `Flux`를 이 미디어 타입으로 리턴하면 원소가 만들어질 때마다 즉시 flush된다(WebFlux 불필요).

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/chat` | 질문 실행 (스트림) — 바디: `{question, pipelineId, history, docIds, options}` |
| POST | `/api/index` | 파일 업로드 + 인덱싱 (스트림) — `multipart/form-data`, 필드명 `files`(복수 가능) + `strategy`, `chunkSize`, `overlap` |
| GET | `/api/knowledge` | 인덱싱된 문서 목록 |
| DELETE | `/api/knowledge/{docId}` | 문서 하나 삭제 |
| DELETE | `/api/knowledge` | 전체 초기화 |
| GET | `/api/knowledge/strategies` | 선택 가능한 청킹 전략 |
| GET | `/api/pipelines` | 선택 가능한 파이프라인 |
| GET | `/api/health` | 백엔드/Ollama/모델/문서 상태 |
| POST | `/api/evaluate` | 답변 채점 (LLM-as-judge) |

### 스트림 이벤트 형식

```jsonc
{"type":"start","pipelineId":"student","pipelineName":"내 파이프라인","tier":"silver"}
{"type":"step","id":"retrieve","label":"벡터 검색","status":"running"}
{"type":"step","id":"retrieve","label":"벡터 검색","status":"done","ms":219,"detail":"3개 청크 · topK=4"}
{"type":"step","id":"rerank","label":"재정렬","status":"todo","hint":"...","file":"StudentRagPipeline#rerank"}
{"type":"sources","sources":[{"index":1,"fileName":"학칙.pdf","page":3,"score":0.62,"text":"..."}]}
{"type":"token","text":"제주도의 "}
{"type":"metric","key":"promptTokens","label":"프롬프트 토큰","value":780}
{"type":"notice","level":"warn","message":"임계값 이상인 청크가 없습니다"}
{"type":"done","ms":4120}
```

`status: "todo"` 이벤트가 프론트에서 "여기를 구현하라"는 점선 카드로 그려진다.
새 이벤트가 필요하면 `AgentEvent.of("myEvent").with("key", 값)` 으로 아무 필드나 붙여 보내면 되고,
프론트는 인스펙터 "로그" 탭에 그대로 보여준다.

### curl로 직접 확인하기

```bash
# 인덱싱
curl -N -X POST http://localhost:8081/api/index \
  -F "files=@문서.pdf" -F "strategy=TOKEN" -F "chunkSize=400"

# 질문 (기본 파이프라인)
curl -N -X POST http://localhost:8081/api/chat -H 'Content-Type: application/json' \
  -d '{"question":"핵심 내용을 요약해줘","pipelineId":"basic","options":{"topK":4}}'

# 내 파이프라인 + 기능 토글
curl -N -X POST http://localhost:8081/api/chat -H 'Content-Type: application/json' \
  -d '{"question":"...","pipelineId":"student","options":{"features":{"rerank":true}}}'
```

## 알아둘 것

- **인덱스는 PGVector에 저장된다.** `docker compose down`은 컨테이너만 내리므로 데이터가 유지된다.
  볼륨까지 지우는 `docker compose down -v`를 실행하면 인덱스도 삭제되므로 주의한다.
- **CORS**는 `application.yml`의 `app.cors.allowed-origin`(기본 `http://localhost:3000`)만 허용한다.
  프론트를 다른 포트로 띄우면 여기도 같이 바꿀 것.
- **스캔 이미지 PDF**는 텍스트 레이어가 없어 0자로 읽힌다(경고 이벤트가 뜬다). OCR은 수업 범위 밖.
- **비교 실행**을 켜면 LLM 요청이 두 배로 나간다. 노트북 사양에 따라 답변이 상당히 느려질 수 있다.
- 포트는 8081 — day1/day2(8080)와 동시에 띄울 수 있다.
