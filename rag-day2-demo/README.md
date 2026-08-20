# RAG 특강 Day2 시연 코드

`RAG_3day_curriculum.md`의 Day2 M2.1/Lab2.1/Lab2.2/Lab2.3 시연을 실제로 동작하는 Spring Boot 프로젝트로 구현한 것.

## 사전 준비
```bash
brew install ollama
brew services start ollama
ollama pull bge-m3          # 임베딩 (사양 무관 공통)
ollama pull llama3.2:3b     # LLM (8GB 노트북 기준. 16GB+면 qwen2.5:7b 등으로 교체)

docker compose up -d        # PGVector (Lab2.1부터 필요)
```

## 실행 방법
프로필 이름을 몰라도 되게 `run.sh`(macOS/Linux) / `run.bat`(Windows) 래퍼 스크립트로 실행한다.

```bash
# macOS / Linux
./run.sh chunking-strategies   # M2.1 청킹 기법 4종(Fixed/Recursive/구조기반/Sliding Window/Semantic) 비교
./run.sh mmr                   # M2.3 — MMR: 순수 Top-K vs MMR(다양성 확보) 비교
./run.sh lab21                 # Lab2.1 — PGVector 인덱싱 (docker compose up -d 먼저 실행할 것)
./run.sh lab22                 # Lab2.2/M2.4 — LLM 기반 리랭크 데모
./run.sh query-transform       # M2.5 — QueryTransformer: 질문 재작성 단독 + Transform/Retrieve/Rerank 파이프라인 비교
./run.sh lab23                 # Lab2.4 — RAG를 도구로 쓰기(Agentic RAG) vs 프롬프트 바인딩형 비교
./run.sh lab24                 # 콘솔 RAG 챗봇 — Advisor(김치) + Tool(제주) 조합, 대화형
```

```bat
:: Windows (cmd 또는 PowerShell)
run.bat chunking-strategies
run.bat mmr
run.bat lab21
run.bat lab22
run.bat query-transform
run.bat lab23
run.bat lab24
```
(원한다면 `mvnw spring-boot:run -Dspring-boot.run.profiles=<이름>`으로 직접 실행해도 동일하게 동작함 — `run.sh`/`run.bat`은 그 명령을 대신 기억해주는 것뿐. Windows에서는 `mvnw.cmd`)

## 학생 실습(Lab2.0~2.4)과의 관계
이 프로젝트의 코드는 **강사가 매뉴얼/이용약관/제주도/김치 문서로 미리 시연한 참고 구현**이다. 학생들은 각자 Lab2.0에서 고른 시나리오 문서로 이 코드를 스터디하고 문서 경로만 자기 시나리오로 바꿔 실행한다 — 특히 `lab21m1` 패키지(청킹 기법 5종)와 `lab23`(Agentic RAG)은 `RAG_day2_slides.md`의 챌린지 슬라이드에서 직접 참고하도록 안내되어 있다.

## 코드 구조
```
com.lecture.rag
├── lab21/PgVectorIndexingDemo.java        (@Profile("lab21")) — Day1 SimpleVectorStore → PgVectorStore 교체 시연
├── lab21m1/                                — M2.1 청킹 전략 심화 참고 구현
│   ├── ChunkingStrategyDemo.java          (@Profile("chunking-strategies"))
│   ├── MarkdownSectionSplitter.java       — 헤더 계층("H1 > H2")을 청크에 붙이는 마크다운 전용 구조 청킹
│   ├── RecursiveCharacterSplitter.java
│   ├── SemanticChunker.java
│   ├── SlidingWindowSplitter.java
│   └── StructureBasedSplitter.java
├── m23mmr/                                 — M2.3 MMR(Maximal Marginal Relevance) 참고 구현
│   └── MmrSearchDemo.java                 (@Profile("mmr"))
├── lab22/                                  — M2.4 Re-ranking 참고 구현
│   ├── LlmReranker.java
│   └── RerankDemo.java                    (@Profile("lab22"))
├── m25query/                               — M2.5 Query Transformation 참고 구현 (lab22의 LlmReranker 재사용)
│   └── QueryTransformDemo.java            (@Profile("query-transform"))             (@Profile("lab23"))
├── lab23/                                  — Lab2.4 Agentic RAG 참고 구현
│   ├── DocumentSearchTool.java
│   └── RagAsToolDemo.java                 (@Profile("lab23"))
└── lab24/                                  — 앞의 랩들을 합친 콘솔 챗봇
    ├── JejuSearchTool.java                (제주 문서 전용 @Tool)
    └── ConsoleChatbotDemo.java            (@Profile("lab24"))
```

