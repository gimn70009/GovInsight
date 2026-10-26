# 백엔드 실행 안내

Spring Boot가 인증, 모니터링 실행, Oracle 저장·조회, Telegram 발송을 담당합니다. 수집·AI 작업은 Python에 요청합니다.

[프로젝트 소개](../README.md) · [전체 설계](../docs/DESIGN.md)

## 준비

Java 21과 Oracle이 필요합니다. 수집·분석까지 실행하려면 [AI 모듈](../ai/README.md)도 켜야 합니다. 아래 명령은 `backend` 폴더에서 실행합니다.

설정 파일이 없는 경우에만 예시를 복사합니다.

```powershell
if (!(Test-Path src/main/resources/application.properties)) {
    Copy-Item src/main/resources/application.properties.example src/main/resources/application.properties
}
```

환경변수 또는 복사한 로컬 설정에 다음 값을 넣습니다. 로컬 설정·비밀번호·토큰은 커밋하지 않습니다.

| 설정 | 필요한 값 |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 사용할 Oracle 접속 정보 |
| `JWT_SECRET` | 충분히 긴 무작위 서명 키(최소 32바이트) |
| `LOCAL_ADMIN_ENABLED` | 첫 관리자 생성 시 `true`, 기본 `false` |
| `LOCAL_ADMIN_LOGIN_ID`, `LOCAL_ADMIN_PASSWORD` | 처음 생성할 관리자의 로그인 정보 |
| `PYTHON_MONITORING_BASE_URL` | AI 모듈 주소, 기본 `http://localhost:8000` |

관리자는 활성화한 경우에만, 해당 계정이 없을 때 생성됩니다.

## 실행

개발 DB를 처음부터 다시 준비할 때는 `create`로 현재 엔티티 기준의 테이블과 시퀀스를 생성합니다. 기존 데이터는 초기화되며 별도 SQL 파일은 필요하지 않습니다.

```powershell
.\gradlew.bat bootRun --args="--spring.jpa.hibernate.ddl-auto=create"
```

스키마를 생성한 뒤 데이터를 유지하며 다시 실행할 때는 자동 DDL을 끕니다. 초기화 여부에 맞는 명령 하나를 사용합니다.

```powershell
.\gradlew.bat bootRun --args="--spring.jpa.hibernate.ddl-auto=none"
```

`none`은 테이블을 생성하거나 변경하지 않으므로 현재 엔티티에 맞는 스키마가 준비되어 있어야 합니다.

## 자동 예약의 지연 허용과 대기

예약 전용 스케줄러가 한국 시간 기준 5초마다 확인합니다. 예약 시각을 놓쳐도 기본 5분 안의 미접수 회차는 대기로 등록하며, 자정을 넘겨 확인해도 원래 예약일로 기록합니다.

| Spring 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `app.monitoring.schedule.catch-up-window` | `PT5M` | 최초 대기 등록의 지연 허용 시간. 0보다 크고 24시간 미만이며 종료 경계를 포함 |

예시 설정의 `MONITORING_SCHEDULE_CATCH_UP_WINDOW` 환경변수로 조정할 수 있습니다. 예: `PT10M`. 설정이 없는 기존 설치에는 기본값을 적용합니다.

진행 중인 수동·예약 모니터링이 있으면 대기 한 건을 DB에 유지하고, 완료 또는 실패 후 다음 확인 주기에 실행합니다. **등록된 대기는 5분이 지나거나 서버를 재시작해도 만료되지 않습니다.** 여러 예약 회차가 겹쳐도 한 건으로 합쳐 누적 실행을 방지합니다. 활성 소스가 없으면 소스 활성화까지 기다립니다.

대기 해제와 새 실행 생성은 같은 트랜잭션에 참여하며 기존 Python 접수 HTTP 요청 동안 소스·설정 잠금을 유지합니다. Python 접수 실패는 FAILED 이력으로 남기고 그 대기는 해제합니다. 외부 접수 후 DB 저장 실패에 대한 정확히 한 번 실행 보장은 추가하지 않습니다.

대기 중 수동 실행은 409(`MONITORING_RUN_409_2`)를 반환합니다. 수동 실행을 우선하려면 화면에서 대기를 취소합니다. 자동 실행을 끄거나 저장된 일정을 바꾸면 기존 대기도 취소하며, 같은 설정을 다시 저장하면 유지합니다.

