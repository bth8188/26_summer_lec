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
```

```bat
:: Windows (cmd 또는 PowerShell)
run.bat chunking-strategies
run.bat mmr
run.bat lab21
run.bat lab22
run.bat query-transform
run.bat lab23
```
(원한다면 `mvnw spring-boot:run -Dspring-boot.run.profiles=<이름>`으로 직접 실행해도 동일하게 동작함 — `run.sh`/`run.bat`은 그 명령을 대신 기억해주는 것뿐. Windows에서는 `mvnw.cmd`)

## 학생 실습(Lab2.0~2.4)과의 관계
이 프로젝트의 코드는 **강사가 매뉴얼/이용약관/제주도/김치 문서로 미리 시연한 참고 구현**이다. 학생들은 각자 Lab2.0에서 고른 시나리오 문서로 이 코드를 스터디하고 문서 경로만 자기 시나리오로 바꿔 실행한다 — 특히 `lab21m1` 패키지(청킹 기법 4종)와 `lab23`(Agentic RAG)은 `RAG_day2_slides.md`의 챌린지 슬라이드에서 직접 참고하도록 안내되어 있다.

## 코드 구조
```
com.lecture.rag
├── lab21/PgVectorIndexingDemo.java        (@Profile("lab21")) — Day1 SimpleVectorStore → PgVectorStore 교체 시연
├── lab21m1/                                — M2.1 청킹 전략 심화 참고 구현
│   ├── ChunkingStrategyDemo.java          (@Profile("chunking-strategies"))
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
│   └── QueryTransformDemo.java            (@Profile("query-transform"))
└── lab23/                                  — Lab2.4 Agentic RAG 참고 구현
    ├── DocumentSearchTool.java
    └── RagAsToolDemo.java                 (@Profile("lab23"))
```

## 실습 문서
- `src/main/resources/docs/` — Day1부터 이어지는 강사 시연용 매뉴얼(`manual.pdf`)과 arXiv 논문(`agentic-rag-survey.pdf`), `lab21`/`lab22` 데모가 사용
- `src/main/resources/scenarios/` — Lab2.0에서 학생들이 고르는 8개 시나리오 문서(이커머스 매뉴얼, arXiv 논문 2종, 스타트업 이용약관, 위키백과 3종, Spring AI README) — `ChunkingStrategyDemo`가 이 중 일부로 실측 시연

## 주의사항
- `dimensions: 1024`는 반드시 bge-m3 실제 출력 차원과 일치해야 함(Lab1.1에서 확인한 값) — 다르면 `DataIntegrityViolationException`
- PGVector 포트 충돌 시 `docker-compose.yml`의 왼쪽 포트만 바꾸고 `application.yml`의 `datasource.url`도 맞춰서 수정