## lab24 — 콘솔 챗봇 (Advisor + Tool 조합)

같은 챗봇 안에서 두 문서를 서로 다른 방식으로 붙인 참고 구현이다. 김치 문서는 `QuestionAnswerAdvisor`로 **항상** 검색되고, 제주 문서는 `JejuSearchTool`로 **모델이 필요하다고 판단할 때만** 검색된다. lab23에서 (A)/(B)로 나눠 비교했던 두 방식을 한 대화에 같이 넣은 것.

두 문서가 같은 테이블에 들어가므로, 인덱싱할 때 `metadata`에 `source`를 박고 검색할 때 `filterExpression`으로 가른다. 이 태그가 역할 분담을 지탱하는 유일한 장치다.

```java
// 인덱싱
new Document(chunk.getText(), Map.of("source", "jeju"))

// Advisor는 김치만
QuestionAnswerAdvisor.builder(vectorStore)
        .searchRequest(SearchRequest.builder().filterExpression("source == 'kimchi'")...)
```

### 검색 게이트를 왜 코드로 넣었나
"모르면 모른다고 답하라"는 지시를 프롬프트에만 맡기면 **`llama3.2:3b`는 이를 무시하고 사전학습 지식으로 답을 지어낸다.** 실측(2026-08-20)에서 "아이폰 최신 모델이 뭐야?"에 `iPhone 14 시리즈입니다`라고 답했다. 그래서 LLM에 묻기 전에 유사도 임계값으로 근거 유무를 먼저 확인하고, 없으면 아예 호출하지 않고 거절한다.

`similarityThreshold`를 안 걸면 기본값이 0이라 **어떤 질문에도 topK가 꽉 채워져 돌아온다**는 점이 핵심이다. 임계값이 있어야 "검색 결과 없음"이라는 상태가 비로소 존재한다. 현재 값 0.55는 실측으로 고른 것이고, 문서를 바꾸면 다시 재봐야 한다.

### 실행과 검증
```bash
docker compose up -d
./run.sh lab24        # 종료하려면 빈 줄 입력
```

| 질문 | 실측 결과 |
|---|---|
| 제주도 면적이 얼마야? | 근거 `[jeju]`, 도구 호출됨 → 1,846km² |
| 김치는 언제부터 먹었어? | 근거 `[kimchi]`, Advisor 컨텍스트로 답변 |
| 아이폰 최신 모델이 뭐야? | 게이트 차단 → "제가 가진 자료로는 답변드릴 수 없습니다." |

### 알려진 한계
`llama3.2:3b`는 김치 질문에도 `searchJeju` 도구를 같이 호출한다. 도구가 제주 필터로 빈손을 돌려주고 Advisor 컨텍스트로 답이 나오므로 결과는 맞지만, "모델이 도구를 정확히 골라 쓴다"는 이상적인 동작은 아니다. 소형 모델의 tool calling 한계로, lab23에서 다루는 주제와 이어진다.


## 실습 문서
- `src/main/resources/docs/` — Day1부터 이어지는 강사 시연용 매뉴얼(`manual.pdf`)과 arXiv 논문(`agentic-rag-survey.pdf`), `lab21`/`lab22` 데모가 사용
- `src/main/resources/scenarios/` — Lab2.0에서 학생들이 고르는 8개 시나리오 문서(이커머스 매뉴얼, arXiv 논문 2종, 스타트업 이용약관, 위키백과 3종, Spring AI README) — `ChunkingStrategyDemo`가 이 중 일부로 실측 시연

## 주의사항
- `dimensions: 1024`는 반드시 bge-m3 실제 출력 차원과 일치해야 함(Lab1.1에서 확인한 값) — 다르면 `DataIntegrityViolationException`
- PGVector 포트 충돌 시 `docker-compose.yml`의 왼쪽 포트만 바꾸고 `application.yml`의 `datasource.url`도 맞춰서 수정