| 메서드·경로 | 내용 |
| --- | --- |
| `GET /api/monitoring-schedule` | 기존 일정과 `pendingScheduledAt`(한국 시간 ISO 시각 또는 null) 조회 |
| `PUT /api/monitoring-schedule` | 기존 요청 형식으로 일정 저장. 응답에는 대기 상태도 포함 |
| `DELETE /api/monitoring-schedule/pending?scheduledAt=2026-10-08T14:00:00` | 화면에서 확인한 대기 한 건 취소 후 갱신된 일정 반환 |

인증이 필요하며 누락·잘못된 취소 시각은 400, 이미 시작했거나 바뀐 대기 취소는 409(`MONITORING_SCHEDULE_409_1`)입니다. 대기 취소는 사용 설정과 해당 회차의 시도 기록을 유지하므로 바로 다시 등록되지 않습니다.

### DB 생성

`ddl-auto=create`로 시작하면 현재 엔티티를 기준으로 `monitoring_schedules.pending_scheduled_at` 컬럼까지 생성됩니다. 별도 SQL은 필요하지 않습니다. 실행 명령은 위의 실행 절을 따릅니다.

`create`는 기존 테이블과 데이터를 초기화합니다. 생성 후 데이터를 유지하며 다시 실행할 때는 `none` 또는 `validate`로 전환합니다. 실제 Oracle 초기화는 이번 코드 작업에서 수행하지 않았습니다.

## 수집 결과 대기 시간과 복구

수집 결과가 유실되거나 AI 프로세스가 중단되어도 다음 자동 실행을 막지 않도록, 수집 단계가 기본 1시간을 넘기면 실행·소스를 실패로 정리합니다. 시작 10초 후부터 1분마다 최대 100건을 확인하며 예약 실행 직전에도 정리합니다. 기존 데이터베이스 스키마를 그대로 사용합니다.

| Spring 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `app.monitoring.collection-recovery.enabled` | `true` | 수집 대기 시간 초과 정리 사용 |
| `app.monitoring.collection-recovery.timeout` | `PT1H` | 접수 시각(없으면 요청 시각) 기준 상한, 예: `PT2H` |
| `app.monitoring.collection-recovery.interval-ms` | `60000` | 복구 확인 간격 |
| `app.monitoring.collection-recovery.initial-delay-ms` | `10000` | 시작 후 최초 확인 대기 |

환경변수는 `APP_MONITORING_COLLECTIONRECOVERY_TIMEOUT`처럼 Spring의 환경변수 표기(점은 밑줄, 하이픈은 제거)를 사용할 수 있습니다. 정상 수집이 오래 걸리면 상한을 늘리세요. COLLECTED 이후 분석·제안 작업은 이 시간 제한 대상이 아닙니다. 만료 후 도착한 결과는 무시하며 Python 작업의 강제 종료·자동 재수집은 하지 않습니다.

`POST /internal/monitoring/collection-results`는 같은 실행의 중복 또는 만료 후 콜백에 HTTP 200과 빈 `data.documents`를 반환합니다. 잘못된 작업 ID는 기존 409 응답을 유지합니다. Python 재시도를 켜기 전에 이 백엔드 변경을 먼저 적용하세요.

## 수정 공고의 비교 입력

내부 분석 요청의 `documents[].previousVersion.attachments`에 직전 버전의 첨부 목록을 전달합니다. 각 항목은 현재 첨부와 같은 `attachmentId`, `fileName`, `extractedText` 형식이며, 파싱 완료 상태의 본문만 포함하고 읽기 실패는 `extractedText: null`로 전달합니다.

- `attachments: []`는 직전 버전에 첨부가 없었다는 뜻입니다.
- 필드 미전달 또는 `null`은 이전 목록을 알 수 없다는 뜻으로 AI가 구형 요청도 수용합니다.
- 파일명이 같은 첨부의 추출 본문을 비교하고 추가·삭제·중복 이름·비교 불가를 구분합니다. 변경 근거와 비교 한계를 기존 분석 입력에 포함하며 화면 응답 형식은 유지합니다.

