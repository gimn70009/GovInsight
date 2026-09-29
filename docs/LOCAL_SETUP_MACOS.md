# 다른 컴퓨터에서 실행하기 (macOS)

이 문서는 **Mac 한 대에서 백엔드·AI·프론트엔드를 실행하는 개발 환경**을 준비하는 안내입니다. macOS 기본 터미널의 zsh 기준이며, 프로젝트 위치는 `~/GovInsight`로 가정합니다. `~`는 현재 사용자의 홈 폴더입니다.

처음에는 **개발 도구 설치 → 코드 받기 → Oracle 준비 → 로컬 설정 작성 → 서버 3개 실행 → 로그인** 순서로 진행합니다. 한 번 준비한 뒤에는 **8. 다음 날 다시 실행하기**의 명령을 사용하세요.

[Windows 실행 안내](LOCAL_SETUP.md) · [프로젝트 소개](../README.md) · [백엔드 상세 안내](../backend/README.md) · [AI 상세 안내](../ai/README.md)

## 1. Mac 종류와 실행 구성을 확인하기

Apple 메뉴의 **이 Mac에 관하여**에서 칩을 확인합니다.

| Mac 종류 | 설치 파일을 고를 때 | 터미널의 `uname -m` 결과 |
| --- | --- | --- |
| M1·M2·M3·M4 등 Apple Silicon | `arm64` 또는 `aarch64` | 일반적으로 `arm64` |
| Intel Mac | `x64` 또는 `x86_64` | `x86_64` |

Apple Silicon에서는 터미널과 개발 도구를 같은 ARM 아키텍처로 맞추세요. Rosetta로 실행한 터미널은 실제 칩과 다른 아키텍처를 표시할 수 있습니다.

| 구성 | 실행 방법 | 기본 주소 |
| --- | --- | --- |
| Oracle | 기존 DB 연결 또는 Docker 컨테이너 | DB 호스트:1521/서비스명 |
| 백엔드 | JDK 21 + 저장소의 Gradle Wrapper | `http://localhost:8080` |
| AI | Python 3.12 가상환경 + Uvicorn | `http://127.0.0.1:8000` |
| 프론트엔드 | Node 24 LTS + Vite | `http://localhost:5173` |

백엔드·AI·프론트는 **터미널 탭 3개**에서 각각 실행합니다. Oracle을 Docker로 실행한다면 Docker Desktop도 켜 두어야 합니다. macOS·칩 지원 범위는 각 도구의 공식 설치 페이지를 확인하세요.

## 2. 처음 한 번 개발 도구 설치하기

### 2-1. Git 준비

터미널에서 `git --version`을 실행합니다. Git이나 개발자 도구가 없다면 아래 명령으로 Xcode Command Line Tools를 설치하고 완료될 때까지 기다립니다.

```bash
xcode-select --install
```

