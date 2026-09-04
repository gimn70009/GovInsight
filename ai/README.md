# GovInsight AI

Python 3.12와 FastAPI를 사용하는 GovInsight 수집·분석 모듈이다.

## 프로젝트 구조

```text
ai/
├─ app/
│  ├─ main.py
│  ├─ api/
│  │  └─ router.py
│  ├─ core/
│  │  ├─ logging.py
│  │  └─ schemas.py
│  └─ domains/
│     └─ monitoring/
│        ├─ api.py
│        ├─ service.py
│        ├─ tasks.py
│        └─ schemas/
│           ├─ request.py
│           └─ response.py
├─ tests/
│  ├─ domains/
│  │  └─ monitoring/
│  └─ test_health.py
├─ pyproject.toml
├─ requirements.txt
└─ requirements-dev.txt
```

- `main.py`: FastAPI 애플리케이션 생성과 공통 API 라우터 등록
- `api/router.py`: 도메인별 API 라우터 조립
- `core/logging.py`: AI 모듈의 공통 로그 레벨과 출력 형식 설정
- `core/schemas.py`: JSON 필드 명명 방식 등 공통 스키마 규칙
- `core/config.py`: Spring Boot 주소와 HTTP 타임아웃 환경설정
- `domains/monitoring/api.py`: 모니터링 내부 API의 HTTP 요청과 응답 처리
- `domains/monitoring/schemas/request.py`: Spring Boot에서 받는 요청 형식 정의
- `domains/monitoring/schemas/response.py`: Spring Boot로 보내는 응답 형식 정의
- `domains/monitoring/service.py`: 작업 ID 생성과 접수 응답 생성
- `domains/monitoring/tasks.py`: HTTP 응답 이후 실행되는 모니터링 백그라운드 작업
- `domains/monitoring/collectors`: Playwright 수집과 상세 게시글 URL 선별
- `domains/monitoring/clients`: Spring Boot 수집 결과 API 호출
- `domains/monitoring/schemas/collection_result.py`: 수집 결과 요청·응답 형식
- `domains/monitoring/schemas/collected_document.py`: 문서·첨부파일·소스별 수집 결과 모델
- `tests/domains/monitoring`: 모니터링 API와 백그라운드 작업 테스트

## 현재 지원하는 기관별 수집 프로필

- 산업통상부
- 과학기술정보통신부
- 기후에너지환경부
- 고용노동부
- 국토교통부
- 한국산업기술진흥원

기관별 프로필은 JavaScript 상세 이동 링크, 제목·본문·게시일 선택자와 첨부파일 링크 형식을 처리한다. 등록 시에는 해당 기관의 게시판 목록 URL과 상세 URL 포함 패턴을 사용한다. 사이트 개편으로 화면 구조가 바뀌면 프로필도 수정해야 하며, 첨부파일 중심 게시글은 본문이 비어 있을 수 있다.

## 개발 환경

```cmd
cd C:\GovInsight\ai
python -m venv .venv
.venv\Scripts\activate.bat
python -m pip install --upgrade pip
python -m pip install -r requirements-dev.txt
python -m playwright install chromium
```

이미 `.venv`가 존재하면 새로 만들지 않고 활성화한 뒤 requirements 파일을 설치한다.

```cmd
.venv\Scripts\activate.bat
python -m pip install -r requirements-dev.txt
python -m playwright install chromium
```

## Spring Boot 연동 설정

기본적으로 `http://127.0.0.1:8080`으로 수집 결과를 전달한다. 주소나 타임아웃을 바꿀 때만 환경변수를 설정한다.

```cmd
set SPRING_BOOT_BASE_URL=http://127.0.0.1:8080
set SPRING_BOOT_TIMEOUT_SECONDS=5
set ATTACHMENT_DOWNLOAD_TIMEOUT_SECONDS=15
set ATTACHMENT_MAX_SIZE_BYTES=20971520
set ZIP_MAX_ENTRY_COUNT=20
set ZIP_MAX_UNCOMPRESSED_SIZE_BYTES=104857600
```
## 첨부파일 메타데이터 처리

