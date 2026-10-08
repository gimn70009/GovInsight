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

## 상세 응답의 신청 마감일

감지 상세 응답에 `analysis.applicationDeadline` 문자열 또는 `null`을 포함합니다. 저장된 `comparisonSummary.applicationDeadline`을 전달하며 기존 데이터에 날짜가 없거나 비교 요약을 읽을 수 없으면 `null`입니다. 프론트는 이 날짜로 접수 종료 여부를 판단하며 일반 요약의 종료 관련 문구를 사용하지 않습니다. DB 스키마 변경은 없습니다.

## 감지 문서·북마크 목록 검색

`GET /api/document-detections`와 `GET /api/bookmarks/documents`는 선택 쿼리 매개변수 `query`(기관·게시판·제목 부분 일치, 최대 500자)와 `priority`(`HIGH`, `NORMAL`, `LOW`)를 받습니다. 생략하면 해당 필터를 적용하지 않습니다. 검색어 앞뒤 공백과 대소문자는 무시하고 SQL 와일드카드 문자는 일반 문자로 처리합니다.

선택한 실행·기간 또는 내 북마크 범위 전체에 조건을 적용한 뒤 `page`·`size`로 나누며 건수도 필터 결과 기준입니다. 북마크는 전체 실행 중 내가 저장한 버전의 최신 감지 결과를 한 번씩 표시하는 기존 범위를 유지합니다. 우선순위는 기존 세부 점수 계산을 사용하며, 선택 시 후보 전체를 200건씩 검사하므로 대량 데이터에서는 조회 시간이 증가할 수 있습니다. DB 스키마 변경과 기존 분석의 재생성은 필요하지 않습니다.

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
