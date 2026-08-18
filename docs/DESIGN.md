# GovInsight 설계

## 1. 프로젝트 개요

GovInsight는 공공기관 게시판을 주기적으로 확인하고 새로 등록되거나 변경된 문서를 수집·분석하는 모니터링 시스템이다.

주요 목적은 기업에 영향을 줄 수 있는 규제, 지원사업과 공고의 변화를 빠르게 확인하는 것이다.

## 2. 시스템 구성

### Spring Boot

- 사용자 로그인과 JWT 인증
- 모니터링 소스, 실행 이력과 문서 데이터 관리
- Python 작업 요청과 수집·분석 결과 수신
- 문서 변경 감지와 Oracle 저장
- 보고서 조회와 Telegram 전송

### Python

- Spring Boot 작업 요청 접수와 백그라운드 실행
- Playwright 기반 게시글 수집
- 첨부파일 임시 다운로드와 메타데이터 계산
- 첨부파일 텍스트 추출과 AI 에이전트 분석
- 처리 결과를 Spring Boot로 전달

첨부파일 텍스트 추출과 AI 분석은 이후 단계에서 구현한다.

### Frontend

- 관리자 로그인
- 모니터링 소스 관리와 수동 실행
- 실행 이력, 감지 문서와 보고서 조회

### Oracle

- 사용자와 모니터링 소스
- 전체·소스별 실행 이력
- 문서, 버전, 첨부파일과 감지 결과
- AI 분석 결과와 실행 보고서

Python은 Oracle에 직접 접근하지 않는다. 데이터 저장과 조회는 Spring Boot가 담당한다.

## 3. 모니터링 처리 흐름

```text
관리자가 실행 요청
        ↓
Spring Boot가 실행 이력 생성
        ↓
Spring Boot가 Python에 작업 요청
        ↓
Python이 작업 ID와 ACCEPTED 상태 반환
        ↓
Python 백그라운드 작업에서 게시글과 첨부파일 수집
        ↓
Python이 Spring Boot에 수집 결과 전달
        ↓
Spring Boot가 변경 감지 후 Oracle에 저장
- 전체 실행 상태 = COLLECTED
        ↓
Python AI 분석과 분석 결과 전달
        ↓
분석과 보고서 생성 완료
- 전체 실행 상태 = COMPLETED
```

## 4. Spring Boot와 Python 통신

### 작업 접수

```http
POST /internal/monitoring/jobs
```

Spring Boot가 실행 ID와 활성 모니터링 소스 목록을 전달한다. Python은 작업을 백그라운드로 예약하고 작업 ID와 `ACCEPTED` 상태를 즉시 반환한다. `ACCEPTED`는 전체 작업 완료가 아니라 접수 완료를 뜻한다.

### 수집 결과 전달

```http
POST /internal/monitoring/collection-results
```

Python은 실행 ID, 작업 ID, 소스별 처리 상태, 게시글과 첨부파일 정보를 전달한다.

Spring Boot는 제목과 본문을 정규화하고 해시를 비교하여 `NEW_DOCUMENT`, `UPDATED_DOCUMENT`, `UNCHANGED_DOCUMENT`를 판정한다. 신규·수정 문서만 새 버전과 첨부파일을 저장하고, 모든 문서는 실행별 감지 결과를 남긴다.

하나 이상의 소스가 정상 저장되면 전체 실행 상태를 `COLLECTED`로 변경한다. 모든 소스가 실패하면 `FAILED`로 변경한다. 수집 완료는 전체 처리 완료가 아니므로 `COMPLETED_AT`은 기록하지 않는다.

### AI 분석 결과 전달

```http
POST /internal/monitoring/analysis-results
```

Python AI 에이전트가 분석 결과를 Spring Boot에 전달하는 API이며 AI 분석 단계에서 구현한다.

## 5. 실행 상태

### 전체 실행 상태

- `REQUESTED`: Spring Boot가 실행 이력을 생성함
- `ACCEPTED`: Python이 작업을 접수함
- `RUNNING`: Python이 수집·처리 중임
- `COLLECTED`: 수집 결과 저장이 끝나 AI 분석을 기다림
- `COMPLETED`: AI 분석과 보고서 생성을 포함한 전체 작업이 완료됨
- `FAILED`: 작업이 실패함

### 소스별 실행 상태

- `PENDING`
- `RUNNING`
- `COMPLETED`
- `FAILED`

### 실행 방식

- `MANUAL`: 관리자 수동 실행
- `SCHEDULED`: 자동 스케줄 실행

## 6. 모니터링 소스와 지원 범위

현재는 사전에 검증한 다음 5개 기관의 게시판을 우선 지원한다.

- 산업통상부
- 과학기술정보통신부
- 기후에너지환경부
- 고용노동부
- 국토교통부

모든 공공기관 URL의 자동 수집을 보장하지 않는다. 검증된 게시판은 기본 모니터링 소스로 제공하며, 신규 기관은 Python의 `SiteProfile`과 관련 테스트를 추가하여 지원한다. 사이트 구조가 변경되면 해당 프로필과 테스트도 수정한다.

