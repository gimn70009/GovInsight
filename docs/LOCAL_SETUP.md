# 다른 컴퓨터에서 실행하기 (Windows)

이 문서는 **새 Windows PC 한 대에서 백엔드·AI·프론트엔드를 함께 실행하는 개발 환경**을 준비하는 안내입니다. 명령은 Windows PowerShell 기준이며, 프로젝트 위치는 `C:\GovInsight`로 가정합니다. 다른 폴더에 받았다면 명령의 경로도 그 위치로 바꾸세요.

처음에는 **프로그램 설치 → 코드 받기 → Oracle 준비 → 설정 작성 → 서버 3개 실행 → 로그인** 순서로 진행합니다. 한 번 준비한 뒤에는 아래의 **8. 다음 날 다시 실행하기**만 따라 하면 됩니다.

[macOS 실행 안내](LOCAL_SETUP_MACOS.md) · [프로젝트 소개](../README.md) · [백엔드 상세 안내](../backend/README.md) · [AI 상세 안내](../ai/README.md)

## 1. 무엇을 실행하는지 먼저 보기

| 구성 | 하는 일 | 실행 위치 | 기본 주소 |
| --- | --- | --- | --- |
| Oracle | 계정, 공고, 분석, 보고서 저장 | 로컬 설치 또는 접근 가능한 DB 서버 | 호스트:1521/서비스명 |
| 백엔드 | 로그인, DB 저장·조회, 모니터링 작업 관리 | `C:\GovInsight\backend` | `http://localhost:8080` |
| AI | 게시판 수집, 첨부파일 읽기, AI 분석 | `C:\GovInsight\ai` | `http://127.0.0.1:8000` |
| 프론트엔드 | 브라우저에서 사용하는 화면 | `C:\GovInsight\frontend` | `http://localhost:5173` |

PowerShell 창이나 VS Code 터미널을 **3개** 열어 백엔드·AI·프론트를 각각 실행합니다. 서버 실행 중에는 명령 입력 상태로 돌아오지 않고 로그가 계속 나오는 것이 정상입니다. 각 창을 켜 둔 상태로 브라우저를 사용하세요.

## 2. 처음 한 번 설치할 프로그램

