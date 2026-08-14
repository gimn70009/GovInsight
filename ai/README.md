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
- `domains/monitoring/api.py`: 모니터링 내부 API의 HTTP 요청과 응답 처리
- `domains/monitoring/schemas/request.py`: Spring Boot에서 받는 요청 형식 정의
- `domains/monitoring/schemas/response.py`: Spring Boot로 보내는 응답 형식 정의
- `domains/monitoring/service.py`: 작업 ID 생성과 접수 응답 생성
- `domains/monitoring/tasks.py`: HTTP 응답 이후 실행되는 모니터링 백그라운드 작업
- `domains/monitoring/collectors`: Playwright 수집과 상세 게시글 URL 선별
- `domains/monitoring/schemas/collected_document.py`: 문서·첨부파일·소스별 수집 결과 모델
- `tests/domains/monitoring`: 모니터링 API와 백그라운드 작업 테스트

## 현재 지원하는 기관별 수집 프로필

- 산업통상부
- 과학기술정보통신부
- 기후에너지환경부
- 고용노동부
- 국토교통부

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
