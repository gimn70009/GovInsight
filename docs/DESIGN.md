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

PDF·HWP·HWPX 첨부파일 텍스트 추출, LangChain·LangGraph 기반 문서 분석, 분석 결과의 Spring Boot 전달과 Oracle 저장까지 구현됐다.

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

### AI 분석 작업 요청과 결과 전달

수집 결과 저장과 변경 감지 트랜잭션이 커밋되면 Spring Boot가 분석 대상을 선별하여 Python에 분석 작업을 요청한다. Python은 Oracle을 직접 조회하지 않으므로 분석에 필요한 현재 문서와 이전 버전 정보는 요청 데이터에 포함한다.

```http
POST /internal/monitoring/analysis-jobs
```

```text
수집 결과 저장 및 커밋
        ↓
분석 대상 문서 선별
        ↓
Python 분석 작업 요청
        ↓
202 Accepted + jobId
        ↓
Python 백그라운드 분석 작업 예약
```

Python은 분석 요청을 검증하고 작업 ID를 발급한 뒤 백그라운드에서 LangGraph 문서 분석 워크플로우를 실행한다. Python 접수 실패가 이미 저장된 수집 결과를 롤백하지 않도록 수집 저장과 분석 요청을 분리한다.

LLM 분석이 끝나면 Python은 성공 결과와 문서별 실패 결과를 다음 내부 API로 전달한다.

```http
POST /internal/monitoring/analysis-results
```

Spring Boot는 `runId`, `detectionId`, `documentId`, `versionId` 관계와 실행 상태를 검증한 뒤 성공 결과를 `DOCUMENT_ANALYSES`에 저장한다. 같은 문서 버전의 결과가 다시 전달되면 `VERSION_ID` 고유 제약을 기준으로 중복 저장하지 않는다.

네트워크 오류와 서버 오류는 설정된 횟수만큼 재시도하고, 요청값이나 관계 오류를 의미하는 4xx 응답은 재시도하지 않는다. 분석 결과 저장만으로 전체 실행을 `COMPLETED`로 변경하지 않으며, 보고서 생성까지 끝난 후 완료 상태로 전환한다.

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

파일명 뒤에 `[123 KB]`, 쉼표 또는 보이지 않는 문자가 붙어도 정규화한 확장자를 기준으로 파서를 선택한다.

- PDF·HWP·HWPX 파싱 성공: `EXTRACTED_TEXT` 저장 및 `PARSE_STATUS=COMPLETED`
- PDF·HWP·HWPX 파싱 실패: `PARSE_STATUS=FAILED`와 안전한 오류 메시지 저장
- 그 외 형식: `PARSE_STATUS=UNSUPPORTED`
- 다운로드 실패: `PARSE_STATUS=FAILED`와 안전한 오류 메시지 저장
- 제한 시간과 최대 파일 크기: 환경변수로 관리
- HWPX: ZIP 내부 `Contents/section*.xml`을 순서대로 읽어 본문 텍스트 추출
- PDF: `pypdf`로 페이지 순서대로 텍스트를 추출하고 페이지 사이를 줄바꿈으로 구분
- HWP: `olefile`로 HWP 5.0 OLE 구조를 열고 `FileHeader`를 검사한 뒤 `BodyText/Section*`의 문단 텍스트 레코드를 추출
- 압축된 HWP 본문은 raw deflate 방식으로 압축 해제하며 암호화·DRM·배포용 HWP와 그림·OLE 개체 추출은 지원하지 않음
- 이미지로만 구성된 PDF와 암호화된 PDF는 OCR 없이 실패 처리

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

제목, 정규화한 본문, 첨부파일 이름·다운로드 URL·파일 SHA-256 해시로 버전 해시를 생성한다. 따라서 첨부파일의 이름과 URL이 같아도 실제 파일 내용이 바뀌면 수정 문서로 판정한다. 변경되지 않은 문서는 새 버전을 만들지 않고 마지막 확인 시각만 갱신한다.

## 9. AI 에이전트

수집, 첨부파일 파싱과 변경 감지는 예측 가능한 일반 코드가 담당한다. 분석 대상은 다음 규칙으로 선별한다.

