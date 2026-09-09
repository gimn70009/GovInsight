# GovInsight Backend

GovInsight의 인증, 모니터링 실행, 문서 저장과 조회를 담당하는 Spring Boot 애플리케이션이다.

## 실행

로컬 Oracle과 `application.properties` 설정을 준비한 뒤 `backend/`에서 실행한다.

```powershell
.\gradlew.bat bootRun
```

## Swagger UI

Spring Boot 실행 후 다음 주소에서 현재 구현된 API 문서를 확인하고 직접 호출할 수 있다.

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI 원본 문서는 다음 주소에서 확인한다.

| 문서 | 주소 |
|---|---|
| 전체 OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| 공개 API 그룹 | `http://localhost:8080/v3/api-docs/public-api` |
| 내부 API 그룹 | `http://localhost:8080/v3/api-docs/internal-api` |
| OpenAPI YAML | `http://localhost:8080/v3/api-docs.yaml` |

Swagger UI에서는 다음 두 그룹을 선택할 수 있다.

- `Public API`: 프론트엔드와 관리자가 사용하는 `/api/**`
- `Internal API`: Spring Boot와 Python 사이에서 사용하는 `/internal/**`

아직 구현되지 않은 API는 문서에 표시하지 않는다.

## Swagger에서 JWT 사용

1. `Auth`의 `POST /api/auth/login`을 실행한다.
2. 응답의 `data.accessToken` 값을 복사한다.
3. Swagger UI 상단의 `Authorize` 버튼을 누른다.
4. 입력창에 `Bearer` 접두어 없이 액세스 토큰만 입력한다.
5. `Monitoring Sources`, `Monitoring Runs` API를 실행한다.

Swagger UI는 요청을 보낼 때 다음 헤더를 자동으로 생성한다.

```http
Authorization: Bearer {accessToken}
```

Swagger UI와 OpenAPI 문서 경로만 인증 없이 접근할 수 있다. 기존 비즈니스 API의 JWT 인증 규칙은 그대로 유지한다.

## 테스트

```powershell
.\gradlew.bat test
```

OpenAPI 통합 테스트에서는 다음 항목을 확인한다.

- 공개·내부 API 그룹에 현재 엔드포인트가 포함되는지
- JWT Bearer 인증 스키마가 생성되는지
- Swagger UI와 OpenAPI JSON에 인증 없이 접근할 수 있는지
- 기존 보호 API가 토큰 없이 호출되지 않는지


## 첨부 양식 선택형 초안 API

- `GET /api/document-detections/{detectionId}/proposal-sources`: 현재 감지 버전의 첨부와 ZIP 내부 문서, 사용 가능 여부와 사유, 동일 본문의 파일명 목록(`relatedFileNames`)을 조회한다. 미지원·실패 파일도 목록에 포함한다.
- `POST /api/document-detections/{detectionId}/proposal-draft`: `{attachmentId, partIndex}`로 선택한 양식의 핵심 항목 최대 4개를 생성한다. 저장된 결과가 있으면 모델 호출 없이 반환하며 새 완료 결과는 계정별 DB에 저장한다.
- `GET /api/document-detections/{detectionId}/proposal-drafts`: 본인 계정의 같은 문서 버전에서 작성한 초안을 최근 열람 순서로 조회한다.
- `GET /api/document-detections/{detectionId}/proposal-drafts/state`: `{drafts, running}`으로 저장된 초안과 본인 계정·현재 문서 버전의 생성 중인 양식 목록을 함께 조회한다. 재진입·새로고침 후에도 생성 중 잠금을 복원하며 완료 결과를 자동 표시한다. 상태 조회에는 AI 호출이 없다.
- 동일 계정·첨부·내부 순번의 동시 생성은 같은 결과를 기다린다. 화면을 나가도 생성 요청을 취소하지 않으며 진행 표시를 실제 생성·저장 종료까지 유지한다. 진행 상태는 Spring 프로세스 메모리, 완료 결과는 DB에 보관한다. 서버 재시작은 미완료 진행 상태를 초기화하며 새 테이블이나 라이브러리는 필요하지 않다.
- `PUT /api/document-detections/{detectionId}/proposal-drafts/last-viewed`: `{attachmentId, partIndex}`로 마지막으로 본 완료 초안을 기억한다. 모든 초안 API는 JWT 인증이 필요하다.
- 기존 Oracle에는 `docs/migrations/20260909_saved_proposal_drafts.sql`을 한 번 적용하고 DB 초기화 없이 시작한다. 생성 전의 임시 메모리 결과는 자동 이관하지 않는다.
- Spring은 첨부 소속과 본문을 검증하고 DB 트랜잭션을 종료한 뒤 Python의 `/internal/monitoring/proposal-write`를 호출한다. 응답 대기는 최대 190초이며 실패는 안전한 안내로 반환한다.
- 완료 결과는 `DOCUMENT_PROPOSAL_DRAFTS`에 본문·근거·확인 사항과 함께 보관한다. 상세 응답 계약과 제한은 `docs/DESIGN.md`에 기록한다.