상세 비교 범위와 제한은 [설계 문서](../docs/DESIGN.md)의 ‘수정 공고의 설명’을 참고합니다.

## 상세 응답의 신청 마감일과 분석 진행 상태

감지 상세 응답에 `analysis.applicationDeadline` 문자열 또는 `null`을 포함합니다. 저장된 `comparisonSummary.applicationDeadline`을 전달하며 기존 데이터에 날짜가 없거나 비교 요약을 읽을 수 없으면 `null`입니다. 프론트는 이 날짜로 접수 종료 여부를 판단하며 일반 요약의 종료 관련 문구를 사용하지 않습니다. DB 스키마 변경은 없습니다.

감지 상세 응답의 `analysisPending`은 같은 문서 버전의 **가장 최근 감지 실행**에서 분석·제안 처리가 진행될 수 있는지를 나타냅니다. 최신 실행이 완료·실패했거나 분석 작업이 `DONE`/`FAILED`이면 `false`이며, 진행 중인 실행의 분석 작업이 `PENDING`/`RUNNING`/`ANALYZED`이면 `true`입니다. 분석 작업 행이 없는 구형 실행은 실행의 진행 상태를 사용합니다. 과거 감지 상세를 열어도 같은 버전의 새 실행을 따라가며 다른 버전의 실행은 영향을 주지 않습니다.

`analysisPending`이 `true`인 동안 `analysis`가 `null`이거나 제안의 `draftStatus`가 `GENERATING`이면 상세를 다시 조회합니다. `false`이면 자동 조회를 종료하고 결과 없음 또는 제안 중단을 안내합니다. 개별 분석 실패나 분석할 본문이 없는 경우는 결과 행을 저장하지 않으므로 `null`을 실패로 단정하지 않습니다. 저장된 분석은 그대로 반환하며 새 필드는 DB 스키마·AI 요청 계약을 변경하지 않습니다.

## 감지 문서·북마크 목록 검색

`GET /api/document-detections`와 `GET /api/bookmarks/documents`는 선택 쿼리 매개변수 `query`(기관·게시판·제목 부분 일치, 최대 500자)와 `priority`(`HIGH`, `NORMAL`, `LOW`)를 받습니다. 생략하면 해당 필터를 적용하지 않습니다. 검색어 앞뒤 공백과 대소문자는 무시하고 SQL 와일드카드 문자는 일반 문자로 처리합니다.

선택한 실행·기간 또는 내 북마크 범위 전체에 조건을 적용한 뒤 DB에서 `page`·`size`로 나누며 건수도 같은 필터 기준입니다. 북마크는 전체 실행 중 내가 저장한 버전의 최신 감지 결과를 한 번씩 표시하는 기존 범위를 유지합니다. 분석 저장 시 기존 산식으로 총점과 우선순위를 함께 계산하며, 목록 조회는 저장된 값만 읽습니다. 세부 점수 CLOB와 전체 후보를 가져와 재계산하지 않습니다. API 응답 형식과 점수 판정 규칙은 유지합니다.

`document_analyses`에 `opportunity_priority`, `opportunity_ranking_version` 컬럼과 `ix_analysis_priority_score` 인덱스가 추가됩니다. 사용 중인 개발 방식대로 `ddl-auto=create`로 시작하면 엔티티 기준으로 생성되므로 별도 SQL은 필요하지 않습니다. `create`는 기존 데이터를 초기화하며, 데이터를 유지하는 DB에서는 새 컬럼·인덱스가 먼저 준비되어 있어야 합니다. `none/validate`는 스키마를 추가하지 않습니다. 이번 구현 작업에서 실제 DB를 초기화하지 않습니다.

기존 행이 있는 경우 백엔드 시작 준비 중 처리 버전이 null 또는 현재 버전(1)보다 낮은 행을 ID 순으로 200건씩 읽고, 행별 짧은 트랜잭션으로 보완합니다. 시작이 중단되어도 완료한 행은 다음 시작에서 건너뛰며, 새 분석 저장이 먼저 끝난 행은 이전 계산으로 덮어쓰지 않습니다. 저장 JSON에서 같은 기준으로 총점·우선순위를 계산하므로 예전 저장 점수와 현재 표시 점수의 불일치도 정리됩니다. 자료 누락·손상 행은 임의 분류하지 않고 기존 총점·미분류를 유지하며, 처리 완료 표시를 남겨 다음 시작 때 다시 검사하지 않습니다. 손상 자료 로그에는 분석 ID·건수만 남깁니다. 기존 보고서·발송 본문은 그대로이고 이후 새 보고서는 정규화된 점수를 사용합니다. AI 재분석이나 새로운 라이브러리는 필요하지 않습니다.

