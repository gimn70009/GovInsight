# GovInsight 시스템 구조

## 문서 목적

이 문서는 GovInsight를 구성하는 Spring Boot, Python, Frontend, PostgreSQL의 역할과 모듈 간 통신 방식을 정의한다.

## 구성 요소와 책임

### Spring Boot Backend

Spring Boot는 시스템 실행과 데이터 관리의 중심 역할을 담당한다.

- Frontend용 REST API 제공
- 사용자와 권한 관리
- 모니터링 소스 등록·수정·조회
- 수동 실행 요청 처리
- 자동 스케줄 실행
- 모니터링 실행 이력과 상태 관리
- Python 모듈에 수집·분석 작업 요청
- Python 모듈의 처리 결과 수신
- PostgreSQL 데이터 저장 및 조회
- 보고서 생성 결과 관리
- Telegram 수신자 관리 및 보고서 전송

### Python 수집·분석 모듈

Python 모듈은 수집과 문서 분석을 담당한다.

- 작업 요청 접수 및 작업 ID 발급
- 백그라운드 작업 실행
- Playwright 기반 게시판 목록·상세 페이지 수집
- 게시글 본문과 첨부파일 정보 추출
- PDF, HWP, HWPX 및 일부 HTML 첨부파일 파싱
- 게시글 변경 여부 판정에 필요한 정보 생성
- LLM 기반 요약, 핵심 포인트, 위험도, 중요도 생성
- 처리 결과와 경고를 Spring Boot에 전달

Python 모듈은 PostgreSQL에 직접 접근하지 않는다. 저장이 필요한 결과는 Spring Boot의 내부 API로 전달한다.

### Frontend

Frontend는 관리자용 화면을 제공하며 Spring Boot API만 호출한다.

- 로그인 화면
- 운영 현황 대시보드
- 모니터링 소스 관리
- 수동 실행 요청
- 실행 이력과 보고서 조회
- 최근 저장 문서 조회
- Telegram 수신자 설정

Frontend는 Python 모듈을 직접 호출하지 않는다.

### PostgreSQL

PostgreSQL은 시스템의 영속 데이터를 저장한다.

- 사용자와 Telegram 수신 설정
- 모니터링 소스
- 실행 이력과 작업 상태
- 수집 문서와 변경 상태
- 첨부파일 메타정보와 추출 텍스트
- 감지 결과와 LLM 분석 결과
- 보고서와 Telegram 발송 결과

PostgreSQL 접근은 Spring Boot를 통해서만 수행한다.

## 비동기 HTTP 작업 흐름

모니터링은 오래 걸릴 수 있으므로 Spring Boot가 Python의 최종 결과를 하나의 HTTP 연결에서 기다리지 않는다.

```text
1. Frontend 또는 스케줄러가 Spring Boot에 실행 요청
2. Spring Boot가 실행 이력 생성
3. Spring Boot가 Python에 HTTP 작업 요청
4. Python이 작업 ID와 접수 상태를 즉시 응답
5. Python이 백그라운드에서 수집·파싱·분석 수행
6. Python이 Spring Boot에 HTTP로 최종 결과 전달
7. Spring Boot가 결과 저장 및 실행 상태 갱신
8. Spring Boot가 Telegram 보고서 전송
```

## 내부 API 방향

### 작업 요청

Spring Boot가 Python 모듈을 호출한다.

```http
POST /internal/monitoring/jobs
```

요청에는 최소한 다음 정보가 포함되어야 한다.

- Spring Boot에서 생성한 실행 ID
- 수집할 활성 소스 목록
- 소스 ID, 기관명, 게시판명, 목록 URL
- 상세 수집 수와 URL 포함 패턴
- 조회 기간과 작업 옵션
- 기존 문서의 원문 식별자, 본문 해시, 첨부파일 시그니처

Python 모듈은 작업을 접수한 후 최종 결과를 기다리게 하지 않고 `202 Accepted`와 작업 ID를 반환한다.

Python 모듈은 Spring Boot가 전달한 기존 문서의 해시 및 첨부파일 시그니처와 새로 수집한 값을 비교해 변경 상태를 판정한다. 기존 문서 정보가 없는 게시글은 신규 문서로 처리한다.

### 결과 전달

Python 모듈이 처리를 마친 후 Spring Boot를 호출한다.

```http
POST /internal/monitoring/results
```

결과에는 최소한 다음 정보가 포함되어야 한다.

- 실행 ID와 Python 작업 ID
- 작업 완료 또는 실패 상태
- 수집 문서와 첨부파일 정보
- 변경 감지 결과
- LLM 분석 결과
- 처리 중 발생한 경고와 오류 정보

Spring Boot가 결과를 정상적으로 저장하면 성공 응답을 반환한다.

정확한 요청·응답 JSON과 오류 코드는 API 설계 문서에서 별도로 정의한다.

## 작업 상태

작업 상태는 최소한 다음 단계를 구분할 수 있어야 한다.

- `REQUESTED`: Spring Boot가 실행을 생성함
- `ACCEPTED`: Python 모듈이 작업을 접수함
- `COMPLETED`: 결과 저장과 후속 처리가 정상 완료됨
- `FALLBACK`: 정상 보고서를 만들 수 없어 점검용 결과로 종료됨
- `FAILED`: 작업을 계속할 수 없는 오류로 종료됨

상태 이름과 전환 조건은 구현 전에 데이터베이스 및 API 설계 문서에서 최종 확정한다.

## 실패와 재시도

- Spring Boot가 Python에 작업을 전달하지 못하면 실행 이력에 요청 실패 원인을 기록한다.
- Python은 결과 전달에 실패하면 동일한 실행 ID와 작업 ID로 제한적으로 재시도할 수 있다.
- Spring Boot의 결과 수신 API는 동일한 결과가 다시 도착해도 중복 저장되지 않도록 설계한다.
- 일부 소스 실패는 전체 작업 결과와 분리해 경고로 전달할 수 있어야 한다.
- Python 프로세스가 재시작되면 메모리에만 존재하던 작업이 유실될 수 있음을 고려한다.

## 현재 범위에서 제외하는 항목

초기 로컬 개발 단계에서는 다음 기술을 도입하지 않는다.

- Redis
- Celery
- RabbitMQ 또는 Kafka 같은 메시지 브로커
- 별도 분산 작업 큐

초기에는 Python 프로세스 내부의 백그라운드 작업으로 구현한다. 운영 안정성, 재시도, 동시 실행 요구가 커지면 영속 작업 큐 도입을 다시 검토한다.

## 로컬 실행과 내부 통신

- 현재 프로젝트는 배포하지 않고 하나의 로컬 모노레포에서 실행한다.
- 프론트엔드의 사용자 요청은 Spring Boot에서 JWT로 인증한다.
- 프론트엔드는 Python 모듈을 직접 호출하지 않는다.
- Spring Boot와 Python 사이에는 별도 인증 토큰을 사용하지 않는다.
- Python 서버는 `127.0.0.1`에만 바인딩해 같은 컴퓨터 밖에서 접근하지 못하게 한다.
- 프론트엔드 사용자 JWT를 Python 내부 API로 전달하지 않는다.
- Spring Boot와 Python 사이의 HTTP 요청에는 연결 및 응답 타임아웃을 설정한다.
- API 키, 비밀번호, JWT, 개인정보와 게시글 원문 전체를 로그에 불필요하게 남기지 않는다.
- 향후 외부 서버에 배포하거나 두 모듈을 서로 다른 컴퓨터에서 실행할 때 내부 인증 방식을 다시 설계한다.