## 텔레그램 관리 페이지 적용

여러 수신자의 채팅 ID·이름·개별 수신 여부와 전체 발송 여부는 관리자 화면에서 저장하며, 봇 토큰은 기존 TELEGRAM_BOT_TOKEN 서버 환경변수로 관리한다. 최초 저장 전에는 기존 TELEGRAM_ENABLED·TELEGRAM_CHAT_ID 설정을 사용하며, TELEGRAM_RECIPIENT_NAME(app.telegram.recipient-name)에 기본 수신자의 표시 이름을 지정할 수 있다. 개인 이름은 Git 제외된 로컬 설정이나 환경변수에만 둔다. 저장한 이름을 기본값으로 덮어쓰지 않는다. 전체 발송 스위치와 각 수신자의 스위치는 독립적으로 저장하며, 전체 발송이 켜져 있어도 수신을 끈 사람에게는 보내지 않는다. 새 라이브러리 설치는 필요하지 않다.

기존 데이터를 보존할 때는 백엔드를 시작하기 전에 [Oracle 마이그레이션](../docs/migrations/20260909_telegram_management.sql)을 해당 스키마에 한 번 적용하고, 이어서 [다중 수신자 마이그레이션](../docs/migrations/20260909_telegram_multi_recipient.sql)을 한 번 적용한다. 새 설정 테이블과 보고서 발송 정보 열만 추가하며 이전 발송 기록을 삭제하지 않는다. 적용 후에는 스키마를 재생성하지 않도록 다음과 같이 시작한다.

~~~powershell
.\gradlew.bat bootRun --args="--spring.jpa.hibernate.ddl-auto=none"
~~~

현재 Oracle의 기존 NUMBER 기본값 열은 Hibernate validate에서 타입 불일치로 판정될 수 있으므로, 명시적인 SQL 적용 후에는 자동 DDL을 실행하지 않는 none을 사용한다.

다중 수신자 마이그레이션에는 기존 수신자와 발송 기록을 새 테이블로 옮기는 INSERT가 있으므로 Hibernate update만으로 대체하지 않는다. 데이터가 있는 환경에서 create 또는 create-drop으로 재시작하지 않는다.

페이지 주소는 /telegram이다. 발송 내역은 보고서별로 묶고 상세에서 수신자별 마지막 결과를 표시하며, 실제 외부 응답이 불명확한 실패는 Telegram 채팅을 확인한 뒤 재전송한다. 관리 페이지 테스트는 외부 Telegram API를 대체하며 실제 메시지를 보내지 않는다.

## 시작 버튼의 채팅 ID 답장

봇을 사용하는 서버 한 대의 application.properties에 app.telegram.commands.enabled=true를 추가하면 개인 채팅의 새 /start 메시지에 숫자 채팅 ID를 답장한다. 예제 설정에서는 TELEGRAM_COMMANDS_ENABLED=true로 켤 수 있고 기본값은 꺼짐이다. 봇 토큰은 기존 설정을 사용하며 app.telegram.read-timeout은 5초보다 길게 둔다(기본 10초). 라이브러리 설치나 DB 변경은 없다.

받는 사람이 봇에서 시작(Start)을 누르거나 /start를 보낸 뒤 답장받은 ID를 담당자에게 전달한다. 담당자가 관리 페이지의 수신자 추가에 등록해야 보고서를 받는다. 서버가 꺼져 있을 때 보낸 이전 명령에는 재시작 후 답장하지 않으므로 /start를 다시 보내야 한다. 보고서 자동 발송을 꺼도 시작 답장은 동작한다.

수신은 getUpdates 긴 폴링을 사용한다. 다른 프로그램의 getUpdates와 동시에 실행하지 않는다. 기존 웹훅이 있거나 HTTP 409 충돌이 발생하면 수신을 중지하고 로그에 안내하며 웹훅을 변경하지 않는다. 원인을 해결한 뒤 서버를 재시작한다. 정상 수신 로그는 Telegram /start is listening for new private messages.이며, 답장 성공은 updateId만 기록한다. 봇 토큰과 채팅 ID·메시지 원문은 로그에 기록하지 않는다.

자동 테스트는 외부 Telegram 수신을 끄고 HTTP·메시지 응답을 모의 객체로 검증한다.

### ZIP 내부 파일 상태 저장

기존 DB에는 [ZIP 목록 열 추가 SQL](../docs/migrations/20260909_zip_entry_metadata.sql)을 한 번 적용한다. 수집 요청의 `archiveEntriesJson`을 `DOCUMENT_ATTACHMENTS.ARCHIVE_ENTRIES_JSON` CLOB에 보관하며 본문과 분리한다. 같은 버전의 재수집은 기존 파일의 내부 순번을 유지하고 새 문서만 뒤에 추가해 저장 초안의 연결을 보존한다. 이전 목록 없는 데이터도 계속 조회할 수 있으며, 다음 수집부터 미지원·실패 파일 목록까지 표시한다. DB 초기화나 기존 초안 재생성이 필요하지 않다.
