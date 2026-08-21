# RAG 특강 Day3 캡스톤 챗봇 — 백엔드

`rag-day3-frontend`(Next.js + Vercel AI SDK)가 호출하는 백엔드. PDF 업로드 → 인덱싱 → 스트리밍 RAG 응답까지 REST API 두 개로 제공한다.

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

## 실행 방법
```bash
./run.sh        # macOS/Linux, 포트 8081
```
```bat
run.bat         :: Windows
```

## API
- `POST /api/index` — `multipart/form-data`, 필드명 `file` (PDF). 응답: `{ fileName, chunkCount }`
- `POST /api/chat` — JSON `{ question: string }`. 응답: `text/plain` 스트리밍(청크 단위로 그대로 흘려보냄, Vercel AI SDK의 `TextStreamChatTransport`가 소비하는 형식)

## 구조
- `VectorStore`는 요청마다 만드는 게 아니라 `/api/index`가 호출될 때마다 새로 갈아끼우는 싱글턴 필드 — Lab1.4(`PdfRagApiController`)와 동일한 패턴, 여러 문서를 동시에 다루지 않는 캡스톤 데모용 단순화.
- CORS는 `application.yml`의 `app.cors.allowed-origin`(기본 `http://localhost:3000`)만 허용 — 프론트 포트를 바꾸면 여기도 맞춰서 바꿀 것.
- 채팅 스트리밍은 `ChatClient...stream().content()`가 반환하는 `Flux<String>`을 컨트롤러가 그대로 리턴 — Spring MVC(서블릿 스택)에서도 `Flux` 리턴 타입은 청크 단위로 그대로 흘려보내진다(WebFlux 불필요).

## 주의사항
- `/api/chat`을 부르기 전에 `/api/index`로 PDF를 먼저 올려야 함 — 안 올리면 "먼저 문서를 업로드해주세요" 고정 응답만 옴
- day1/day2와 포트 충돌을 피하려고 8081로 분리해둠 — 셋을 동시에 띄워도 됨
