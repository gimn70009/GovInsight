# AI 모듈 실행 안내

FastAPI가 작업을 접수하고 Playwright로 공고를 수집합니다. PDF·HWP·HWPX·ZIP을 읽어 회사 관점의 분석과 초안을 생성하며, 결과 저장은 Spring Boot에 맡깁니다.

[프로젝트 소개](../README.md) · [전체 설계](../docs/DESIGN.md)

## 설치

Python 3.12가 필요합니다. 아래 명령은 **`ai` 폴더에서** 실행합니다.

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m playwright install chromium
```

설정 파일이 없는 경우에만 복사합니다.

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
```

`.env`의 `OPENAI_API_KEY`를 입력합니다. 키가 들어간 파일은 커밋하지 않습니다.

| 설정 | 용도·기본값 |
| --- | --- |
| `OPENAI_API_KEY` | 실제 분석·초안 생성에 필요한 API 키 |
| `OPENAI_MODEL`, `PROPOSAL_MODEL` | 분석·제안 모델, 기본 `gpt-5-mini` |
| `SPRING_BOOT_BASE_URL` | 결과를 전달할 백엔드, 기본 `http://127.0.0.1:8080` |
| `ANALYSIS_CONCURRENCY` | 문서 분석·후속 사업 제안 생성의 단계별 동시 처리 수, 기본 `3` |

처리 시간·재시도·ZIP 제한은 [.env.example](.env.example)에서 확인할 수 있습니다. 기존 `.env`의 `ANALYSIS_CONCURRENCY`가 `2`이면 `3`으로 변경하고 AI 서버를 재시작합니다. 실행 환경에 별도로 지정한 값은 `.env`와 코드 기본값보다 우선합니다.

## 실행

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

