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
| `ANALYSIS_CONCURRENCY` | 문서 분석 동시 처리 수, 기본 `2` |

처리 시간·재시도·ZIP 제한은 [.env.example](.env.example)에서 확인할 수 있습니다.

## 실행

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

- [상태 확인](http://127.0.0.1:8000/health): 정상 응답은 `{"status":"UP"}`입니다.
- [API 문서](http://127.0.0.1:8000/docs): 내부 작업 요청과 응답 형식을 확인합니다.
- 전체 모니터링은 [백엔드](../backend/README.md)도 실행한 뒤 화면에서 시작합니다.

Windows용 UUID·해시 호환 모듈을 사용하도록 `ai` 폴더의 가상환경 Python으로 실행합니다. 별도의 네이티브 모듈 차단 여부는 해당 PC 정책에 따라 달라질 수 있습니다.

## 코드에서 확인할 부분

| 위치 | 역할 |
| --- | --- |
| [monitoring](app/domains/monitoring) | 작업 접수, 기관별 수집, 파일 처리, 결과 전달 |
| [analysis](app/domains/analysis) | 근거 선택, 공고 분석, 사업 제안·초안, 결과 검증 |
| [tests](tests) | 파싱·작업 흐름·모델 출력·실패 처리 회귀 테스트 |

AI 호출은 횟수와 시간을 제한합니다. 결과를 스키마로 검증하고, 첨부 하나의 읽기 실패는 가능한 경우 경고로 남겨 나머지 처리를 이어갑니다. 사업 제안 후속 처리까지 끝난 뒤 보고서 단계로 넘어가며, 양식 초안은 사용자가 별도로 요청합니다.

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

[영문 초안 검증 개선](../docs/case-studies/english-draft.md) · [초안 재작성과 실패 복구](../docs/case-studies/draft-regeneration.md)
