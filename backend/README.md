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

새로 만든 **빈 개발용 Oracle 스키마**에서는 예시의 `update` 설정으로 테이블을 준비할 수 있습니다.

```powershell
.\gradlew.bat bootRun --args="--spring.jpa.hibernate.ddl-auto=update"
```

기존 데이터가 있는 DB는 필요한 마이그레이션 적용 후 자동 DDL을 끄고 실행합니다. 두 명령 중 DB 상태에 맞는 하나를 사용합니다.

```powershell
.\gradlew.bat bootRun --args="--spring.jpa.hibernate.ddl-auto=none"
```

기존 DB에 `create`·`create-drop`을 사용하면 데이터가 삭제될 수 있습니다. 기존 Oracle 열은 `validate`에서 타입 불일치가 날 수 있어 명시적 SQL 적용 후 `none`을 사용합니다.

## API 확인과 테스트

- [Swagger UI](http://localhost:8080/swagger-ui.html): `Public API`는 화면용 API, `Internal API`는 모듈 간 통신입니다.
- `POST /api/auth/login` 응답의 `data.accessToken`을 Swagger의 **Authorize**에 입력하면 인증 API를 확인할 수 있습니다.
- 테스트는 H2와 외부 호출 대체 객체를 사용합니다. 로컬 Oracle·모델·Telegram에 의존하지 않습니다.

```powershell
.\gradlew.bat test
```

<details>
<summary>기존 DB에서 기능을 추가할 때</summary>

현재 스키마를 확인하고 아직 적용하지 않은 SQL만 순서대로 적용합니다. 데이터 이관을 포함한 SQL은 Hibernate `update`만으로 대체할 수 없습니다.

| 기능 | 마이그레이션 |
| --- | --- |
| 북마크 | [기본 테이블](../docs/migrations/20260907_document_bookmarks.sql) → [버전 연결](../docs/migrations/20260907_bookmark_versions.sql) |
| 초안 저장·재작성 | [초안 저장](../docs/migrations/20260909_saved_proposal_drafts.sql) → [재작성·복원](../docs/migrations/20260910_proposal_regeneration.sql) |
| Telegram | [발송 관리](../docs/migrations/20260909_telegram_management.sql) → [다중 수신자](../docs/migrations/20260909_telegram_multi_recipient.sql) |
| ZIP 내부 파일 목록 | [메타데이터 열](../docs/migrations/20260909_zip_entry_metadata.sql) |
| 실행 경고 도움말 | [경고 상세 열](../docs/migrations/20260910_monitoring_warning_details.sql) |

</details>

<details>
<summary>선택 기능: Telegram 설정</summary>

서버에 `TELEGRAM_BOT_TOKEN`을 설정하고 `/telegram` 화면에서 수신자와 발송 여부를 관리합니다. 최초 저장 전에는 `TELEGRAM_ENABLED`·`TELEGRAM_CHAT_ID` 기본 설정을 사용합니다.

개인 채팅의 `/start`에 채팅 ID를 답장하려면 `TELEGRAM_COMMANDS_ENABLED=true`로 설정합니다. 봇당 서버 한 대에서만 켜며 다른 `getUpdates` 수신기나 웹훅과 함께 사용하지 않습니다. 받은 ID를 관리 화면에 등록해야 보고서 수신자가 됩니다.

</details>
