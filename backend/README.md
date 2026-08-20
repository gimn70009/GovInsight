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