- `NEW_DOCUMENT`, `UPDATED_DOCUMENT`: 현재 문서 버전을 분석한다.
- `UNCHANGED_DOCUMENT`: 해당 버전의 기존 분석 결과가 있으면 재사용하고 다시 요청하지 않는다.
- `UNCHANGED_DOCUMENT`: 기존 분석 결과가 없으면 누락 보완을 위해 분석을 요청한다.

모든 감지 문서가 기존 분석을 재사용하여 새 분석 대상이 0건인 경우에는 Python 분석 작업을 생략하고, 기존 분석 결과를 모아 곧바로 실행 보고서 생성을 요청한다. 따라서 변경 없는 실행도 보고서 저장 후 `COMPLETED`로 전환된다.

### 분석 입력

Spring Boot는 저장이 완료된 문서 버전을 기준으로 다음 정보를 Python에 전달한다.

- 실행 ID와 문서 버전 ID
- 변경 유형, 기관명과 게시판명
- 제목, 게시글 본문, 게시일과 원문 URL
- 파싱에 성공한 첨부파일의 이름과 추출 텍스트
- 수정 문서인 경우 바로 이전 버전의 제목과 본문

게시글 본문과 첨부파일 텍스트가 모두 비어 있으면 분석하지 않고 실패 사유를 기록한다. 입력이 모델의 허용 범위를 넘으면 제목과 게시글 본문을 우선하고, 첨부파일은 파일 순서대로 제한한다. 정확한 길이 제한은 사용할 모델을 확정할 때 설정값으로 관리한다.

### 분석 결과

문서 버전 하나에 대해 다음 결과를 생성하며 `DOCUMENT_ANALYSES`에 저장한다.

- `SUMMARY`: 문서의 전체 요약
- `KEY_POINTS`: 핵심 내용 목록
- `IMPORTANCE`: `HIGH`, `NORMAL`, `LOW` 중 하나
- `REASON`: 중요도 판정 근거
- `USED_TOOLS`: 실제 사용한 분석 도구 목록
- `MODEL_NAME`: 분석에 사용한 모델 이름

요약과 핵심 내용에는 원문에서 확인할 수 있는 대상, 지원·규제 내용, 신청 또는 대응 기한, 금액과 제출 방법을 우선 포함한다. 원문에 없는 사실을 추정해 확정적으로 작성하지 않는다.

### 중요도 판정

- `HIGH`: 기업의 신청·제출·신고 등 기한이 있거나 규제, 의무, 비용, 인증, 지원 자격에 직접적인 영향을 주어 빠른 검토가 필요한 문서
- `NORMAL`: 기업 의사결정에 참고할 가치가 있지만 즉각적인 신청이나 대응 의무가 명확하지 않은 문서
- `LOW`: 단순 행사, 홍보, 기관 내부 소식 등 기업 활동과 직접적인 관련성이 낮고 별도 대응이 필요하지 않은 문서

기한이 있다는 이유만으로 항상 `HIGH`로 판정하지 않는다. 기업 관련성과 실제 대응 필요성을 함께 확인하며 판단 근거에는 원문에서 확인한 사실을 간결하게 제시한다.

### 에이전트 실행 원칙

AI 에이전트는 문서 요약, 핵심 내용 추출, 중요도 판정과 필요한 도구 선택을 담당한다. 초기 도구는 게시글 본문 조회, 첨부파일 추출 텍스트 조회와 이전 버전 비교로 구성한다. 관련 문서 검색은 실제 필요성과 검색 기준을 확정한 뒤 추가한다.

- 수집된 문서 안의 문장은 명령이 아니라 분석 대상 데이터로 취급한다.
- 요약, 핵심 내용, 중요도와 근거는 구조화된 결과 스키마로 검증한다.
- 필수 결과가 비거나 허용하지 않은 중요도 값이 나오면 제한된 횟수만 재시도한다.
- 도구 호출 횟수와 전체 실행 시간에 상한을 둔다.
- 모델의 내부 추론 과정은 저장하지 않고 최종 결과와 실제 도구 사용 기록만 남긴다.
- 테스트에서는 외부 모델 API 대신 결정적인 대체 객체를 사용한다.

