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
- `tests/domains/monitoring`: 모니터링 API와 백그라운드 작업 테스트

## 개발 환경

```cmd
cd C:\GovInsight\ai
python -m venv .venv
.venv\Scripts\activate.bat
python -m pip install --upgrade pip
python -m pip install -r requirements-dev.txt
```

이미 `.venv`가 존재하면 새로 만들지 않고 활성화한 뒤 requirements 파일을 설치한다.

```cmd
.venv\Scripts\activate.bat
python -m pip install -r requirements-dev.txt
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