수집한 첨부파일은 운영체제 임시 폴더에 스트리밍 방식으로 내려받는다. 다운로드 중 파일 크기와 SHA-256 해시를 계산하고 HTTP 응답의 MIME 타입을 정리한 뒤, 임시 파일은 즉시 삭제한다. 파일 원본은 Git이나 Oracle에 저장하지 않는다.

- 기본 다운로드 제한 시간: 15초
- 기본 최대 파일 크기: 20 MiB
- PDF·HWP·HWPX 및 ZIP 내부의 PDF·HWP·HWPX 파싱 성공: 추출 텍스트 저장 후 `PARSE_STATUS=COMPLETED`
- 그 외 형식: `PARSE_STATUS=UNSUPPORTED`
- 다운로드 또는 파싱 실패: `PARSE_STATUS=FAILED`와 안전한 오류 메시지 저장
- PDF는 `pypdf`, HWP는 `olefile`과 HWP 5.0 레코드 분석, HWPX는 Python ZIP·XML 기능으로 처리
- ZIP은 내부 파일 20개와 총 압축 해제 크기 100 MiB를 기본 상한으로 사용하며 중첩 ZIP은 분석하지 않음
- 암호화·DRM·배포용 HWP와 그림·OLE 개체 추출은 지원하지 않음
- 본문 전체와 다운로드 URL은 로그에 남기지 않음

## AI 문서 분석 설정

`.env.example`을 참고하여 `ai/.env`에 OpenAI API 키를 설정한다. `.env`는 Git에서 제외되며 실제 키를 커밋하지 않는다.

```ini
OPENAI_API_KEY=발급받은_API_키
OPENAI_MODEL=gpt-5-mini
ANALYSIS_TIMEOUT_SECONDS=180
PROPOSAL_MODEL=gpt-5-mini
PROPOSAL_TIMEOUT_SECONDS=180
ANALYSIS_MAX_ATTEMPTS=2
ANALYSIS_CONCURRENCY=2
ANALYSIS_MAX_TEXT_CHARS=40000
```

분석 에이전트는 LangChain 도구로 현재 게시글 본문, 첨부파일 추출 텍스트와 이전 버전 차이를 필요한 순서대로 조회한다. LangGraph는 문서별 분석 상태, 결과 검증과 제한된 재시도를 관리한다. 문서는 `ANALYSIS_CONCURRENCY` 값에 따라 기본 최대 2건씩 동시에 분석하며 결과 순서와 문서별 실패 격리를 유지한다. 분석 결과는 Spring Boot로 전달되어 Oracle에 저장된다.

사업 제안 준비안은 조건부 2단계로 생성한다. 공고 분석 결과를 먼저 Spring Boot에 전달해 화면 저장과 보고서 생성을 시작한 뒤, 제안서 제출 공고, 회사 적합도 61점 이상, 신청 불가 아님, 파싱된 첨부 양식 보유 조건을 모두 만족한 문서만 별도 백그라운드 단계에서 후속 처리한다. 후속 단계는 같은 OpenAI API 키와 `PROPOSAL_MODEL` 모델을 사용하며 `PROPOSAL_TIMEOUT_SECONDS`의 기본 180초 제한을 적용한다. 생성이 끝나면 제안 결과만 별도 내부 API로 갱신한다. 후속 처리 실패는 기본 공고 분석과 보고서 생성을 지연시키지 않으며 사업 제안만 추가 검토 상태로 저장한다.

문서별 분석 저장이 완료되면 Spring Boot가 `/internal/monitoring/report-jobs`로 보고서 생성을 요청한다. Python은 추가 OpenAI 호출 없이 문서별 요약과 중요도를 템플릿으로 조합해 기관·게시판별 전체 보고서 제목·요약을 생성하고 `/internal/monitoring/report-results`로 전달한다. 변경 없는 버전에 저장된 분석이 있으면 Spring Boot가 AI 재분석을 생략하고 같은 분석을 보고서에 재사용한다.

## 실행

```cmd
python -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

- API 문서: <http://localhost:8000/docs>
- 상태 확인: <http://localhost:8000/health>

## 검증

```cmd
python -m pytest
python -m ruff check .
```
