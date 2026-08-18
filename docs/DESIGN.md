# GovInsight 설계

## 1. 프로젝트 개요

GovInsight는 공공기관 게시판을 주기적으로 확인하고, 새로 등록되거나 변경된 문서를 수집·분석하는 모니터링 시스템이다.

주요 목적은 기업에 영향을 줄 수 있는 규제, 지원사업, 공고 등의 변화를 빠르게 확인하는 것이다.

## 2. 시스템 구성

### Spring Boot

- 사용자 로그인과 JWT 인증
- 모니터링 소스 관리
- 수동·자동 실행 관리
- 실행 이력과 문서 데이터 저장
- Python 작업 요청 및 결과 수신
- 보고서 조회와 Telegram 전송

### Python

- Spring Boot의 작업 요청 접수
- Playwright 기반 게시글 수집
- 첨부파일 다운로드 및 텍스트 추출
- 문서 변경 감지
- AI 에이전트 기반 문서 분석
- 처리 결과를 Spring Boot로 전달

### Frontend

- 관리자 로그인
- 모니터링 소스 관리
- 수동 실행
- 실행 이력 및 보고서 조회
- 수집 문서 조회

### Oracle

- 사용자
- 모니터링 소스
- 실행 이력
- 수집 문서와 버전
- 첨부파일과 분석 결과
- 실행 보고서

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
Python 백그라운드 작업 실행
        ↓
게시글 및 첨부파일 정보 수집
        ↓
Python이 Spring Boot에 수집 결과 전달
        ↓
Spring Boot가 변경 감지 후 Oracle에 저장
- 전체 실행 상태 = COLLECTED
        ↓
Python AI 분석 및 분석 결과 전달
        ↓
분석과 보고서 생성 완료
- 전체 실행 상태 = COMPLETED
```

## 4. Spring Boot와 Python 통신

### 작업 접수

```http
POST /internal/monitoring/jobs
```

Spring Boot가 실행 ID와 활성화된 모니터링 소스 목록을 Python에 전달한다.

Python은 작업을 백그라운드로 예약한 후 즉시 다음 응답을 반환한다.

```json
{
  "jobId": "3ed1132b-8d61-45d9-bfab-06c1ed96f202",
  "status": "ACCEPTED"
}
```

`ACCEPTED`는 작업 완료가 아니라 작업 접수와 예약이 완료되었다는 의미다.

### 수집 결과 전달

```http
POST /internal/monitoring/collection-results
```

Python은 게시글 수집이 끝나면 실행 ID, 작업 ID, 소스별 상태, 게시글과 첨부파일 정보를 Spring Boot에 전달한다.

Spring Boot는 제목과 본문을 정규화하고 해시를 비교해 `NEW_DOCUMENT`, `UPDATED_DOCUMENT`, `UNCHANGED_DOCUMENT`를 판정한다. 신규·수정 문서만 새 버전과 첨부파일 정보를 저장하며, 모든 문서는 실행별 감지 결과를 남긴다.

하나 이상의 소스 수집 결과가 정상 저장되면 전체 실행 상태를 `COLLECTED`로 변경한다. 모든 소스가 실패한 경우에는 `FAILED`로 변경한다. 수집 완료 시점에는 전체 처리가 끝난 것이 아니므로 `COMPLETED_AT`을 기록하지 않는다.

응답에는 저장된 문서 ID, 버전 ID, 변경 유형과 AI 분석 필요 여부를 포함한다.

### AI 분석 결과 전달

```http
POST /internal/monitoring/analysis-results
```

Python AI 에이전트는 분석이 필요한 문서의 분석 결과를 Spring Boot에 전달한다. 이 API는 AI 분석 단계에서 구현한다.

## 5. 실행 상태

### 전체 실행 상태

- `REQUESTED`: Spring Boot가 실행 이력을 생성함
- `ACCEPTED`: Python이 작업을 접수함
- `RUNNING`: Python이 문서를 수집하고 처리 중임
- `COLLECTED`: 수집 결과의 DB 저장까지 완료되었으며 AI 분석을 기다리는 상태
- `COMPLETED`: AI 분석과 보고서 생성을 포함한 전체 작업이 정상적으로 완료됨
- `FAILED`: 작업이 실패함

### 소스별 실행 상태

- `PENDING`
- `RUNNING`
- `COMPLETED`
- `FAILED`

### 실행 방식

- `MANUAL`: 관리자 수동 실행
- `SCHEDULED`: 자동 스케줄 실행

## 6. 문서 수집

Python은 Playwright를 사용하여 다음 정보를 수집한다.

- 게시글 제목
- 본문
- 게시일
- 원문 URL
- 원본 게시글 번호
- 첨부파일 이름과 다운로드 URL

목록 페이지에서 상세 게시글 링크를 찾은 후 `urlIncludePattern`으로 필요한 URL만 선택한다.

현재 우선 지원 기관은 다음과 같다.

- 산업통상부
- 과학기술정보통신부
- 기후에너지환경부
- 고용노동부
- 국토교통부

기관 사이트의 화면 구조가 변경되면 해당 기관의 수집 프로필과 테스트도 함께 수정한다.

한 기관의 수집이 실패하더라도 다른 기관의 수집은 계속 진행한다.

## 7. 문서 저장 구조

### `DOCUMENTS`

게시글의 고유 정보와 마지막 확인 상태를 저장한다.

- 하나의 모니터링 소스는 여러 문서를 가진다.
- 같은 소스와 원문 URL 조합은 중복 저장하지 않는다.
- 최초 발견 시각과 마지막 발견 시각을 관리한다.

### `DOCUMENT_VERSIONS`

게시글의 제목, 본문, 게시일 또는 첨부파일 구성이 변경될 때마다 새로운 버전을 저장한다.

- 하나의 문서는 여러 버전을 가질 수 있다.
- 문서별 버전 번호는 중복되지 않는다.
- 제목·본문·첨부파일 정보를 이용한 해시로 변경 여부를 판단한다.

### `DOCUMENT_ATTACHMENTS`

문서 버전에 포함된 첨부파일과 텍스트 추출 결과를 저장한다.

파싱 상태는 다음과 같다.

- `PENDING`
- `PARSING`
- `COMPLETED`
- `FAILED`
- `UNSUPPORTED`

### `DOCUMENT_DETECTIONS`

특정 실행에서 어떤 문서와 버전이 감지되었는지 저장한다.

변경 유형은 다음과 같다.

- `NEW_DOCUMENT`
- `UPDATED_DOCUMENT`
- `UNCHANGED_DOCUMENT`

### 관계

```text
MONITORING_SOURCES 1 ── N DOCUMENTS
DOCUMENTS 1 ── N DOCUMENT_VERSIONS
DOCUMENT_VERSIONS 1 ── N DOCUMENT_ATTACHMENTS

