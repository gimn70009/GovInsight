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

## 수정 공고의 비교 입력

내부 분석 요청의 `documents[].previousVersion.attachments`에 직전 버전의 첨부 목록을 전달합니다. 각 항목은 현재 첨부와 같은 `attachmentId`, `fileName`, `extractedText` 형식이며, 파싱 완료 상태의 본문만 포함하고 읽기 실패는 `extractedText: null`로 전달합니다.

- `attachments: []`는 직전 버전에 첨부가 없었다는 뜻입니다.
- 필드 미전달 또는 `null`은 이전 목록을 알 수 없다는 뜻으로 AI가 구형 요청도 수용합니다.
- 파일명이 같은 첨부의 추출 본문을 비교하고 추가·삭제·중복 이름·비교 불가를 구분합니다. 변경 근거와 비교 한계를 기존 분석 입력에 포함하며 화면 응답 형식은 유지합니다.

상세 비교 범위와 제한은 [설계 문서](../docs/DESIGN.md)의 ‘수정 공고의 설명’을 참고합니다.

## 상세 응답의 신청 마감일

감지 상세 응답에 `analysis.applicationDeadline` 문자열 또는 `null`을 포함합니다. 저장된 `comparisonSummary.applicationDeadline`을 전달하며 기존 데이터에 날짜가 없거나 비교 요약을 읽을 수 없으면 `null`입니다. 프론트는 이 날짜로 접수 종료 여부를 판단하며 일반 요약의 종료 관련 문구를 사용하지 않습니다. DB 스키마 변경은 없습니다.

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