- [상태 확인](http://127.0.0.1:8000/health): 정상 응답은 `{"status":"UP"}`입니다.
- [API 문서](http://127.0.0.1:8000/docs): 내부 작업 요청과 응답 형식을 확인합니다.
- 전체 모니터링은 [백엔드](../backend/README.md)도 실행한 뒤 화면에서 시작합니다.

Windows용 UUID·해시 호환 모듈을 사용하도록 `ai` 폴더의 가상환경 Python으로 실행합니다. 별도의 네이티브 모듈 차단 여부는 해당 PC 정책에 따라 달라질 수 있습니다.

## 수집 결과 전달 실패 처리

수집 결과는 동일한 실행 ID·작업 ID·본문으로 총 3회까지 전송합니다. 네트워크 오류와 HTTP 5xx·429만 0.2초·0.4초 간격으로 재시도하고, 다른 4xx는 즉시 중단합니다. 기존 `SPRING_BOOT_TIMEOUT_SECONDS`는 각 요청에 적용합니다.

수집·첨부 보강·결과 요청 구성 자체가 중단되면 소스 전체의 실패를 백엔드에 통지합니다. 통지 실패나 프로세스 종료는 백엔드의 수집 대기 시간 초과 정리로 처리합니다. 개별 첨부 오류의 부분 성공 처리는 유지합니다. **콜백 중복 방지가 적용된 백엔드를 먼저 업데이트**한 다음 AI 모듈을 재시작하세요. 중복·만료 콜백의 빈 `data.documents` 응답은 정상 처리합니다.

## 코드에서 확인할 부분

| 위치 | 역할 |
| --- | --- |
| [monitoring](app/domains/monitoring) | 작업 접수, 기관별 수집, 파일 처리, 결과 전달 |
| [analysis](app/domains/analysis) | 근거 선택, 공고 분석, 사업 제안·초안, 결과 검증 |
| [tests](tests) | 파싱·작업 흐름·모델 출력·실패 처리 회귀 테스트 |

AI 호출은 횟수와 시간을 제한합니다. 결과를 스키마로 검증하고, 첨부 하나의 읽기 실패는 가능한 경우 경고로 남겨 나머지 처리를 이어갑니다. 사업 제안 후속 처리까지 끝난 뒤 보고서 단계로 넘어가며, 양식 초안은 사용자가 별도로 요청합니다.

### 분석 폴더 구조

`api.py`·`service.py`·`tasks.py`·`config.py`는 분석 API, 작업 접수·실행, 설정을 담당합니다. 세부 기능은 다음 폴더에서 찾을 수 있습니다.

| 폴더 | 역할 |
| --- | --- |
| [context](app/domains/analysis/context) | 회사 프로필, 이전 분석 조회, 공통 입력 지시 |
| [evidence](app/domains/analysis/evidence) | 근거 조사 에이전트·도구, 긴 원문 선택, 버전 비교 |
| [legal](app/domains/analysis/legal) | 법률 검토, 공고 간 비교, 설명 문체 |
| [proposals](app/domains/analysis/proposals) | 사업 제안, 준비도 점수, 양식·초안 작성과 언어 검증 |
| [workflow](app/domains/analysis/workflow) | 분석 에이전트·그래프, 재시도, 기회 점수, 검색용 텍스트 |
| [schemas](app/domains/analysis/schemas) | 요청·응답·결과·전송 형식 |
| [clients](app/domains/analysis/clients) | 백엔드 결과 전송 |

[분석 테스트](tests/domains/analysis)도 같은 역할별 폴더로 구분합니다. 양식 테스트 자료는 `proposals/fixtures`에 있습니다.

## 회사 정보 입력

[회사 프로필](app/domains/analysis/context/company_profile.py)의 실제 회사 소개·역량·공개 수행 사례와 확인 필요 항목만 분석에 사용합니다. 가상 고객 업무·GPU 수요·예산·인력·서류 상태를 담은 데모 프로필은 제거했습니다.

변경은 AI 서버 재시작 후 새로 생성하는 공고 분석·사업 제안·양식 초안·공고 간 비교에 적용됩니다. 기존 저장 결과는 자동 갱신되지 않습니다. 호환용 `usesDemoProfile` 필드는 유지하지만 새로 생성하는 결과에는 false를 설정합니다.

## 수정 공고의 비교 입력

내부 분석 요청의 `documents[].previousVersion.attachments`에 직전 버전의 첨부 목록을 전달합니다. 각 항목은 현재 첨부와 같은 `attachmentId`, `fileName`, `extractedText` 형식이며, 파싱 완료 상태의 본문만 포함하고 읽기 실패는 `extractedText: null`로 전달합니다.

- `attachments: []`는 직전 버전에 첨부가 없었다는 뜻입니다.
- 필드 미전달 또는 `null`은 이전 목록을 알 수 없다는 뜻으로 AI가 구형 요청도 수용합니다.
- 파일명이 같은 첨부의 추출 본문을 비교하고 추가·삭제·중복 이름·비교 불가를 구분합니다. 변경 근거와 비교 한계를 기존 분석 입력에 포함하며 화면 응답 형식은 유지합니다.

상세 비교 범위와 제한은 [설계 문서](../docs/DESIGN.md)의 ‘수정 공고의 설명’을 참고합니다.

## 테스트

```powershell
.\.venv\Scripts\python.exe -m pytest
```

외부 사이트·모델·Spring Boot 호출은 대체 객체로 검증합니다. 테스트 통과가 실제 공고 전체에 대한 분석 정확도를 뜻하지는 않습니다.

## 보고서 제출 안내 모델

보고서 생성 시 `gpt-5-mini`가 대상·기한·제출처·문의·서류를 원문 근거와 함께 추출합니다. 사업 제안이 없어도 동작하며 저장 체크리스트는 후보로만 사용합니다. 모델이 원문에서 확인한 서류·제출 조건으로 목록을 구성하고, 확인되지 않은 기존 항목은 자동 복사하지 않습니다. 보고서 전체 문장 생성이나 발송 단계에는 모델을 호출하지 않습니다.

기존 `OPENAI_API_KEY`를 사용합니다. 기본 활성화이며 `REPORT_BRIEF_ENABLED=false`로 끄면 기존 템플릿 추출만 사용합니다. `.env.example`의 `REPORT_BRIEF_*` 설정으로 모델, 입력 길이, 시간과 동시 처리 상한을 조정합니다. 기본은 공고당 30초/전체 60초/동시 3건/16,000자입니다. 실패는 해당 공고만 대체 처리하며 보고서 하단에 안내합니다.

같은 입력은 프로세스 메모리의 최대 256개/24시간 캐시로 재사용합니다. 재시작 후에는 다시 호출될 수 있습니다. 저장된 보고서 재발송에는 모델 호출이 없고 기존 보고서 내용도 자동 변경되지 않습니다. 모델 출력을 원문 인용과 대조해도 의미 해석 오류나 누락이 모두 해결되는 것은 아닙니다.

백엔드는 최소 보고서와 보강 요청을 먼저 DB에 저장합니다. 보고서 요청의 선택 필드 `jobId`가 있으면 Python은 이를 접수 응답과 결과 전달에 그대로 사용합니다. 결과 전송 실패 시 백엔드가 임대 만료 후 새 시도 ID로 다시 요청하며, 이전 ID의 늦은 결과는 기존 본문을 덮어쓰지 않습니다. 백엔드와 Python을 함께 재시작해 계약을 적용하세요.

첨부 입력은 총 120,000자/파일당 40,000자 안에서 분량을 배분하여 뒤쪽 공고문 누락을 줄입니다. 모델 입력 16,000자는 공고·접수 안내를 우선 배정합니다. 누락된 필드는 저장 분석 문장이 아닌 표제가 있는 원문 구절로만 보완합니다. 기존 보고서는 재전송이 아니라 새 보고서 생성이 필요합니다.

기존 환경변수에 `REPORT_BRIEF_MODEL=gpt-5-nano`가 지정되어 있으면 기본값 변경보다 우선합니다. mini를 적용하려면 `REPORT_BRIEF_MODEL=gpt-5-mini`로 바꾼 뒤 AI 서버를 재시작하세요. PDF 줄 복원은 새 파싱부터 적용되며 기존 파싱 결과의 재사용은 별도입니다.

## 분석 시도 ID와 보고서 조건 검증

`POST /internal/monitoring/analysis-jobs`의 선택 UUID `jobId`는 백엔드 복구 작업이 지정한 시도 ID입니다. 지정된 경우 접수 응답과 분석·제안 콜백에 같은 값을 사용하며 생략하면 기존처럼 생성합니다. 백엔드는 만료·이전 시도의 결과를 409로 거부합니다. Python의 프로세스 내 작업 자체가 영속화되는 것은 아니며 백엔드 DB 작업이 중단을 복구합니다.

보고서 표시 검증은 수치·단위·비교 방향을 함께 검사합니다. 한국어 자격 제한·부정·예외 조건은 검증된 원문 인용 전체를 보존하고, 길거나 역할 연결이 불명확한 자격 표는 원문 확인 안내로 남깁니다. 모든 자연어 의미 오류를 보장해서 제거하는 기능은 아니며 기존 저장 보고서는 자동 변경하지 않습니다.

## 제안·보고서 모델 지연 진단

Python 서버를 재시작한 뒤 새 모니터링을 실행하면 기존 콘솔에 `model_call_*`, `report_brief_*` 진단이 추가됩니다. 별도 설정이나 추가 API 호출은 없으며 모델·시간 제한·재시도·결과 상태 정책은 바꾸지 않습니다.

- `model_call_start/stage/progress/finish`: `call_id`로 한 호출을 추적합니다. 제안은 `detection_id/version_id`, 보고서는 `run_id/version_id`로 공고를 구분합니다. 실제 모델 설정, 시간 제한, 입력/원문 문자 수, 구간별 경과 시간이 기록됩니다. 대기 중에는 15초마다 현재 단계가 표시됩니다.
- 단계는 `queued`(동시 실행 차례 대기), `input_build`(입력 구성), `model_prepare/model_invocation`(호출 준비), `model_wait`(모델 호출·통신·SDK 처리), `response_parsing`(후속 구조화 응답 해석), `final_validation`(제안 검증)입니다. 보고서 검증 시간은 `report_brief_validation`으로 별도 기록합니다.
- `model_call_response`: 콜백에서 확인된 입력·출력·추론 토큰 수, 종료 사유(`finish_reason`), 요청 ID, 제공자 처리시간을 기록합니다. 해당 API 응답에 값이 없으면 생략합니다. 추론 내용이나 모델 응답 본문은 기록하지 않습니다.
- 실패는 `outcome`, `error_type`, `cause_types`, `timeout_kind`, 가능한 HTTP 상태/요청 ID로 구분합니다. `timeout_kind`는 `local_deadline`, `sdk`, `connect`, `read`, `write`, `connection_pool`, `transport` 중 확인 가능한 값입니다. `cancelled`는 전체 제한 또는 상위 작업 종료에 의한 취소이며 `report_brief_batch_finish.total_timeout`을 함께 확인합니다.
- `report_brief_result`: 문서별 모델 성공, 캐시, 동일 입력 공유, 원문 재사용, 실패 대체 및 사유입니다. `report_brief_batch_finish`에는 각 건수와 `degraded`, `total_timeout`이 표시됩니다. `degraded=true`이면 대체 처리된 문서가 있으므로 기존 보고서의 `COMPLETED`만으로 AI 보강이 모두 성공했다고 판단하지 않습니다. `model_success`는 호출/검증 경로를 통과했다는 뜻이며 모든 원문 정보의 완전성을 보장하지 않습니다.

`response_received`는 HTTP 첫 바이트 수신 여부가 아니라 LangChain 모델 종료 콜백 관찰 여부입니다. SDK 내부 해석이나 출력 한도 오류는 HTTP 응답이 왔어도 false일 수 있습니다. 예외가 응답 객체를 제공하면 `http_response_observed=true`와 HTTP 상태를 별도로 기록합니다. 스트리밍을 켜거나 패킷을 추적하지 않으므로 서버 내부 대기와 생성 시간을 항상 분리하거나 첫 토큰 시간을 측정하지는 않습니다. 모델 대기 중 응답 없이 취소되면 해당 구간·시간 초과 종류까지 확인할 수 있으며 제공자 내부 원인은 단정하지 않습니다.

API 키·회사 자료·공고 원문·모델 응답·예외 메시지·전체 HTTP 헤더는 새 진단에 기록하지 않습니다. 오류 메시지 대신 유형과 허용한 메타데이터만 남깁니다.
