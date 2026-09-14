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

서버에 `TELEGRAM_BOT_TOKEN`을 설정하고 `/telegram` 화면에서 수신자와 발송 여부를 관리합니다. 최초 저장 전에는 `TELEGRAM_ENABLED`·`TELEGRAM_CHAT_ID` 기본 설정을 사용합니다.

개인 채팅의 `/start`에 채팅 ID를 답장하려면 `TELEGRAM_COMMANDS_ENABLED=true`로 설정합니다. 봇당 서버 한 대에서만 켜며 다른 `getUpdates` 수신기나 웹훅과 함께 사용하지 않습니다. 받은 ID를 관리 화면에 등록해야 보고서 수신자가 됩니다.

</details>