MONITORING_RUN_SOURCES 1 ── N DOCUMENT_DETECTIONS
DOCUMENTS 1 ── N DOCUMENT_DETECTIONS
DOCUMENT_VERSIONS 1 ── N DOCUMENT_DETECTIONS
```

JPA 연관관계는 자식 엔티티의 `LAZY @ManyToOne`을 기준으로 단방향 매핑한다.

## 8. 변경 감지

- 처음 발견한 게시글은 `NEW_DOCUMENT`
- 기존 게시글의 내용이나 첨부파일이 변경되면 `UPDATED_DOCUMENT`
- 이전 버전과 같으면 `UNCHANGED_DOCUMENT`

제목, 정규화한 본문과 첨부파일 구성을 이용해 해시를 생성한다.

변경되지 않은 문서는 다시 분석하지 않고 마지막 확인 시각만 갱신한다.

## 9. AI 에이전트

문서 수집과 변경 감지는 일반 Python 코드가 담당한다.

AI 에이전트는 신규 또는 변경된 문서를 대상으로 다음 작업을 수행한다.

- 문서 요약
- 핵심 내용 추출
- 중요도 판정
- 판단 근거 생성
- 필요한 분석 도구 선택 및 호출

초기 도구는 다음과 같이 구성한다.

- 게시글 본문 조회
- 첨부파일 추출 텍스트 조회
- 이전 버전 비교
- 관련 문서 조회

AI의 내부 추론 과정은 저장하지 않는다. 최종 분석 결과와 사용한 도구만 저장한다.

## 10. 운영 원칙

- 비밀번호, JWT, API 키를 Git에 저장하지 않는다.
- Python 서버는 로컬 개발 중 `127.0.0.1`에 바인딩한다.
- 게시글 본문 전체와 민감정보를 로그에 남기지 않는다.
- 외부 요청에는 연결 및 응답 타임아웃을 적용한다.
- 대상 사이트의 이용정책과 `robots.txt`를 확인한다.
- 현재는 프로세스 내부 백그라운드 작업을 사용한다.
- Redis, Celery와 메시지 브로커는 필요성이 생기면 도입한다.
## 첨부파일 임시 다운로드와 메타데이터

- Python은 게시글 수집 후 첨부파일을 운영체제 임시 폴더에 스트리밍 방식으로 다운로드한다.
- 다운로드 과정에서 `CONTENT_TYPE`, `FILE_SIZE`, SHA-256 기반 `FILE_HASH`를 계산한다.
- 임시 파일은 메타데이터 계산 직후 삭제하며 원본 파일 자체는 Oracle에 저장하지 않는다.
- 다운로드 성공 상태는 텍스트 추출 전이므로 `PARSE_STATUS=PENDING`으로 유지한다.
- 다운로드 실패는 첨부파일 단위로 `PARSE_STATUS=FAILED`와 안전한 오류 메시지를 저장하고, 다른 첨부파일과 문서 처리는 계속한다.
- 제한 시간과 최대 파일 크기는 환경변수로 관리한다.

## 원본 게시글 식별자

- 기관별 게시글 ID 쿼리 키와 경로 패턴은 제목·본문·게시일 선택자와 함께 `SiteProfile`에서 관리한다.
- 기관 프로필의 쿼리 키를 우선 확인하고, 설정된 경로 패턴이 있으면 경로의 ID를 확인한다.
- `read.do`, `view.do`, `DTL.jsp` 같은 페이지 파일명은 식별자로 저장하지 않는다.
- 유효한 식별자를 찾지 못하면 `NULL`로 저장한다.