## 모니터링 중복 실행 차단

`POST /api/monitoring-runs`는 REQUESTED/ACCEPTED/RUNNING/COLLECTED 실행이 있으면 HTTP 409와 `MONITORING_RUN_409_1`을 반환합니다. 수동·예약 요청이 동시에 들어와도 DB 잠금으로 새 실행을 하나만 접수합니다. 분석·보고서 생성이 끝나 COMPLETED가 되거나 FAILED로 정리되면 다시 실행할 수 있습니다.

인증된 `GET /api/monitoring-runs/active`의 `data`는 `{ "running": true, "runId": 123, "status": "COLLECTED" }` 형태입니다. 진행 중 실행이 없으면 `running`은 false, 나머지는 null입니다. 실행 목록의 현재 페이지와 관계없이 전체에서 조회합니다. 화면에서는 표시 중 5초마다 확인하고 진행 중 또는 조회 실패 시 실행 버튼을 잠급니다.

중복 실행 차단은 그대로 유지합니다. COLLECTED 상태의 분석·제안 중단은 아래의 DB 작업 기반 복구가 처리합니다.

## API 확인과 테스트

- [Swagger UI](http://localhost:8080/swagger-ui.html): `Public API`는 화면용 API, `Internal API`는 모듈 간 통신입니다.
- `POST /api/auth/login` 응답의 `data.accessToken`을 Swagger의 **Authorize**에 입력하면 인증 API를 확인할 수 있습니다.
- 테스트는 H2와 외부 호출 대체 객체를 사용합니다. 로컬 Oracle·모델·Telegram에 의존하지 않습니다.

```powershell
.\gradlew.bat test
```

<details>
<summary>선택 기능: Telegram 설정</summary>

서버에 `TELEGRAM_BOT_TOKEN`을 설정하고 `/reports` 화면에서 수신자와 발송 여부를 관리합니다. 최초 저장 전에는 `TELEGRAM_ENABLED`·`TELEGRAM_CHAT_ID` 기본 설정을 사용합니다.

개인 채팅의 `/start`에 채팅 ID를 답장하려면 `TELEGRAM_COMMANDS_ENABLED=true`로 설정합니다. 봇당 서버 한 대에서만 켜며 다른 `getUpdates` 수신기나 웹훅과 함께 사용하지 않습니다. 받은 ID를 관리 화면에 등록해야 보고서 수신자가 됩니다.

</details>


## Gmail·네이버 이메일 보고서

새 보고서는 사업 기회에 유형·방식·금액·참여조건을 표시하고 일반 공지는 핵심 요약을 우선합니다. 점수별 색상은 유지하며 모든 공고에서 기회점수·공고 구분·변경 상태 배지를 같은 16px 글자와 여백으로 표시합니다. 공고 구분은 확인된 사업 공고/일반 공지와 분류 확인 필요를 구별하고, 모든 카드의 핵심 요약을 본문 맨 위에 둡니다. 유형과 방식은 `유형:`·`방식:` 라벨이 있는 별도 정보행으로 표시합니다. 금액은 문단으로 읽을 수 있게 표시하며, 기존 저장본의 명백한 목록 기호·빈 표제·정확한 중복을 정리합니다. 평탄화된 표 조각은 다른 명시 문장에서 금액과 기간이 모두 확인되는 경우에만 중복으로 숨기고, 예산 한정·세금·자부담 등 각주 조건은 보존합니다. 금액을 합산하거나 지원 관계를 추정하지 않습니다. 접수 링크는 접수 안내 안에만 배치하며, 같은 주소의 역할 없는 반복 링크는 제거합니다. 영문·국문 등 역할과 쿼리·경로·fragment가 다른 제출 단계는 유지합니다. 하단 원문은 `공고 원문 보기` 버튼 하나로 표시합니다. 긴 금액 조건은 문장 경계에서 나누고 `□ 사업 기간/예산` 같은 표제 장식을 정리하되 출연금·기간·조건은 유지합니다. 보고서 요청의 `comparisonSummary` 전체 JSON 전달에 `supportScale`, `requiredPartner`가 포함되며, Python은 현재 원문과 대조한 값만 새 사실 필드에 사용합니다. 신규 추출에는 AI 재시작·보고서 재생성이 필요하고, 메일 레이아웃은 백엔드 재시작 후 새 발송·재전송에 적용됩니다. 과거 본문과 이미 받은 이메일은 자동 변경되지 않습니다.

`/reports`에서 이메일 수신자를 등록하고 테스트 메일을 보낸 뒤 이메일 발송을 켭니다. 발신 서비스는 Gmail 또는 네이버 중 하나를 선택하며, 수신자는 두 서비스와 다른 유효한 이메일 주소를 함께 사용할 수 있습니다.

서버 프로세스의 환경변수로 설정합니다. `APP_EMAIL_*`는 Spring 설정에 직접 매핑되므로 기존 로컬 설정 파일에서도 동작합니다. 예시 설정 파일을 복사한 경우 `EMAIL_PROVIDER`·`EMAIL_USERNAME`·`EMAIL_PASSWORD`·`EMAIL_SENDER_NAME`도 사용할 수 있습니다. 비밀번호는 코드·문서·Git에 저장하지 않습니다.

| 환경변수 | 값 |
| --- | --- |
| `APP_EMAIL_PROVIDER` | `GMAIL`(기본) 또는 `NAVER` |
| `APP_EMAIL_USERNAME` | 전체 발신 이메일 주소. SMTP 인증 및 From 주소로 사용 |
| `APP_EMAIL_PASSWORD` | 서비스에서 발급한 앱 비밀번호 |
| `APP_EMAIL_SENDER_NAME` | 표시 이름. 기본 `GovInsight` |

- Gmail은 2단계 인증 후 [앱 비밀번호 안내](https://support.google.com/accounts/answer/185833?hl=ko)를 확인합니다. 조직 정책으로 앱 비밀번호를 사용할 수 없는 계정은 이 방식으로 연결할 수 없습니다. OAuth 로그인 연동은 이번 기능에 포함하지 않습니다.
- 네이버는 메일 환경설정에서 IMAP/SMTP 사용 설정과 계정의 앱 비밀번호 요구 사항을 확인합니다. [네이버 공식 연결 안내](https://help.naver.com/service/30029/contents/21351?osType=COMMONOS)를 따릅니다.
- [Gmail 공식 SMTP 안내](https://support.google.com/mail/answer/7104828?hl=ko) 및 네이버 공식 안내의 587/STARTTLS를 사용합니다. 인증·암호화와 서버 인증서 검증을 끌 수 없으며 서버 주소는 선택한 서비스에 고정합니다.
- 설정을 적용하려면 백엔드를 재시작합니다. 화면의 `설정됨`은 인증 정보 등록 여부이며, 실제 연결은 테스트 메일로 확인합니다. 메일 완료는 SMTP 접수를 뜻하므로 수신함·스팸함도 확인합니다.
- 새 `email_settings`, `email_recipients`, `email_deliveries` 테이블 및 이메일 발송 시퀀스가 필요합니다. 기존 `ddl-auto=none` DB에는 자동 생성되지 않습니다. 앞의 개발 DB 초기화 절은 **기존 데이터를 삭제**하므로 데이터 보존이 필요한 DB에 그대로 실행하지 마세요. 이번 작업은 로컬 DB 초기화를 수행하지 않았습니다.

### 이메일 및 통합 이력 API

모든 아래 API는 관리자 JWT를 요구합니다. 기존 `/api/telegram/**` API도 유지합니다. 실제 계약과 검증 제약은 Swagger에서 확인할 수 있습니다.

| 메서드·경로 | 내용 |
| --- | --- |
| `GET /api/email/settings` | version, enabled, configured, provider, senderAddress, senderName, recipients, updatedAt |
| `PUT /api/email/settings` | `{version, enabled, recipients: [{address, name, enabled}]}` 저장. 최대 20개, 주소 최대 254자, 이름 최대 100자 |
| `POST /api/email/test-message` | `{expectedAddress}`에 해당하는 저장된 수신자로 테스트 발송 |
| `POST /api/email/deliveries/{id}/retry` | `{expectedAddress, expectedAttemptCount}` 검증 후 실패한 한 건 재전송 |
| `GET /api/report-deliveries` | page, size(1~100), from, to, channel(ALL/TELEGRAM/EMAIL), status 필터. 보고서별 telegram/email 상태와 발송 수 |
| `GET /api/report-deliveries/{id}` | report, body, telegramDeliveries, emailDeliveries. 수신자별 발송 시각·오류·시도 횟수 |

설정 충돌·이미 처리된 재전송·변경된 수신자는 409, 중복 주소·잘못된 입력·기간은 400, 미설정 계정·발송 꺼짐은 422를 반환합니다. 발신 인증 비밀번호와 SMTP 원본 예외는 응답하지 않습니다.

## 분석·제안 중단 복구

수집 커밋 전에 실행별 분석 작업을 DB에 남깁니다. Python 중단·접수 실패·콜백 유실은 최대 3회 재시도하고, 소진되면 실행을 FAILED로 정리해 다음 실행을 허용합니다. 분석 저장 후 제안 결과가 유실된 경우도 포함합니다. 이전 시도 또는 만료된 콜백은 409이며, 완료된 동일 시도는 결과·발송을 반복하지 않습니다.

기본 복구 주기는 10초, 접수 실패 대기는 10초, 처리 임대는 문서 수 × 15분(최소 30분)입니다. `app.analysis.recovery.minimum-lease`와 `per-document-lease`로 조정합니다. 서버 중단 복구에는 모델 재실행 비용이 생길 수 있습니다.

개발 DB를 `ddl-auto=create`로 초기화하면 `AnalysisTask` 엔티티에 따라 테이블·시퀀스·인덱스가 생성되므로 별도 SQL은 필요하지 않습니다. Python·백엔드는 함께 업데이트하세요. 기존 데이터를 유지하려면 스키마 생성 후 `ddl-auto=none`으로 실행합니다. `none/validate`는 새 테이블을 생성하지 않습니다.


문의 안내는 담당별로 이름을 굵게 표시하고 전화·이메일·추가 안내를 별도 행으로 나눕니다. 담당 수를 두 곳으로 제한하지 않으며, 같은 담당 안의 중복 연락처만 정리합니다. 줄이 나뉜 기관명과 후속 번호·이메일의 관계, 내선·상담 시간·제출 조건, 문의 게시판 링크를 유지합니다. 390px에서도 일반 길이의 이메일이 불필요하게 두 줄로 갈리지 않도록 연락처 칸을 확보합니다. 기존 저장 본문의 문서 글머리표는 의미가 있는 조건을 보존하며 정리하고, 참여 역할에 반복된 예산 문장은 금액 영역에 동일한 내용이 확인될 때만 제외합니다.

- 이메일은 모든 공고에서 유형·방식 행을 표시하며 일반 공지의 해당 없음, 사업 공고의 원문 확인 필요, 기존 보고서의 분류 정보 없음을 구분합니다. 문의처의 사업명 표제·중복 담당·전화 전체 괄호·내용 없는 문의 문구를 정리하고 담당별 채널과 제한 조건을 보존합니다. 핵심 요약은 별도 항목에 확인된 금액·일정·방법의 반복을 줄이고 공고 내용과 정책 변경의 핵심 수치를 유지합니다.

## 공고 상세의 참여 조건 분류

GET /api/document-detections/{id} 응답의 participationGuide.categories는 8개 분류를 고정 순서로 제공합니다. 기존 conditions, rules, variants는 유지합니다.

- 분류: { code, label, rows }
- 세부 항목: { code, label, status, entries }. 기본 40개 항목을 항상 반환하며 대응하지 않는 명시 조건은 분류 내부의 OTHER 행에 보존합니다.
- 상태: STATED(근거가 있는 조건), NOT_STATED(확인한 원문에서 기준을 찾지 못함), REVIEW_REQUIRED(원문 미확보·파싱 실패·손상·분류 불확실).
- 조건: { rule, context, relation, relatedCriteria }. rule은 기존 ParticipationRule 구조입니다. context는 분리 전 문장, relation은 LINKED/ALTERNATIVE/null, relatedCriteria는 연결된 세부 항목명입니다. 분리된 수치뿐 아니라 적용 범위·예외·연결 관계를 함께 읽어야 합니다.
- 기업 규모·형태는 업력·매출 등 대상 구분 조건을 유지합니다. 화면은 실제 유형·범위를 첫 문장에 통합하고 표 안내 문구를 숨깁니다. 명사·수치 조각은 완결된 context 문장으로 표시합니다. 수행 요건은 본문의 불릿·절·표 경계를 복원해 의무 문장만 표시하며 같은 조건의 복수 근거는 보존하여 합칩니다.
- 화면은 LINKED 배지와 REVIEW 진단 항목을 숨기고, 미확인 빈 항목을 ‘—’로 표시합니다. ALTERNATIVE 선택 관계와 원문·예외는 유지합니다. 조건은 문법상 안전한 경계에서 분리하며 일정·접수처·제출 목록 전체를 자격으로 사용하지 않습니다.
- 저장 원문 조회 시 계산하므로 DB 마이그레이션·AI 재분석은 필요하지 않습니다. 변경된 백엔드와 프런트엔드를 함께 반영해야 합니다.

참여 조건 분류 응답의 표시문은 조회 시 공통 정제를 거칩니다. 번호·참조 표기와 법령 정의의 혼입을 제거하고 확인된 소제목·유형·수치를 포함한 완결 문장으로 표시합니다. 구조와 단위가 확인된 지원금/자부담 표만 유형별 문장으로 복원하며 불확실한 표는 근거에 유지합니다. 정제 후 동일 조건은 출처를 합치고, 저장 원문·기존 응답 구조·DB 스키마는 바꾸지 않습니다.

## 신청 자격과 제한의 LLM 재작성 (2026-10-21)

앞선 규칙 기반 참여 조건 표시 정책을 대체합니다. 상세 조회는 원문 구역을 보존해 비동기 재작성하고 `participationGuide.rewriteStatus=PENDING/READY/UNAVAILABLE`, `entry.displayStatus=VERIFIED/EVIDENCE_ONLY`를 반환합니다. `conditions/rules`는 호환 자료이며 화면 표시문으로 사용하지 않습니다. 다른 상세 영역의 생성 방식은 그대로입니다.

- Python `POST /internal/monitoring/participation-rewrite`: sources(id, excerpt, group_label, criteria), criteria(code, label, category), business_types. 반환은 statements(source_ids, criterion, text, group_label, supporting_quotes, kind), evidence_only_ids, irrelevant_source_ids, status입니다. 검증 후 조건이 아닌 구역만 irrelevant로 제외합니다.
- 원문 지문과 파이프라인 버전으로 결과를 재사용합니다. 작업자 2개·대기 32개, HTTP 읽기 제한 620초이며 같은 문서 버전의 동시 요청은 합칩니다. 실패 결과도 보관해 상세 조회마다 모델을 반복 호출하지 않습니다. 원문 변경은 새 지문으로 재처리합니다.
- 기존 DB의 `ddl-auto=none/validate` 환경에서는 `schema/participation-rewrite.sql`을 먼저 적용합니다. 로컬 `update` 설정은 백엔드 시작 시 두 nullable 캐시 컬럼을 추가합니다. 기존 문서·분석 데이터 초기화는 필요하지 않습니다. Python·백엔드·프런트엔드를 함께 반영해야 합니다.

참여 조건 재작성은 AI의 `/internal/monitoring/participation-rewrite/stream`에서 검증된 중간 결과를 받습니다. `PENDING`에도 `VERIFIED` 문장이 포함될 수 있고 상세 화면은 처리 완료까지 자동 갱신합니다. HWP/PDF의 같은 원문은 한 번 처리하되 모든 근거를 유지합니다. 후속 장애가 먼저 검증된 문장을 지우지 않으며 최종 결과만 영속 캐시에 기록합니다. 일시적인 `UNAVAILABLE`은 5분 후 재조회나 서버 재시작 시 재시도할 수 있습니다.
