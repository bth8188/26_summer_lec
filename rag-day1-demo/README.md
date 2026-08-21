# RAG 특강 Day1 시연 코드

`RAG_3day_curriculum.md`의 Day1 Lab/M1.1 라이브 데모를 실제로 동작하는 Spring Boot 프로젝트로 구현한 것. 전부 실제 Ollama에 연결해서 검증 완료.

## 사전 준비
```bash
brew install ollama
brew services start ollama
ollama pull bge-m3          # 임베딩 (사양 무관 공통, 이유는 아래 "실측 이슈" 참고)
ollama pull llama3.2:3b     # LLM (8GB 노트북 기준. 16GB+면 qwen2.5:7b 등으로 교체)
```

## 실행 방법
프로필 이름을 몰라도 되게 `run.sh`(macOS/Linux) / `run.bat`(Windows) 래퍼 스크립트로 실행한다 (내부적으로 `RagDay1DemoApplication`의 `@Profile`을 골라서 켜줌).

```bash
# macOS / Linux
./run.sh live-demo             # M1.1 라이브 데모 — Swagger UI(브라우저), 강의 라이브용 추천
./run.sh lab11                 # 임베딩 유사도 실습
./run.sh lab12                 # 인덱싱 파이프라인 실습
./run.sh lab13                 # RAG 챗봇 (콘솔 대화형, 빈 줄 입력으로 종료)
./run.sh hallucination-console # M1.1 할루시네이션 재현 (콘솔 버전, Swagger 대신 쓰고 싶을 때)
./run.sh compare-console       # M1.1 순수 LLM vs RAG 비교 (콘솔 버전)
```

```bat
:: Windows (cmd 또는 PowerShell)
run.bat live-demo
run.bat lab11
run.bat lab12
run.bat lab13
run.bat hallucination-console
run.bat compare-console
```
(원한다면 `mvnw spring-boot:run -Dspring-boot.run.profiles=<이름>`으로 직접 실행해도 동일하게 동작함 — `run.sh`/`run.bat`은 그 명령을 대신 기억해주는 것뿐. Windows에서는 `mvnw.cmd`)

## Swagger로 라이브 데모하기 (강의 중 추천 방식)
콘솔보다 훨씬 보기 좋고, 그 자리에서 질문을 바꿔가며 보여줄 수 있음. `./run.sh live-demo`(Windows는 `run.bat live-demo`)로 띄운 뒤 브라우저에서:
```
http://localhost:8080/swagger-ui/index.html
```
- `GET /api/compare?question=...` — 순수 LLM vs RAG 답변 나란히 비교 (기본 질문: E2 오류 코드)
- `GET /api/hallucination?studentQuestion=...` — 프리셋 할루시네이션 3종(지어낸 API / 최신 이벤트 / 학사 일정) + 학생이 그 자리에서 제안하는 질문을 `studentQuestion`에 넣으면 4번째 결과로 같이 나옴 (비워두면 프리셋 3개만 실행)
- `GET /api/chat?question=...` — RAG 챗봇에 자유 질문

세 엔드포인트 모두 Swagger UI에 파라미터 설명(description)과 예시(example)가 달려있어서, 학생이 화면만 보고도 뭘 입력해야 하는지 알 수 있음. (초기 버전엔 `/api/hallucination`에 입력창이 아예 없었고 파라미터 설명도 비어있었는데, 실제 UX 확인 후 수정함.)

참고: `studentQuestion`으로 테스트해보면 매번 100% 할루시네이션이 재현되는 건 아님 — 질문에 따라 모델이 "모른다"고 정직하게 답하는 경우도 있음 (예: "우리 학교 총학생회장 이름이 뭐야?"는 한 번 실패함). 라이브 중 안 걸리면 질문을 좀 더 구체적으로/그럴듯하게 바꿔서 재시도할 것.

실측 확인(2026-08-18): `/api/compare` 기본 질문 기준 — 순수 LLM은 "10분 대기 후 재시작" 같은 완전히 지어낸 절차를 답하고, RAG는 문서 제5조 그대로 "고객센터(1544-0000) 문의"라고 정확히 답함. 콘솔 데모보다 이 대비가 화면으로 훨씬 잘 보임.

주의: `spring-boot-starter-web`이 추가되면서 **어떤 프로필로 실행하든 8080 포트에 Tomcat이 항상 뜬다** (컨트롤러 자체는 `api` 프로필에서만 등록되지만 서버는 항상 켜짐). 포트 충돌 시 `--server.port=8081` 등으로 바꿀 것.

## 실습 문서
`src/main/resources/docs/manual.txt` / `manual.pdf` — 가상의 커피메이커(MCM-200) 제품 매뉴얼. 조항 번호(제N조)와 오류 코드(E1/E2/E3), 고객센터 번호 등 고유명사가 있어 Day2 하이브리드 검색 실습에도 그대로 재사용 가능.

## 실측 중 발견한 이슈 (강의 전 꼭 알아둘 것)

### 1. `nomic-embed-text`는 한국어에서 신뢰 불가
처음엔 8GB 노트북용으로 가벼운 `nomic-embed-text`(274MB)를 쓰려 했으나, 실제로 돌려보니 의미상 무관한 한국어 문장이 의미상 유사한 문장보다 코사인 유사도가 더 높게 나오는 문제가 있었음.
- "물탱크 용량이 궁금해요" vs "물통에는 물이 얼마나 들어가나요?" → 0.67
- "물탱크 용량이 궁금해요" vs "오늘 저녁 메뉴로 뭐가 좋을까요?" → **0.78 (더 높음, 잘못된 결과)**
- Nomic 권장 접두어(`search_query:`/`search_document:`)를 붙여도 동일하게 실패
- `bge-m3`로 교체하니 유사 0.62 vs 무관 0.42~0.44로 정상 작동

**결론: 임베딩 모델은 노트북 사양과 무관하게 `bge-m3`로 통일.** Lab1.1의 핵심 메시지("임베딩은 의미를 본다")가 걸린 부분이라 데모가 실패하면 수업 전체 설득력이 흔들림.

### 2. `llama3.2:3b`는 가끔 영어/한자로 답함
한국어로 질문해도 영어나 중국어 한자가 섞여 나오는 경우가 있었음(예: `deleteAllByMetadata()`를 지어내면서 "documento", "步骤" 같은 표현이 섞여 나옴 — 이건 오히려 할루시네이션 데모용으로는 더 임팩트 있음). 다만 Lab1.3/M1-compare처럼 정상 답변을 보여줘야 하는 데모에서는 `ChatClient.builder(chatModel).defaultSystem("항상 한국어로 답변하세요.")`로 시스템 프롬프트를 걸어서 해결함. 이미 코드에 반영되어 있음.

### 3. Netty DNS 관련 ERROR 로그
실행 시 `Unable to load io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider...` 에러 로그가 뜨는데 기능에는 영향 없음(단순 경고). 강의 중 학생이 "에러 떴어요!"라고 당황할 수 있으니 미리 언급해둘 것.

## 코드 구조
```
com.lecture.rag
├── lab11/EmbeddingSimilarityDemo.java   (@Profile("lab11"))
├── lab12/IndexingPipelineDemo.java      (@Profile("lab12"))
├── lab13/RagChatbotDemo.java            (@Profile("lab13"))
├── m1demo/
│   ├── HallucinationLiveDemo.java       (@Profile("m1-hallucination"), 콘솔)
│   └── PureLlmVsRagDemo.java            (@Profile("m1-compare"), 콘솔)
└── api/LiveDemoController.java          (@Profile("api"), Swagger UI — 위 두 데모의 웹 버전)
```
