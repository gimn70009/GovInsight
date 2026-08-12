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
│  │  └─ schemas.py
│  └─ domains/
│     └─ monitoring/
│        ├─ api.py
│        ├─ service.py
│        └─ schemas/
│           ├─ request.py
│           └─ response.py
├─ tests/
│  ├─ domains/
│  │  └─ monitoring/
│  └─ test_health.py
├─ pyproject.toml
└─ uv.lock
```

- `main.py`: FastAPI 애플리케이션 생성과 공통 API 라우터 등록
- `api/router.py`: 도메인별 API 라우터 조립
- `core/schemas.py`: JSON 필드 명명 방식 등 공통 스키마 규칙
- `domains/monitoring/api.py`: 모니터링 내부 API의 HTTP 요청과 응답 처리
- `domains/monitoring/schemas/request.py`: Spring Boot에서 받는 요청 형식 정의
- `domains/monitoring/schemas/response.py`: Spring Boot로 보내는 응답 형식 정의
- `domains/monitoring/service.py`: 작업 접수와 처리 로직
- `tests/domains/monitoring`: 모니터링 API와 서비스 테스트

## 개발 환경

```cmd
cd C:\GovInsight\ai
uv sync --dev
```

가상환경을 직접 활성화하려면 CMD에서 다음 명령을 사용한다.

```cmd
.venv\Scripts\activate.bat
```

## 실행

```cmd
uv run uvicorn app.main:app --reload --port 8000
```

- API 문서: <http://localhost:8000/docs>
- 상태 확인: <http://localhost:8000/health>

## 검증

```cmd
uv run pytest
uv run ruff check .
```
