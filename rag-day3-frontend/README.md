# RAG 특강 Day3 캡스톤 챗봇 — 프론트

Vercel AI SDK(`useChat` + `TextStreamChatTransport`)로 만든 스트리밍 채팅 UI. PDF 업로드/인덱싱과 채팅 응답 스트리밍 모두 `rag-day3-demo` 백엔드(기본 `http://localhost:8081`)를 브라우저에서 직접 fetch로 호출한다 — Next.js API 라우트를 따로 두지 않은 구조.

## 사전 준비

**Node.js 설치** (LTS 버전, 없는 경우만)

macOS:
```bash
brew install node
```

Windows (PowerShell, winget은 Windows 10 2004+/11에 기본 내장):
```powershell
winget install OpenJS.NodeJS.LTS
```
winget이 없거나 옛날 Windows면 [nodejs.org](https://nodejs.org)에서 LTS 인스톨러를 직접 받아도 됨. 설치 후 새 터미널에서 `node -v`로 확인.

**패키지 설치**
```bash
npm install
```
백엔드(`rag-day3-demo`)가 먼저 8081 포트로 떠 있어야 함.

## 실행
```bash
npm run dev
```
[http://localhost:3000](http://localhost:3000) 접속.

## 백엔드 주소 바꾸기
기본값은 `http://localhost:8081`. 다른 포트/주소를 쓰려면 `.env.local` 파일에:
```
NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
```

## 구조
- `src/app/page.tsx` 하나로 구성된 클라이언트 컴포넌트. 서버 컴포넌트/Next.js API 라우트 없음 — 전부 브라우저에서 백엔드로 직접 요청.
- 채팅 전송 시 `prepareSendMessagesRequest`로 요청 바디를 AI SDK의 `UIMessage` 형식이 아니라 백엔드가 기대하는 단순한 `{ question: string }`으로 바꿔서 보냄.
- 백엔드가 CORS로 이 origin(`http://localhost:3000`)을 허용해줘야 동작함 — 다른 포트로 띄우면 `rag-day3-demo`의 `application.yml`도 같이 맞춰야 함.