| 프로그램 | 사용할 버전 | 설치 링크와 확인할 점 |
| --- | --- | --- |
| Git | Windows용 | [공식 설치 페이지](https://git-scm.com/install/windows)에서 설치 |
| Java JDK | **21** | [Temurin 다운로드](https://adoptium.net/temurin/releases/?version=21)에서 버전 21, Windows, 본인 PC 아키텍처, **JDK** 선택 |
| Python | **3.12.x** | [Python 3.12.10 배포 페이지](https://www.python.org/downloads/release/python-31210/)에는 Windows 설치 파일이 있습니다. 설치 시 Python Launcher와 PATH 등록 옵션 확인 |
| Node.js | **24 LTS** | [공식 다운로드](https://nodejs.org/en/download)에서 설치. npm도 함께 설치됨 |
| Oracle | 접근 가능한 Oracle DB | 기존 개발 DB를 사용하거나 [Oracle Database Free 시작 안내](https://www.oracle.com/database/free/get-started/)에 따라 로컬 설치 |
| 편집기 | 선택 | VS Code나 IntelliJ를 사용할 수 있으며, 설정 파일은 메모장으로도 수정 가능 |

Java는 JRE만 설치하면 빌드할 수 없습니다. Windows x64 MSI 설치 시 `PATH` 등록과 `JAVA_HOME` 설정 옵션을 선택하세요. 세부 화면은 [Temurin Windows 설치 안내](https://adoptium.net/installation/windows/)를 참고합니다.

이 안내에서는 프론트의 현재 Vite·ESLint 버전 요구사항을 충족하는 **Node 24 LTS**로 통일합니다. Gradle과 npm을 따로 내려받을 필요는 없습니다. Gradle은 저장소의 Wrapper, npm은 Node 설치에 포함된 명령을 사용합니다.

설치 후 터미널을 새로 열고 확인합니다.

```powershell
git --version
java -version
javac -version
py -3.12 --version
node --version
npm.cmd --version
```

Java·javac는 `21`, Python은 `3.12`, Node는 `v24`로 시작하는 버전이 나와야 합니다. `명령을 찾을 수 없다`면 프로그램 설치와 PATH를 확인한 뒤 VS Code·터미널도 다시 여세요.

## 3. 코드 받기

아래 명령은 `C:\GovInsight` 폴더가 아직 없을 때 실행합니다. 저장소 접근 권한이 필요한 경우 본인의 GitHub 계정으로 인증하세요.

```powershell
git clone --branch develop https://github.com/gimn70009/GovInsight.git C:\GovInsight
Set-Location C:\GovInsight
git branch --show-current
```

마지막 결과가 `develop`이면 됩니다. 이미 받은 폴더가 있다면 새로 덮어쓰지 말고 **9. 코드를 업데이트할 때**를 참고하세요.

새 PC에는 다음을 별도로 준비해야 합니다.

| 항목 | 준비 방법 |
| --- | --- |
| Oracle 주소·서비스명·계정·비밀번호 | DB 담당자에게 받거나 다음 절에서 전용 개발 계정 생성 |
| OpenAI API 키 | 프로젝트에서 사용할 API 키 준비 |
| 최초 관리자 ID·비밀번호 | 새 개발 DB라면 직접 정함. 기존 DB라면 기존 로그인 계정 사용 |
| 백엔드 로컬 설정 | `application.properties.example`을 복사해서 작성 |
| AI 로컬 설정 | `.env.example`을 복사해서 작성 |

`application.properties`와 `.env`는 비밀정보가 들어가는 로컬 파일이라 Git으로 내려오지 않습니다. 기존 PC의 `.venv`, `node_modules`, `build` 폴더도 복사하지 않고 새 PC에서 다시 설치·생성합니다. **Git clone은 DB 데이터와 기존 로그인 계정을 옮기지 않습니다.**

## 4. Oracle 준비

### 4-1. 기존 DB를 사용할 경우

DB 담당자에게 **호스트, 포트, 서비스명, 전용 사용자 이름, 비밀번호**를 확인합니다. 새 PC에서 해당 DB에 접속할 수 있어야 하며, 사내 DB는 VPN이나 접속 허용이 필요할 수 있습니다.

백엔드가 사용하는 주소 형식은 다음과 같습니다.

```text
jdbc:oracle:thin:@DB_HOST:1521/SERVICE_NAME
```

| 예시 | JDBC 주소 |
| --- | --- |
| 로컬 Oracle Free의 기본 PDB | `jdbc:oracle:thin:@localhost:1521/FREEPDB1` |
| 설정 예시 파일에 들어 있는 XE 서비스명 | `jdbc:oracle:thin:@localhost:1521/XEPDB1` |
| 별도 DB 서버 | `jdbc:oracle:thin:@DB_HOST:1521/SERVICE_NAME` |

`DB_HOST`와 `SERVICE_NAME`은 실제 값으로 바꾸세요. **새 PC의 `localhost`는 새 PC 자신을 뜻합니다.** 기존 PC에 설치한 Oracle을 사용할 때는 그 PC의 주소가 필요합니다.

Oracle Free의 기본 애플리케이션용 PDB 서비스는 `FREEPDB1`입니다. 설치 완료 화면이나 DB 담당자가 알려준 값을 우선 사용하세요. [Oracle 공식 접속 안내](https://docs.oracle.com/en/database/oracle/oracle-database/26/xeinw/connecting-oracle-database-xe.html)

### 4-2. 새 PC에 Oracle을 설치한 경우

1. Oracle Free를 설치하고 설치 때 정한 관리자 비밀번호를 기억합니다.
2. 설치 완료 화면에서 접속 주소·서비스명을 확인합니다.
3. 아래처럼 `SYSTEM`으로 **애플리케이션을 사용할 PDB**에 접속합니다. 명령이 없다면 Oracle 설치 폴더의 `bin` 아래 `sqlplus.exe`를 사용하세요.

```powershell
sqlplus system@localhost:1521/FREEPDB1
```

비밀번호 입력창에는 Oracle 설치 시 정한 관리자 비밀번호를 입력합니다. SQL*Plus의 `SQL>` 프롬프트가 나타나면 접속된 것입니다.

아래 SQL은 **전용 개발 계정이 아직 없을 때 한 번만** 실행합니다. `YOUR_OWN_DB_PASSWORD`를 본인이 정한 DB 비밀번호로 바꾸세요. `SHOW CON_NAME` 결과가 접속하려던 PDB인지 먼저 확인합니다.

```sql
SHOW CON_NAME;

CREATE USER govinsight IDENTIFIED BY "YOUR_OWN_DB_PASSWORD"
    DEFAULT TABLESPACE USERS
    TEMPORARY TABLESPACE TEMP
    QUOTA 1G ON USERS;

GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO govinsight;
EXIT;
```

이 예시는 로컬 Oracle Free의 `USERS`·`TEMP` 테이블스페이스를 사용합니다. 기존 회사 DB에서는 담당자가 정한 계정·권한·용량을 사용하세요. 계정 생성과 권한 부여의 의미는 [Oracle 사용자·권한 안내](https://blogs.oracle.com/sql/how-to-create-users-grant-them-privileges-and-remove-them-in-oracle-database)를 참고할 수 있습니다.

`govinsight`로 접속되는지 확인하면 DB 준비가 끝납니다.

```powershell
sqlplus govinsight@localhost:1521/FREEPDB1
```

접속 후 `EXIT;`로 나옵니다. **앱의 테이블과 시퀀스는 다음 절의 JPA `create` 실행이 만듭니다. Oracle 설치나 DB 사용자 계정 생성까지 대신해 주지는 않습니다.**

## 5. 백엔드 설정 작성

### 5-1. 예시 파일 복사

PowerShell에서 다음을 실행합니다. 기존 파일이 있으면 덮어쓰지 않습니다.

```powershell
Set-Location C:\GovInsight\backend
if (!(Test-Path src/main/resources/application.properties)) {
    Copy-Item src/main/resources/application.properties.example src/main/resources/application.properties
}
notepad.exe src/main/resources/application.properties
```

### 5-2. JWT 키 만들기

JWT는 로그인 상태를 확인할 때 사용하는 서명 방식입니다. 이 프로젝트는 **Base64로 인코딩된 32바이트 이상의 키**를 받습니다. 별도 PowerShell에서 아래 명령을 한 번 실행하고 출력값을 복사합니다.

```powershell
py -3.12 -c "import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())"
```

생성한 키는 다음 설정의 `app.jwt.secret`에 넣고 같은 DB·서버를 사용하는 동안 보관합니다. 키가 바뀌면 기존 로그인 토큰은 유효하지 않아 다시 로그인해야 합니다.

### 5-3. 로컬 값 입력

열어 둔 `application.properties`에서 **아래 키가 있는 줄을 찾아 값을 교체**합니다. 아래 내용을 파일 끝에 중복 추가하지 마세요. 다른 설정은 그대로 둡니다.

```properties
spring.datasource.url=jdbc:oracle:thin:@localhost:1521/FREEPDB1
spring.datasource.username=govinsight
spring.datasource.password=YOUR_OWN_DB_PASSWORD
spring.jpa.hibernate.ddl-auto=none

app.jwt.secret=PASTE_GENERATED_BASE64_KEY_HERE

app.local-admin.enabled=true
app.local-admin.login-id=admin
app.local-admin.password=YOUR_OWN_LOGIN_PASSWORD

app.python-monitoring.base-url=http://127.0.0.1:8000
```

- DB 주소·사용자·비밀번호는 4절에서 준비한 값으로 바꿉니다. 예시 파일의 `XEPDB1`을 그대로 쓸 수 있는지는 실제 DB 서비스명으로 판단합니다.
- `YOUR_OWN_DB_PASSWORD`, `PASTE_GENERATED_BASE64_KEY_HERE`, `YOUR_OWN_LOGIN_PASSWORD`는 설명용 표시입니다. 반드시 실제 값으로 바꾸고 저장합니다.
- DB 비밀번호와 화면 로그인 비밀번호는 서로 다른 용도입니다. `app.local-admin.password`가 브라우저 로그인 비밀번호입니다.
- `.properties`의 값 양옆에는 따옴표를 붙이지 않습니다. 값에 역슬래시가 있으면 `\`로 이스케이프해야 합니다.
- 최초 관리자 생성은 `enabled=true`이고 같은 ID가 DB에 없을 때만 수행합니다. 기존 DB를 사용하는 경우에는 `false`로 두고 기존 계정으로 로그인할 수 있습니다. 설정의 비밀번호를 바꿔도 이미 생성된 계정의 비밀번호가 바뀌지는 않습니다.

이 안내는 로컬 파일에 값을 직접 적는 방식입니다. 예시의 `${DB_PASSWORD}` 등을 그대로 두고 환경변수를 사용하는 방식도 가능하지만, 그 경우 백엔드를 실행하는 터미널에서 변수를 설정해야 합니다. 일반적인 `.env` 파일을 만들어 놓는 것만으로 Spring Boot가 자동으로 읽지는 않습니다.

## 6. AI와 프론트 의존성 설치

### 6-1. AI 가상환경·패키지·브라우저 설치

```powershell
Set-Location C:\GovInsight\ai
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m playwright install chromium
```

각 명령이 성공한 뒤 다음 명령을 실행하세요. `requirements-dev.txt`에는 실행 패키지와 테스트 도구가 함께 들어 있습니다. Playwright용 Chromium은 웹 수집에 사용하므로 일반 Chrome이 설치되어 있어도 위 설치를 실행합니다.

가상환경 활성화는 생략하고 `.venv`의 Python을 직접 지정합니다. AI 명령은 **`ai` 폴더에서** 실행해야 저장소의 Windows UUID·해시 호환 모듈도 올바르게 찾습니다.

AI 설정 파일을 만듭니다.

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
notepad.exe .env
```

`OPENAI_API_KEY` 줄을 실제 키로 바꾸고, 백엔드 주소가 없다면 한 줄 추가합니다. 나머지는 예시 파일의 값으로 시작하세요.

```dotenv
OPENAI_API_KEY=YOUR_OPENAI_API_KEY
SPRING_BOOT_BASE_URL=http://127.0.0.1:8080
```

기본 분석·제안·보고서 모델은 `gpt-5-mini`이며, 임베딩도 API를 사용합니다. 준비한 키에 해당 API를 사용할 권한과 사용 가능 한도가 있어야 합니다. 실제 모니터링·분석은 API 사용량을 발생시킵니다.

### 6-2. 프론트 패키지 설치

```powershell
Set-Location C:\GovInsight\frontend
npm.cmd ci
```

`npm ci`는 저장소의 `package-lock.json`에 맞춰 패키지를 설치합니다. Windows에서는 `npm.cmd`로 실행하면 `npm.ps1`의 실행 정책 오류를 피할 수 있습니다.

기본 로컬 실행에는 프론트용 `.env`가 필요하지 않습니다. 현재 [Vite 설정](../frontend/vite.config.ts)이 브라우저의 `/api` 요청을 백엔드 `http://localhost:8080`으로 전달합니다.

## 7. 처음 실행하고 로그인하기

### 7-1. 터미널 1 — AI 실행

```powershell
Set-Location C:\GovInsight\ai
.\.venv\Scripts\python.exe -m uvicorn app.main:app --env-file .env --reload --host 127.0.0.1 --port 8000
```

`Application startup complete`가 나오면 브라우저에서 [AI 상태 확인](http://127.0.0.1:8000/health)을 엽니다. `{"status":"UP"}`가 나오면 서버가 응답하는 상태입니다. API 키의 유효성이나 실제 모델 호출 성공까지 검사한 결과는 아닙니다.

`--env-file .env`는 서버가 앱을 불러오기 전에 설정을 읽게 합니다. [Uvicorn 설정 안내](https://uvicorn.dev/settings/)

### 7-2. 터미널 2 — 백엔드 실행

먼저 사용할 DB 상태에 따라 **아래 명령 중 하나**를 선택합니다.

| DB 상태 | 사용할 DDL 값 |
| --- | --- |
| 새 전용 개발 계정이거나, 기존 앱 데이터를 지우고 다시 만들기로 한 DB | `create` |
| 현재 코드에 맞는 테이블이 이미 있고 데이터를 유지할 DB | `none` |

**`create`는 연결한 DB 계정의 앱 테이블을 지우고 다시 만듭니다. 기존 계정·공고·분석·보고서도 없어집니다. 새 PC라는 이유만으로 기존 공유 DB에 `create`를 실행하면 안 됩니다.**

새 전용 개발 DB를 처음 초기화할 때:

```powershell
Set-Location C:\GovInsight\backend
.\gradlew.bat bootRun --args="--spring.jpa.hibernate.ddl-auto=create"
```

이미 준비된 DB의 데이터를 유지할 때:

```powershell
Set-Location C:\GovInsight\backend
.\gradlew.bat bootRun --args="--spring.jpa.hibernate.ddl-auto=none"
```

첫 실행에는 Gradle과 Java 의존성을 내려받아 시간이 걸릴 수 있습니다. Gradle 진행률이 끝나지 않아 보여도 `Started BackendApplication`이 나오고 오류 없이 계속 실행 중이면 서버가 올라온 것입니다. [Swagger UI](http://localhost:8080/swagger-ui.html)를 열어 확인합니다. 백엔드 루트 주소 `/`의 응답만으로 정상 여부를 판단하지 마세요.

`create`로 초기화했다면 이후 다시 실행할 때는 **`none` 명령을 사용**합니다. 최초 관리자 생성 후에는 설정의 `app.local-admin.enabled`를 `false`로 바꿔도 기존 계정은 유지됩니다. 다시 DB를 초기화할 때에는 새 계정이 필요하므로 관리자 생성 설정도 다시 확인합니다.

### 7-3. 터미널 3 — 프론트 실행

```powershell
Set-Location C:\GovInsight\frontend
npm.cmd run dev
```

`http://localhost:5173`이 출력되면 [GovInsight 화면](http://localhost:5173)을 엽니다. `npm run build`는 파일을 만드는 명령이므로 이 개발 서버 실행을 대신하지 않습니다.

로그인에는 5절에서 정한 **`app.local-admin.login-id`와 `app.local-admin.password`**를 사용합니다. 예시대로라면 ID는 `admin`, 비밀번호는 본인이 입력한 값입니다.

### 7-4. 실제 흐름 확인

1. 세 터미널에 종료 오류가 없는지 확인합니다.
2. AI `/health`와 백엔드 Swagger가 열리는지 확인합니다.
3. 프론트에서 로그인합니다.
4. 새 DB에서는 모니터링 소스와 실행 이력이 비어 있을 수 있습니다. **소스 등록**에서 기관명·게시판명·실제 목록 URL·수집 건수를 입력하고 활성화합니다.
5. 처음에는 사용할 게시판 한 곳과 적은 건수로 **지금 모니터링 실행**을 눌러 수집·분석 결과를 확인합니다. 완료까지 세 서버를 켜 둡니다.

Telegram과 이메일 발송은 선택 기능입니다. 서버 3개 실행과 로그인에 봇 토큰·SMTP 계정이 필수는 아닙니다. 발송이 필요할 때 [백엔드의 알림 설정 안내](../backend/README.md)를 따라 설정하고 화면에서 수신자와 발송 여부를 관리합니다.

## 8. 다음 날 다시 실행하기

Oracle이 켜져 있는지 확인한 뒤 터미널 3개에서 각각 실행합니다. 로컬 설정 파일과 설치한 의존성이 남아 있다면 복사·설치를 반복할 필요가 없습니다.

**터미널 1 — AI**

```powershell
Set-Location C:\GovInsight\ai
.\.venv\Scripts\python.exe -m uvicorn app.main:app --env-file .env --reload --host 127.0.0.1 --port 8000
```

**터미널 2 — 백엔드: 데이터 유지**

```powershell
Set-Location C:\GovInsight\backend
.\gradlew.bat bootRun --args="--spring.jpa.hibernate.ddl-auto=none"
```

**터미널 3 — 프론트**

```powershell
Set-Location C:\GovInsight\frontend
npm.cmd run dev
```

브라우저에서 [GovInsight](http://localhost:5173)을 열어 로그인합니다. 종료할 때는 실행 중인 각 터미널에서 `Ctrl+C`를 누릅니다. 배치 작업 종료 여부를 물으면 종료를 선택합니다.

## 9. 코드를 업데이트할 때

서버를 종료하고 프로젝트 루트에서 실행합니다.

```powershell
Set-Location C:\GovInsight
git status
git switch develop
git pull --ff-only origin develop
```

작업 중인 변경이 있거나 충돌·브랜치 분기 오류가 나오면 먼저 해당 변경을 확인하세요. 로컬 설정 파일은 기존 값을 유지합니다.

AI 또는 프론트의 의존성이 바뀌었다면 다시 설치합니다. Playwright 버전이 바뀌었을 때도 Chromium 설치를 다시 실행합니다.

```powershell
Set-Location C:\GovInsight\ai
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m playwright install chromium

Set-Location C:\GovInsight\frontend
npm.cmd ci
```

엔티티 변경이 포함되었다면 `none`으로 실행해도 DB 구조가 자동 갱신되지는 않습니다. 데이터를 유지할 DB는 필요한 스키마 변경을 확인하고 적용합니다. 비워도 되는 전용 개발 DB를 재생성할 때만 7절의 `create` 명령을 사용합니다.

## 10. 자주 막히는 부분

| 증상 | 확인할 내용 |
| --- | --- |
| `java`를 찾지 못하거나 Java 21을 찾을 수 없음 | JDK 21 설치, `java -version`·`javac -version` 확인. `JAVA_HOME`은 JDK 폴더를 가리켜야 하며 `bin` 폴더가 아님. IDE의 Gradle JVM도 21로 선택 |
| `py -3.12`를 찾을 수 없음 | Python 3.12와 Launcher 설치 확인. 설치한 Python 3.12 실행 파일의 전체 경로로 가상환경을 만들어도 됨 |
| `npm.ps1` 실행이 차단됨 | 문서처럼 `npm.cmd`로 실행 |
| `No module named app`, UUID·xxhash import 오류 | 현재 폴더가 `C:\GovInsight\ai`인지, `.venv\Scripts\python.exe`를 쓰는지 확인 |
| Playwright `Executable doesn't exist` | AI 가상환경에서 `python.exe -m playwright install chromium` 실행 |
| `OPENAI_API_KEY가 설정되지 않았습니다`, API 인증 실패 | `ai\.env`의 실제 키, 키 권한·사용 가능 한도, `--env-file .env` 실행 여부 확인 후 AI 재시작 |
| `${DB_PASSWORD}`·`${JWT_SECRET}` 설정 누락 | 예시 파일의 해당 줄을 실제 값으로 교체했는지 확인. 환경변수 방식이면 백엔드 실행 터미널에서 설정 |
| JWT `WeakKeyException`, Base64 관련 오류 | 5-2절 명령으로 만든 Base64 키 전체를 `app.jwt.secret`에 입력 |
| Oracle `ORA-12541`·`ORA-12514` | DB와 리스너 실행, 호스트·포트·서비스명 확인. `FREEPDB1`과 `XEPDB1`을 혼동하지 않았는지 확인 |
| Oracle `ORA-01017` | DB 사용자·비밀번호 확인. 화면 로그인 계정과 구분 |
| Oracle `ORA-00942` | 연결 계정·서비스명이 맞는지, 해당 DB에 앱 테이블을 생성했는지 확인 |
| Oracle 권한·저장 공간 오류 | 전용 계정에 테이블·시퀀스 생성 권한과 테이블스페이스 용량이 있는지 확인 |
| 화면은 열리지만 로그인·조회 실패 | 백엔드 8080과 프론트의 `/api` 프록시 확인. 새 관리자 생성 여부와 로그인 ID·비밀번호 확인 |
| AI 상태는 정상인데 모니터링 실패 | 상태 확인은 실제 수집·모델 호출 검사가 아님. AI 로그에서 대상 사이트 접속, Chromium, API 키, 결과 전달 주소 확인 |
| `Address already in use`, 5173 포트 사용 중 | 같은 서버가 이미 실행 중인지 확인하고 해당 터미널에서 `Ctrl+C`. 프론트는 5173이 차 있으면 다른 포트로 자동 이동하지 않음 |

포트를 쓰는 프로세스가 궁금하면 아래 조회 명령을 사용합니다. `OwningProcess`가 PID입니다.

```powershell
Get-NetTCPConnection -LocalPort 8000,8080,5173 -State Listen -ErrorAction SilentlyContinue |
    Select-Object LocalAddress,LocalPort,OwningProcess
```

이 문서의 기본 주소를 바꿀 때에는 연결하는 쪽도 함께 맞춥니다.

| 바뀌는 주소 | 함께 바꿀 설정 |
| --- | --- |
| AI 주소·8000 포트 | 백엔드 `app.python-monitoring.base-url` 및 Uvicorn 실행 옵션 |
| 백엔드 주소·8080 포트 | AI `.env`의 `SPRING_BOOT_BASE_URL`, 프론트 `vite.config.ts`의 `/api` 대상, 백엔드 포트 설정 |
| Oracle 주소·포트·서비스명 | 백엔드 `spring.datasource.url` |

설정을 바꾼 뒤 해당 서버를 다시 실행하세요. 이 안내의 기본 구성에서는 API 키와 DB 비밀번호를 AI·백엔드의 로컬 설정에만 보관하며 프론트 코드에는 넣지 않습니다.