설치가 끝나면 새 터미널에서 확인합니다. 이미 설치되었다는 메시지가 나오면 다음 단계로 진행하면 됩니다. [Git 공식 macOS 안내](https://git-scm.com/install/mac)

```bash
git --version
```

### 2-2. JDK·Python·Node 설치

아래는 공식 설치 파일을 사용하는 방법입니다. 이미 해당 버전이 있다면 다시 설치하지 않고 다음 절에서 버전을 확인합니다.

| 도구 | 설치할 버전 | 설치 방법 |
| --- | --- | --- |
| Java JDK | **21** | [Temurin 다운로드](https://adoptium.net/temurin/releases/?version=21&os=mac)에서 macOS·JDK·21·본인 칩에 맞는 `.pkg` 선택 |
| Python | **3.12.x** | [Python 3.12.10 배포 페이지](https://www.python.org/downloads/release/python-31210/)의 `macOS 64-bit universal2 installer` 사용 가능. universal2는 Apple Silicon·Intel용 설치 파일 |
| Node.js | **24 LTS** | [공식 다운로드](https://nodejs.org/en/download)에서 macOS 설치 파일 선택. npm 포함 |
| Docker Desktop | 로컬 Oracle을 쓸 때만 | [공식 Mac 설치 안내](https://docs.docker.com/desktop/setup/install/mac-install/)에서 Apple Silicon 또는 Intel용 선택 |

`.pkg`를 열어 설치한 뒤 터미널을 새로 엽니다. Python 공식 설치 파일을 썼다면 `/Applications/Python 3.12/`의 `Install Certificates.command`도 실행해 Python HTTPS 인증서 설정을 완료하세요.

이미 Homebrew로 JDK 21·Python 3.12·Node 24를 설치했다면 그대로 사용할 수 있습니다. 아래 버전 확인 결과를 기준으로 하며, 서로 다른 아키텍처의 설치를 섞지 않습니다.

### 2-3. 실제 사용할 버전 확인

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
javac -version
python3.12 --version
node --version
npm --version
```

Java·javac는 `21`, Python은 `3.12`, Node는 `v24`로 시작해야 합니다. 여러 Java가 설치되어 있을 수 있으므로 이 문서의 백엔드 실행 명령은 `JAVA_HOME`을 다시 지정합니다.

`python3.12`를 찾지 못하면 새 터미널을 열어 다시 확인합니다. 공식 `.pkg` 설치 경로를 사용하는 경우 `/Library/Frameworks/Python.framework/Versions/3.12/bin/python3.12`로도 실행할 수 있습니다. Gradle은 저장소의 Wrapper가 내려받으므로 따로 설치할 필요가 없습니다.

## 3. 코드 받기

`~/GovInsight` 폴더가 아직 없을 때 실행합니다. 저장소 인증이 필요하면 본인 GitHub 계정으로 인증하세요.

```bash
git clone --branch develop https://github.com/gimn70009/GovInsight.git "$HOME/GovInsight"
cd "$HOME/GovInsight"
git branch --show-current
```

마지막 결과가 `develop`이면 됩니다. 다른 위치를 사용한다면 뒤의 `cd` 명령도 해당 경로로 바꾸세요.

Windows에서 쓰던 `.venv`, `node_modules`, `backend/build`를 복사하지 않고 Mac에서 다시 만듭니다. `application.properties`와 `ai/.env`는 Git에 들어 있지 않아 직접 작성해야 합니다. Git clone으로 기존 DB·로그인 계정·공고 데이터까지 옮겨지는 것은 아닙니다.

## 4. Oracle 준비 — 두 방법 중 하나 선택

이미 사용할 Oracle DB가 있다면 **4-1**만 따라 합니다. Mac 안에 새 개발 DB가 필요하면 **4-2**로 진행합니다. Oracle은 Mac의 Arm·Intel 환경에서 Docker 이미지를 이용하는 방법을 제공합니다. [Oracle 공식 안내](https://www.oracle.com/database/free/)

### 4-1. 기존 Oracle에 연결

DB 담당자에게 **호스트·포트·서비스명·전용 사용자·비밀번호**를 받습니다. 사내 DB라면 Mac에서 접근할 수 있도록 VPN·접속 허용도 확인합니다.

백엔드 설정에는 다음 형식의 JDBC 주소를 사용합니다.

```text
jdbc:oracle:thin:@DB_HOST:1521/SERVICE_NAME
```

`DB_HOST`와 `SERVICE_NAME`을 실제 값으로 바꿉니다. **Mac에서 `localhost`는 Mac 자신입니다.** Windows PC에 설치된 DB를 사용한다면 Windows PC의 주소를 넣어야 합니다.

기존 데이터가 있는 DB에 접속할 때는 7절의 **`none` 명령**을 사용합니다. 스키마는 현재 코드와 맞아야 합니다. JDBC 드라이버는 백엔드 의존성에 포함되어 있어 앱 실행을 위해 Mac에 SQL*Plus를 따로 설치할 필요는 없습니다.

### 4-2. Mac에서 Docker로 새 Oracle 실행

**① Docker Desktop을 실행합니다.**

응용 프로그램에서 Docker를 열어 엔진이 실행될 때까지 기다린 뒤 확인합니다.

```bash
docker version
```

Client와 Server 정보가 나와야 합니다. `Cannot connect to the Docker daemon`이면 Docker Desktop 실행 상태부터 확인합니다.

**② 공식 Oracle 이미지를 받습니다.**

```bash
docker pull container-registry.oracle.com/database/free:latest-lite
```

아래 예시는 Oracle이 안내하는 `latest-lite` 이미지를 사용합니다. Apple Silicon에서는 네이티브 ARM 이미지를 사용하며, CPU 종류를 바꾸는 `--platform` 옵션은 지정하지 않습니다. [Oracle의 Apple Silicon 이미지 안내](https://blogs.oracle.com/database/announcing-oracle-database-23ai-free-container-images-for-armbased-apple-macbook-computers)

**③ 컨테이너를 처음 한 번 만듭니다.**

이름은 `govinsight-oracle`, 데이터 볼륨은 `govinsight-oracle-data`입니다. 관리자 비밀번호를 직접 정해 입력합니다. 입력 중에는 문자가 보이지 않는 것이 정상입니다. 여러 줄 명령의 역슬래시 뒤에는 공백을 붙이지 마세요.

```bash
printf 'Oracle 관리자 비밀번호: '
read -r -s ORACLE_PWD
printf '\n'
export ORACLE_PWD

docker run -d --name govinsight-oracle \
  -p 127.0.0.1:1521:1521 \
  -e ORACLE_PWD \
  -v govinsight-oracle-data:/opt/oracle/oradata \
  container-registry.oracle.com/database/free:latest-lite

unset ORACLE_PWD
```

이 비밀번호는 Oracle의 `SYSTEM` 등 관리자 계정용입니다. 앱의 DB 계정 비밀번호와 화면 로그인 비밀번호는 아래에서 별도로 정합니다. 데이터 볼륨은 컨테이너를 중지했다가 다시 시작해도 DB 파일을 유지합니다. [Oracle 컨테이너 설정 안내](https://github.com/oracle/docker-images/blob/main/OracleDatabase/SingleInstance/README.md)

**④ DB가 준비될 때까지 기다립니다.**

```bash
docker logs -f govinsight-oracle
```

DB 시작·준비 완료 로그를 확인합니다. `DATABASE IS READY TO USE!` 같은 완료 메시지 후 `Ctrl+C`로 로그 보기만 종료합니다. 이때 DB 컨테이너는 계속 실행됩니다.

```bash
docker ps --filter name=govinsight-oracle
```

컨테이너가 실행 중이고 상태가 `healthy`인지 확인합니다. 초기화 도중 연결 오류가 나면 준비 완료 후 다시 시도합니다.

**⑤ 전용 앱 DB 계정을 만듭니다.**

컨테이너 안의 SQL*Plus를 사용합니다.

```bash
docker exec -it govinsight-oracle sqlplus system@//localhost:1521/FREEPDB1
```

비밀번호 입력창에는 ③에서 정한 Oracle 관리자 비밀번호를 입력합니다. `SQL>`이 나타나면 아래 SQL을 입력하세요. `YOUR_OWN_DB_PASSWORD`는 직접 정한 앱 DB 비밀번호로 바꿉니다. 계정 생성은 처음 한 번만 합니다.

```sql
SHOW CON_NAME;

CREATE USER govinsight IDENTIFIED BY "YOUR_OWN_DB_PASSWORD"
    DEFAULT TABLESPACE USERS
    TEMPORARY TABLESPACE TEMP
    QUOTA 1G ON USERS;

GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO govinsight;
EXIT;
```

`SHOW CON_NAME`은 `FREEPDB1`을 가리켜야 합니다. 앱 테이블·시퀀스는 7절의 JPA `create` 실행에서 생성합니다. JPA는 Oracle 설치나 DB 사용자 생성까지 대신하지 않습니다.

이 구성의 백엔드 JDBC 주소는 **`jdbc:oracle:thin:@127.0.0.1:1521/FREEPDB1`**입니다. 백엔드를 Mac에서 직접 실행하므로 컨테이너 이름 대신 Mac에 공개한 로컬 포트를 사용합니다.

## 5. 백엔드 설정 작성

기존 설정 파일이 없을 때만 복사하고, macOS 기본 편집기로 엽니다.

```bash
cd "$HOME/GovInsight/backend"
if [ ! -f src/main/resources/application.properties ]; then
  cp src/main/resources/application.properties.example src/main/resources/application.properties
fi
open -t src/main/resources/application.properties
```

JWT 키는 아래 명령으로 한 번 생성합니다. 출력된 **Base64 키 전체**를 복사합니다.

```bash
openssl rand -base64 32
```

`application.properties`의 **해당 줄을 찾아 값을 교체**합니다. 파일 끝에 같은 키를 중복 추가하지 않습니다. 아래에 없는 다른 설정은 예시 그대로 두세요.

```properties
spring.datasource.url=jdbc:oracle:thin:@127.0.0.1:1521/FREEPDB1
spring.datasource.username=govinsight
spring.datasource.password=YOUR_OWN_DB_PASSWORD
spring.jpa.hibernate.ddl-auto=none

app.jwt.secret=PASTE_GENERATED_BASE64_KEY_HERE

app.local-admin.enabled=true
app.local-admin.login-id=admin
app.local-admin.password=YOUR_OWN_LOGIN_PASSWORD

app.python-monitoring.base-url=http://127.0.0.1:8000
```

- 기존 DB에 연결한다면 첫 세 줄은 전달받은 접속 정보로 바꿉니다.
- `YOUR_OWN_DB_PASSWORD`는 Oracle 앱 계정의 비밀번호입니다. `YOUR_OWN_LOGIN_PASSWORD`는 브라우저 로그인용으로 직접 정한 값입니다.
- `app.jwt.secret`에는 위에서 만든 키를 넣습니다. 로그인 검증에 쓰이므로 같은 서버에서는 유지합니다. 임의의 짧은 문자열은 사용할 수 없습니다.
- `.properties` 값 양옆에 따옴표를 넣지 않습니다. 편집기가 서식 문서로 바꾸거나 `.txt` 확장자를 덧붙이지 않도록 원래 이름으로 저장합니다.
- `app.local-admin.enabled=true`이면 같은 ID가 없을 때 최초 관리자가 생성됩니다. 기존 계정이 있는 DB는 `false`로 두고 기존 계정으로 로그인할 수 있습니다. 이 설정의 비밀번호를 바꾸는 것만으로 기존 계정의 비밀번호가 바뀌지는 않습니다.

이 안내는 로컬 파일에 값을 적는 방식입니다. 예시의 `${DB_PASSWORD}` 등을 유지하려면 백엔드 실행 터미널에 환경변수를 따로 설정해야 합니다. Spring Boot가 일반 `.env` 파일을 자동으로 읽는 구성은 아닙니다.

## 6. AI·프론트 설치와 설정

### 6-1. AI 가상환경과 Chromium 설치

```bash
cd "$HOME/GovInsight/ai"
python3.12 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m playwright install chromium
```

각 명령이 성공한 뒤 다음으로 진행합니다. 가상환경을 활성화하지 않아도 `.venv/bin/python`을 직접 지정하면 됩니다. `requirements-dev.txt`에는 실행 의존성과 테스트 도구가 함께 들어 있습니다. Chromium은 게시판 수집에 필요합니다.

AI 설정 파일을 준비합니다.

```bash
if [ ! -f .env ]; then
  cp .env.example .env
fi
open -t .env
```

`OPENAI_API_KEY`를 실제 키로 교체하고, 백엔드 주소가 없다면 한 줄 추가합니다. 나머지는 예시의 값으로 시작합니다.

```dotenv
OPENAI_API_KEY=YOUR_OPENAI_API_KEY
SPRING_BOOT_BASE_URL=http://127.0.0.1:8080
```

기본 분석·제안·보고서 모델은 `gpt-5-mini`이고 임베딩도 API를 사용합니다. 실제 분석에는 API를 사용할 수 있는 키와 사용 가능 한도가 필요합니다. 키를 프론트 코드에 넣거나 Git에 커밋하지 않습니다.

VS Code를 사용한다면 `Cmd+Shift+P` → **Python: Select Interpreter**에서 `~/GovInsight/ai/.venv/bin/python`을 선택합니다. 저장소의 기본 편집기 설정은 Windows의 `.venv/Scripts/python.exe`를 가리키므로 Mac에서는 인터프리터를 별도로 선택해야 합니다.

### 6-2. 프론트 설치

```bash
cd "$HOME/GovInsight/frontend"
npm ci
```

`npm ci`는 저장소의 `package-lock.json`에 맞춰 설치합니다. 기본 로컬 실행에는 프론트용 `.env`가 필요하지 않습니다. [Vite 설정](../frontend/vite.config.ts)의 `/api` 프록시가 백엔드 `http://localhost:8080`으로 요청을 전달합니다.

## 7. 처음 실행하고 로그인하기

### 7-1. 터미널 1 — AI

```bash
cd "$HOME/GovInsight/ai"
.venv/bin/python -m uvicorn app.main:app --env-file .env --reload --host 127.0.0.1 --port 8000
```

`Application startup complete`가 나오면 [AI 상태 확인](http://127.0.0.1:8000/health)을 엽니다. `{"status":"UP"}`는 서버가 응답한다는 뜻이며 실제 모델 호출·키 유효성까지 검증한 결과는 아닙니다. `--env-file`은 앱을 불러오기 전에 `.env` 설정을 읽게 합니다.

### 7-2. 터미널 2 — 백엔드

**새 전용 개발 DB를 처음 만들거나 기존 앱 데이터를 지우기로 한 경우**에는 다음 명령을 사용합니다.

```bash
cd "$HOME/GovInsight/backend"
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
sh ./gradlew bootRun --args="--spring.jpa.hibernate.ddl-auto=create"
```

**`create`는 연결한 DB의 앱 테이블을 지우고 다시 만들며 계정·공고·분석·보고서도 초기화합니다. 기존 DB의 데이터를 유지하려면 아래 `none`을 사용하세요. Docker 볼륨이 있어도 `create`로 삭제한 데이터는 유지되지 않습니다.**

이미 현재 코드에 맞는 스키마가 있는 DB를 사용할 때:

```bash
cd "$HOME/GovInsight/backend"
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
sh ./gradlew bootRun --args="--spring.jpa.hibernate.ddl-auto=none"
```

현재 저장소의 `gradlew`에는 실행 권한이 설정되어 있지 않아 `sh ./gradlew`로 실행합니다. 이 방식은 파일 권한을 바꾸지 않아도 됩니다. Windows용 `gradlew.bat`는 사용하지 않습니다.

첫 실행에는 Gradle과 의존성을 내려받아 시간이 걸릴 수 있습니다. `Started BackendApplication`이 나오면 [Swagger UI](http://localhost:8080/swagger-ui.html)를 열어 확인합니다. 서버가 켜진 동안 터미널에 로그가 계속 나오는 것은 정상입니다.

최초 관리자 생성 후에는 `app.local-admin.enabled=false`로 바꿔도 계정은 유지됩니다. 이후 백엔드를 다시 실행할 때는 `none`을 사용합니다. DB를 다시 초기화하는 경우 관리자 생성 설정도 다시 확인하세요.

### 7-3. 터미널 3 — 프론트

```bash
cd "$HOME/GovInsight/frontend"
npm run dev
```

[GovInsight 화면](http://localhost:5173)을 열어 `app.local-admin.login-id`와 `app.local-admin.password`에 설정했던 값으로 로그인합니다.

새 DB는 모니터링 소스·공고·실행 이력이 비어 있을 수 있습니다. **소스 등록**에서 실제 게시판을 등록하고 적은 수집 건수로 **지금 모니터링 실행**을 눌러 봅니다. 수집·분석이 끝날 때까지 세 서버와 DB를 켜 둡니다. 실제 실행에는 OpenAI API 사용량이 발생합니다.

Telegram과 이메일 발송은 선택 기능입니다. 필요할 때 [백엔드의 알림 설정 안내](../backend/README.md)를 따라 설정합니다.

## 8. 다음 날 다시 실행하기

로컬 Oracle 컨테이너를 사용한다면 먼저 Docker Desktop을 열고 아래 명령으로 DB를 시작합니다. **기존 컨테이너에는 `docker run`을 반복하지 않습니다.**

```bash
docker start govinsight-oracle
docker ps --filter name=govinsight-oracle
```

DB가 준비된 뒤 터미널 3개에서 각각 실행합니다. 기존 DB 서버에 접속하는 방식이면 Docker 단계는 생략합니다.

**터미널 1 — AI**

```bash
cd "$HOME/GovInsight/ai"
.venv/bin/python -m uvicorn app.main:app --env-file .env --reload --host 127.0.0.1 --port 8000
```

**터미널 2 — 백엔드: 데이터 유지**

```bash
cd "$HOME/GovInsight/backend"
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
sh ./gradlew bootRun --args="--spring.jpa.hibernate.ddl-auto=none"
```

**터미널 3 — 프론트**

```bash
cd "$HOME/GovInsight/frontend"
npm run dev
```

브라우저에서 [GovInsight](http://localhost:5173)을 사용합니다. 종료할 때는 각 서버 터미널에서 `Ctrl+C`를 누릅니다. 로컬 DB도 중지하려면 앱 서버를 종료한 뒤 아래 명령을 사용합니다. 컨테이너와 볼륨을 유지하므로 다음에는 `docker start`로 이어서 실행할 수 있습니다.

```bash
docker stop govinsight-oracle
```

## 9. 코드 업데이트와 문제 해결

서버를 종료한 뒤 변경 상태를 확인하고 업데이트합니다.

```bash
cd "$HOME/GovInsight"
git status
git switch develop
git pull --ff-only origin develop
```

작업 중인 변경이나 충돌·분기 오류가 있으면 먼저 해당 내용을 확인합니다. 로컬 설정은 유지합니다. 의존성이 바뀌었다면 다시 설치하고 Playwright 버전이 바뀌면 Chromium도 갱신합니다.

```bash
cd "$HOME/GovInsight/ai"
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m playwright install chromium

cd "$HOME/GovInsight/frontend"
npm ci
```

`none`은 DB 구조를 자동으로 바꾸지 않습니다. 엔티티 변경이 있다면 데이터를 유지할 DB에 필요한 스키마 변경을 확인합니다. 데이터를 비워도 되는 전용 개발 DB만 `create`로 다시 만듭니다.

| 증상 | 확인할 내용 |
| --- | --- |
| Java 21을 찾지 못함 | `/usr/libexec/java_home -V`로 설치 목록 확인. JRE가 아닌 JDK 21 설치 후 `JAVA_HOME` 지정 |
| `permission denied: ./gradlew` | 문서처럼 `sh ./gradlew` 실행 |
| Python `externally-managed-environment` | 시스템 Python에 설치하지 말고 `.venv/bin/python -m pip`로 설치 |
| `No module named app` 또는 Python import 오류 | `ai` 폴더에서 Mac용 `.venv/bin/python`을 사용하는지 확인 |
| Python HTTPS 인증서 오류 | python.org 설치본이면 `Install Certificates.command` 실행. 사내 인증서가 필요한 환경은 해당 인증서를 설정 |
| VS Code가 Windows Python 경로를 찾음 | 인터프리터를 `ai/.venv/bin/python`으로 선택 |
| `bad CPU type`, 잘못된 아키텍처 오류 | Mac 칩과 JDK·Python·Node 아키텍처 확인. 다른 PC의 가상환경·node_modules를 가져오지 않았는지 확인 |
| Docker daemon 연결 실패 | Docker Desktop 실행 및 엔진 준비 상태 확인 |
| 컨테이너 이름이 이미 존재함 | `docker ps -a --filter name=govinsight-oracle`로 확인하고 기존 컨테이너는 `docker start` 사용 |
| `no matching manifest`, 컨테이너 실행 아키텍처 오류 | 공식 이미지와 Mac 칩에 맞는 Docker 설치 확인. 오래된 XE 이미지 대신 안내한 Free 이미지 사용 |
| Oracle `ORA-12541`·`ORA-12514` | DB 준비·1521 포트·서비스명 확인. 이 Docker 구성은 `FREEPDB1` 사용 |
| Oracle `ORA-01017` | 관리자 비밀번호·앱 DB 비밀번호·화면 로그인 비밀번호를 구분 |
| Oracle `ORA-00942` | 연결 계정·PDB가 맞는지, 앱 테이블을 생성했는지 확인 |
| JWT 키 관련 오류 | `openssl rand -base64 32` 결과 전체를 `app.jwt.secret`에 입력 |
| Chromium 실행 파일 없음 | `.venv/bin/python -m playwright install chromium` 실행 |
| 화면만 열리고 로그인·조회 실패 | 백엔드 8080·DB·최초 관리자 생성 여부 확인 |
| AI 상태는 UP인데 분석 실패 | API 키·모델 사용 권한·한도·대상 사이트 접속·백엔드 결과 전달 주소 확인 |
| 포트 사용 중 오류 | 같은 서버가 이미 실행 중인지 확인하고 해당 터미널에서 `Ctrl+C` |

포트 사용자는 아래 명령으로 조회할 수 있습니다.

```bash
lsof -nP -iTCP:8000 -sTCP:LISTEN
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:5173 -sTCP:LISTEN
lsof -nP -iTCP:1521 -sTCP:LISTEN
```

AI 포트를 바꾸면 백엔드의 `app.python-monitoring.base-url`도 바꿉니다. 백엔드 포트를 바꾸면 AI의 `SPRING_BOOT_BASE_URL`과 프론트 `vite.config.ts`의 `/api` 대상도 함께 바꿔야 합니다. 설정 변경 후 해당 서버를 재시작하세요.