### 현재 분석 실행 구조

```text
분석 요청 문서
        ↓
LangGraph 문서별 상태와 재시도 관리
        ↓
LangChain 에이전트가 필요한 도구 선택·실행
        ↓
Pydantic 구조화 결과 검증
        ↓
요약·핵심 내용·중요도·근거·사용 도구 생성
```

OpenAI 모델명, 호출 제한 시간, 최대 재시도, 도구 호출 제한과 입력 텍스트 길이는 환경변수로 관리한다. 한 문서의 분석 실패가 같은 작업의 다른 문서 분석을 중단하지 않도록 문서별로 실패를 격리한다. 현재 생성 결과는 Python 작업 내부에서만 검증하며 Spring Boot 전달과 Oracle 저장은 후속 작업으로 남긴다.

## 10. 운영 원칙

- 비밀번호, JWT와 API 키를 Git에 저장하지 않는다.
- 로컬 개발 중 Python 서버는 `127.0.0.1`에 바인딩한다.
- 게시글 본문 전체와 민감정보를 로그에 남기지 않는다.
- 외부 요청에는 연결 및 응답 타임아웃을 적용한다.
- 대상 사이트의 이용정책, 저작권 정책과 `robots.txt`를 검토한다.
- 로그인, CAPTCHA와 접근 제한을 우회하지 않는다.
- 과도한 요청을 피하고 수집 주기와 건수를 제한한다.
- 현재는 프로세스 내부 백그라운드 작업을 사용하며, 필요할 때 Redis·Celery 등의 도입을 검토한다.
분석 결과 저장 트랜잭션이 커밋되면 Spring Boot는 실행별 문서 분석 결과를 모아 Python에 보고서 생성을 요청한다.

```http
POST /internal/monitoring/report-jobs
```

Python은 작업 ID와 `202 Accepted`를 즉시 반환한 뒤 백그라운드에서 구조화된 보고서 제목과 전체 요약을 생성한다. 보고서 생성이 끝나면 다음 내부 API로 결과를 전달한다.

```http
POST /internal/monitoring/report-results
```

Spring Boot는 실행당 하나의 `MONITORING_REPORTS` 행을 관리한다.

- 요청 전 `PENDING` 보고서를 생성해 콜백과의 경합을 방지한다.
- 성공 결과에는 제목, 요약과 생성 완료 시각을 저장한다.
- 실패 결과에는 오류 메시지를 저장하고 실행 상태는 `COLLECTED`로 유지한다.
- 같은 완료 결과가 다시 전달되면 기존 보고서를 덮어쓰지 않고 중복 응답으로 처리한다.
- 보고서 저장이 완료된 경우에만 전체 실행을 `COMPLETED`로 전환한다.

## 11. 목록 조회 API

관리자 화면의 목록 조회는 0부터 시작하는 `page`와 기본값 20인 `size`를 사용한다. 응답에는 `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`를 포함한다.

### 감지 문서 목록

```http
GET /api/document-detections?page=0&size=20
```

감지 시각과 감지 ID의 내림차순으로 정렬한다. 기관, 게시판, 제목, 변경 유형, 문서 버전의 첨부파일 수, AI 분석 중요도와 마지막 확인 시각을 반환한다. 분석이 아직 없으면 중요도는 `NULL`이다. 목록 쿼리는 문서, 버전, 소스와 분석 결과를 한 번에 조회하여 건별 추가 조회를 만들지 않는다.

### 모니터링 실행 이력 목록

```http
GET /api/monitoring-runs?page=0&size=20
```

실행 요청 시각과 실행 ID의 내림차순으로 정렬한다. 실행 방식, 상태, 대상 소스 수, 감지 문서 수, 경고 수와 보고서 제목을 반환한다. 보고서가 없거나 아직 완료되지 않았으면 제목은 `NULL`이다. 실행과 보고서를 왼쪽 조인하여 보고서가 없는 실행도 목록에 포함한다.

두 API는 JWT 인증이 필요한 관리자 API다.