`listUrl`은 게시글 목록 페이지 주소다. `urlIncludePattern`은 목록에서 상세 게시글 URL만 선택하기 위한 기술 설정으로, 기본 소스에는 검증된 값을 미리 등록한다. 패턴이 없으면 메뉴·로그인·검색 링크를 게시글로 오인할 수 있으므로 신규 소스는 실제 URL 구조를 확인해야 한다.

한 소스의 수집이 실패해도 다른 소스의 수집은 계속한다.

## 7. 문서 수집과 첨부파일 처리

Python은 Playwright로 다음 정보를 수집한다.

- 게시글 제목, 본문과 게시일
- 원문 URL과 원본 게시글 번호
- 첨부파일 이름과 다운로드 URL

기관별 제목·본문·게시일 선택자, JavaScript 링크 변환과 게시글 ID 규칙은 `SiteProfile`에서 관리한다. `read.do`, `view.do`, `DTL.jsp` 같은 페이지 파일명은 게시글 ID로 저장하지 않으며 유효한 식별자가 없으면 `NULL`로 저장한다.

첨부파일은 운영체제 임시 폴더에 스트리밍 방식으로 다운로드한다. 다운로드 중 MIME 타입, 파일 크기와 SHA-256 해시를 계산하고 임시 파일은 즉시 삭제한다. 원본 파일 자체는 Oracle에 저장하지 않는다.

- 다운로드 성공: 텍스트 추출 전이므로 `PARSE_STATUS=PENDING`
- 다운로드 실패: `PARSE_STATUS=FAILED`와 안전한 오류 메시지 저장
- 제한 시간과 최대 파일 크기: 환경변수로 관리
- PDF·HWP·HWPX 텍스트 추출: 이후 단계에서 구현

## 8. 문서 저장과 변경 감지

### 주요 테이블

- `DOCUMENTS`: 게시글 고유 정보와 최초·마지막 발견 시각
- `DOCUMENT_VERSIONS`: 제목, 본문, 게시일과 첨부파일 구성이 변경된 버전
- `DOCUMENT_ATTACHMENTS`: 버전별 첨부파일 메타데이터와 파싱 결과
- `DOCUMENT_DETECTIONS`: 실행별로 감지된 문서, 버전과 변경 유형

```text
MONITORING_SOURCES 1 ── N DOCUMENTS
DOCUMENTS 1 ── N DOCUMENT_VERSIONS
DOCUMENT_VERSIONS 1 ── N DOCUMENT_ATTACHMENTS

MONITORING_RUN_SOURCES 1 ── N DOCUMENT_DETECTIONS
DOCUMENTS 1 ── N DOCUMENT_DETECTIONS
DOCUMENT_VERSIONS 1 ── N DOCUMENT_DETECTIONS
```

JPA 연관관계는 자식 엔티티의 `LAZY @ManyToOne` 단방향 매핑을 기준으로 한다.

변경 유형은 다음과 같다.

- 처음 발견한 게시글: `NEW_DOCUMENT`
- 기존 게시글의 내용이나 첨부파일이 변경됨: `UPDATED_DOCUMENT`
- 이전 버전과 같음: `UNCHANGED_DOCUMENT`

제목, 정규화한 본문과 첨부파일 구성으로 버전 해시를 생성한다. 변경되지 않은 문서는 새 버전을 만들거나 다시 분석하지 않고 마지막 확인 시각만 갱신한다.

## 9. AI 에이전트

수집, 첨부파일 파싱과 변경 감지는 예측 가능한 일반 코드가 담당한다. AI 에이전트는 신규 또는 변경된 문서를 대상으로 다음 작업을 수행한다.

- 문서 요약과 핵심 내용 추출
- 중요도 판정과 판단 근거 생성
- 필요한 분석 도구 선택 및 호출

초기 도구는 게시글 본문 조회, 첨부파일 추출 텍스트 조회, 이전 버전 비교와 관련 문서 조회로 구성한다. 내부 추론 과정은 저장하지 않고 최종 결과와 실제 도구 사용 기록만 남긴다.

## 10. 운영 원칙

- 비밀번호, JWT와 API 키를 Git에 저장하지 않는다.
- 로컬 개발 중 Python 서버는 `127.0.0.1`에 바인딩한다.
- 게시글 본문 전체와 민감정보를 로그에 남기지 않는다.
- 외부 요청에는 연결 및 응답 타임아웃을 적용한다.
- 대상 사이트의 이용정책, 저작권 정책과 `robots.txt`를 검토한다.
- 로그인, CAPTCHA와 접근 제한을 우회하지 않는다.
- 과도한 요청을 피하고 수집 주기와 건수를 제한한다.
- 현재는 프로세스 내부 백그라운드 작업을 사용하며, 필요할 때 Redis·Celery 등의 도입을 검토한다.
