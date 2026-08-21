# RAG 에이전트 콘솔 — Day3 캡스톤 프론트엔드

문서를 인덱싱하고 질문하면서 **검색된 근거·파이프라인 단계·지표를 눈으로 확인하는** 작업 화면.
백엔드(`rag-day3-demo`)를 브라우저에서 직접 호출한다(Next.js API 라우트 없음).

실습 가이드는 → [`../rag-day3-demo/CAPSTONE.md`](../rag-day3-demo/CAPSTONE.md)

## 화면 구성

```
┌──────────────────────────────────────────────────────────────────────┐
│ 상단바: Ollama/모델/문서 상태 · 파이프라인 선택 · 설정 · 테마          │
├───────────────┬──────────────────────────────┬───────────────────────┤
│ 대화 세션      │  대화 (질문 + 실행 카드)      │ 인스펙터              │
│ 지식 베이스    │   ├ 단계 타임라인            │  근거 / 단계 /        │
│  업로드        │   ├ 답변 ([1] 인용 칩)       │  지표 / 이벤트 로그   │
│  문서 목록     │   └ 지표 · 복사/재실행/채점   │                       │
│  청킹 설정     │  입력창                      │                       │
└───────────────┴──────────────────────────────┴───────────────────────┘
```

주요 기능
- **단계 타임라인** — 검색·재정렬·생성이 각각 몇 ms 걸렸는지 실행 중에 실시간으로 켜진다.
- **TODO 카드** — 백엔드에 아직 구현되지 않은 단계를 점선 카드로 표시하고, 채워야 할 파일/메서드를 알려준다.
- **인용 칩** — 답변 안의 `[1]`을 누르면 그 근거 청크로 이동 + 하이라이트.
- **비교 실행** — 같은 질문을 두 파이프라인으로 동시에 실행해 나란히 비교(내 파이프라인 vs 기본 RAG).
- **채점** — 답변 카드의 "채점" 버튼이 LLM-as-judge 채점(충실도/관련성)을 호출한다.
- **이벤트 로그** — 백엔드가 보낸 NDJSON 원본을 그대로 보여준다. 학생이 추가한 이벤트도 여기 나온다.
- 대화 세션은 브라우저 localStorage에 저장된다(서버는 대화를 기억하지 않는다 — 매 요청에 기록을 실어 보낸다).

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

## 실행
```bash
npm run dev
```
[http://localhost:3000](http://localhost:3000) 접속. 백엔드(`rag-day3-demo`)가 8081 포트에 떠 있어야 한다.

## 백엔드 주소 바꾸기
기본값은 `http://localhost:8081`. 다른 포트/주소를 쓰려면 `.env.local` 파일에:
```
NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
```
백엔드의 `app.cors.allowed-origin`도 프론트 origin과 맞춰야 한다.

## 파일 구조

```
src/
├─ app/
│   ├─ page.tsx          <AgentConsole /> 하나만 렌더
│   ├─ layout.tsx        폰트·메타데이터
│   └─ globals.css       디자인 토큰(CSS 변수) + 공통 클래스
├─ lib/
│   ├─ protocol.ts       백엔드 이벤트 타입 정의 (AgentEvent.java와 1:1)
│   ├─ api.ts            fetch 래퍼 + NDJSON 스트림 파서
│   ├─ state.ts          Run/Message/Session 모델 + 이벤트 → 상태 변환
│   └─ useAgentConsole.ts 콘솔 전체 상태 (세션·스트리밍·지식베이스·설정)
└─ components/
    ├─ AgentConsole.tsx  레이아웃 조립
    ├─ TopBar.tsx        상태 표시등·파이프라인 선택
    ├─ KnowledgePanel.tsx 업로드·인덱싱 진행·문서 목록·청킹 설정
    ├─ ChatPanel.tsx     대화 목록·입력창·첫 화면 안내
    ├─ RunCard.tsx       실행 하나(단계+답변+지표+채점)
    ├─ StepTrace.tsx     단계 타임라인
    ├─ InspectorPanel.tsx 근거/단계/지표/로그 탭
    ├─ SettingsPanel.tsx 검색·생성 옵션·기능 토글
    ├─ MarkdownLite.tsx  가벼운 마크다운 렌더러 + 인용 칩
    └─ ui.tsx            뱃지·토글·슬라이더·아이콘
```

핵심 개념은 **Run**이다 — "질문 하나를 파이프라인 하나로 실행한 결과"로, 답변 텍스트뿐 아니라
단계·근거·지표·원본 이벤트를 모두 담는다. 비교 실행을 켜면 어시스턴트 메시지 하나가 Run을 두 개 갖는다.

## 스트리밍 방식

백엔드가 **NDJSON**(한 줄에 JSON 하나)으로 답변 토큰과 진행 상황을 같은 스트림에 섞어 보낸다.
`lib/api.ts`의 `streamNdjson()`이 `fetch` + `ReadableStream`으로 줄 단위 파싱을 하고,
`lib/state.ts`의 `applyEvent()`가 이벤트를 Run 상태에 반영한다.

Vercel AI SDK의 `useChat`을 쓰지 않는 이유: 이 화면은 답변 텍스트만이 아니라 단계·근거·지표까지
같은 스트림으로 받아 그려야 해서, 프로토콜을 직접 정의하는 게 단순하고 Spring 쪽에서 구현하기도 쉽다.
(파서 전체가 30줄이라 학생이 읽고 고칠 수 있다는 것도 이유다.)

## 프론트를 고쳐보고 싶다면

- 새 지표 배지 추가 → 백엔드에서 `AgentEvent.metric(...)`을 보내고 `RunCard.tsx` 푸터에 한 줄 추가
- 새 이벤트 타입 → `lib/protocol.ts`에 타입 추가 + `lib/state.ts`의 `applyEvent()`에 case 추가
- 색/여백 → `app/globals.css`의 CSS 변수만 바꾸면 다크·라이트가 함께 반영된다
  (컴포넌트에 `dark:` 클래스가 하나도 없는 이유)
