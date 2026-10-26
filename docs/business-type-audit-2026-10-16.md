# 사업 유형 분류 조사 및 개선 결과 — 2026-10-16

- 작업 브랜치: `fix/business-type-diagnostics`
- 기준 코드: `b2b0b89` (`develop`)
- 범위: 사업 유형의 원문 검증·조회 보완·진단 로그. 분류 모델·프롬프트·10개 유형 체계·화면·API 계약·DB 스키마는 유지했습니다.

## 확인한 원인과 수정

### 1. 실제 공고문도 번호가 붙은 별첨으로 시작하면 검증 대상에서 사라짐

기존 AI 검증의 `_notice_part`와 백엔드 조회의 `noticePart`는 줄 시작의 `붙임/별첨/별지/서식 + 번호`를 만나면 그 뒤를 모두 서식으로 제외했습니다. 따라서 `붙임 1 공고문` 다음에 실제 지원 내용이 있어도 AI의 근거 인용 검증에서 탈락하고, 백엔드 규칙 보완에서도 같은 내용을 찾지 못했습니다. 본문 추출 자체가 실패한 문제가 아니라 **추출된 원문을 검증용으로 다시 제한하는 단계의 오류**입니다.

이 오류는 보존된 인증 공고에 표제를 추가한 형식 변형에서 재현했습니다. 그 표제가 실제 공고 원문에 존재했다거나 운영에서 몇 번 발생했는지는 확인하지 않았습니다. 번호가 붙어도 공고·모집 안내·사업 안내임이 명시된 표제는 보존하도록 양쪽을 수정했습니다. 신청서·사업계획서·서식·작성 예시가 함께 있거나 번호만 있는 불명확한 자료는 계속 제외합니다. 뒤에 나타나는 실제 신청서도 계속 차단합니다.

### 2. 서로 다른 실패가 모두 빈 분류로 합쳐져 원인 추적이 어려움

기존 AI 검증은 실패 분기를 모두 `None`으로 반환했고 백엔드도 저장 JSON 손상·인용 불일치·출처 누락 등을 구분하지 않았습니다. 이제 내부 진단 결과와 로그에 사유 코드를 남깁니다. AI 검증에서는 감지 ID·버전 ID, 백엔드 조회에서는 같은 ID와 최종 보완 경로를 기록합니다. 원문·인용문·파일명·URL·모델 내부 추론은 로그에 남기지 않습니다.

백엔드는 저장 분류가 제외된 사유와 최종 화면 분류 경로를 분리합니다. 예를 들어 `stored_reason=QUOTE_MISMATCH`여도 실제 본문으로 보완했다면 `decision_reason=RULE_CLASSIFICATION`과 최종 유형을 함께 확인할 수 있습니다. 동일 파일명은 파싱 상태를 포함한 전체 첨부 목록에서 중복을 검사하여 출처를 임의로 고르지 않습니다. 백엔드의 최소 인용 길이도 AI와 동일하게 공백을 제외한 5자로 맞췄습니다.

## 검증

- 백엔드 전체: 1,114개, 124개 테스트 묶음. 실패·오류·건너뜀 0.
- AI 전체: 1,810개 통과. 추가된 로그 테스트의 전체 실행 순서 의존성을 수정한 뒤 재실행했습니다.
- 변경 Python 파일의 Ruff 검사 통과.
- 같은 인증 공고 인용을 기준 커밋의 AI 검증 함수와 수정 함수에 직접 대입하여 `분류 제외 → VALIDATED`도 확인했습니다. 실제 모델 호출은 아니며 결과는 `backend/build/business-type-audit/ai-validation-comparison.json`에 보존했습니다.
- 최종 `git diff --check` 통과, 8080 포트 비움 확인.
- 저장된 분석 → 백엔드 근거 재검증 → 상세 응답의 유형·원문 보존 및 로그 비노출 테스트 통과.
- 원문·경계 재현 20건: 수정 전 19건 일치 → 수정 후 20건 일치. 아래 표에 전부 기록합니다.
- 실제 OpenAI 호출, 운영 DB의 기존 오분류 조회·수정, 실제 웹/메일 화면 검증은 수행하지 않았습니다. 프런트엔드·메일 렌더러는 변경하지 않았습니다.

## 남은 한계와 다음 판단

- 기존 공식 원문 14개 사례는 수정 전에도 기대 결과와 일치했습니다. 오늘 확인한 회귀 집합에서 **운영에 가장 흔한 오분류 유형이나 전체 정확도는 산정할 수 없습니다.**
- 정답은 에이전트가 기존 분류 기준·원문을 대조한 것이며 독립적인 사람 검수나 새로운 기관/새 공고에 대한 평가가 아닙니다. 같은 공고의 형식 차이를 별도 공고로 계산하지 않았습니다.
- `MODEL_UNCLASSIFIED`는 모델 응답의 유형이 비어 있다는 사실만 뜻합니다. 원문 선택에서 중요한 문장이 빠졌는지, 모델이 판단하지 못했는지, 일반공지여서 비워 둔 것인지는 해당 사례를 추가 확인해야 합니다.
- `EXCLUDED_SOURCE_SECTION`은 원문에는 인용이 있지만 검증 대상 범위에는 없다는 뜻입니다. 서식·예시·압축 내부 제외나 기존 200,000자 제한이 원인일 수 있으며 무조건 정상 근거라는 의미가 아닙니다.
- 현재 지원과 향후 연계가 한 인용에 섞인 경우 등은 기존의 보수적 제외 정책을 유지합니다. 인용을 원문에 맞게 좁힐 수 있는지 사례별 확인이 필요합니다.
- 이미지 전용 PDF, 원문 누락, 긴 문서 선택 누락과 실제 모델의 의미 판단 정확도는 이번 수정의 해결 범위가 아닙니다.
- 다음 운영 분석부터 진단 사유를 모으면 실제 빈도를 기준으로 입력 보완·검증 보완·모델 비교 중 우선순위를 정할 수 있습니다. 이번 결과만으로 파인튜닝이나 새 분류기가 필요하다고 결론 내리지 않습니다.

## 적용과 원인 확인 방법

백엔드 재시작 후 상세 조회와 그 상세 데이터를 재사용하는 메일에 적용됩니다. AI 검증 변경과 AI 진단 로그는 AI 서비스 재시작 이후 실행되는 분석부터 적용됩니다. 자동 재분석·재수집·재발송은 수행하지 않았습니다. 이전 AI 단계에서 이미 버려져 저장되지 않은 유형을 복원하는 것은 아니며, 기존 원문에서 규칙 보완이 가능한 경우에만 조회 시 보완됩니다.

운영 로그에서 `Business type validation.`과 `Business type resolution.`을 동일한 `detection_id`/`version_id`로 연결해 확인합니다. `source_index`는 0부터 시작하는 인용 번호이고 파일명이나 원문 자체가 아닙니다.

| 진단 | 뜻 | 우선 확인할 부분 |
|---|---|---|
| `MODEL_UNCLASSIFIED` | AI 응답에 유형 없음 | 일반공지 여부, 전달 원문과 모델 응답 |
| `NO_USABLE_SOURCE` | 검증에 쓸 비어 있지 않은 원문 없음 | 본문·첨부 추출 및 제외된 서식 |
| `SOURCE_NOT_READY` | 백엔드 첨부 파싱 상태가 완료 아님 | 첨부의 실패·미지원·대기 상태 |
| `SOURCE_TEXT_UNAVAILABLE` | 참조 출처의 추출문 없음 | 해당 첨부 추출 결과 |
| `SOURCE_NOT_FOUND` / `AMBIGUOUS_SOURCE` | 참조 출처가 없거나 파일명 중복 | 저장된 출처와 실제 첨부 목록 |
| `EXCLUDED_SOURCE` / `EXCLUDED_SOURCE_SECTION` | 파일 또는 해당 구간이 검증 대상에서 제외됨 | 서식·예시·공고 경계 및 길이 제한 |
| `QUOTE_MISMATCH` | 인용이 현재 원문에 없음 | 이전 버전 인용·임의 요약·잘못된 출처 |
| `UNSUPPORTED_EVIDENCE` | 향후·과거·지원 불가 등 배제 문구 포함 | 현재 실제 지원 활동의 별도 근거 |
| `NO_SAVED_ANALYSIS` / `NO_SAVED_CLASSIFICATION` | 저장 분석/유형이 없음 | 분석 대기 여부, 이전 결과의 필드 유무 |
| `INVALID_*` | 저장 분류의 JSON/필드 형식 손상 | 저장 계약과 결과 변환 경로 |
| `NO_SUPPORT_ACTIVITY` | 규칙으로 확인 가능한 지원 활동 없음 | 지원 내용 구역·범주 경계 |
| `GENERAL_NOTICE` | 최종 일반공지 판정 | 신규 모집과 혼동하지 않았는지 |
| `SAVED_CLASSIFICATION` / `RULE_CLASSIFICATION` | 저장 분류 또는 원문 규칙으로 최종 유형 채택 | AI/후처리 중 어느 경로인지 |

## 재현 방법

```powershell
# backend 디렉터리
.\gradlew.bat test --tests '*BusinessTypeAuditTest' --console=plain
# 저장소 루트
ai\.venv\Scripts\python.exe ai\scripts\summarize_business_type_audit.py
```

기대 결과와 출처는 `backend/src/test/resources/business-type/cases.json`, 기준선은 같은 폴더의 `baseline.json`에 있습니다. 결과는 `backend/build/business-type-audit/current.json` 및 `report.md`로 생성됩니다.

---

## 사례별 수정 전후 결과

기준선: b2b0b89 / 기대 결과 일치: 19/20 → 20/20

공식 공고 출처 8개, 원문·형식·경계 사례 20개입니다. 같은 공고의 PDF/HWPX와 변형은 독립 공고로 세지 않습니다.

라벨은 저장소 분류 기준과 보존된 원문을 에이전트가 대조한 결과입니다. 독립적인 사람 검수나 실제 모델 호출 평가가 아니며 운영 정확도를 뜻하지 않습니다.

| 사례 | 자료 종류 | 기대 | 수정 전 | 수정 후 | 현재 결정 / 저장값 진단 |
|---|---|---|---|---|---|
| ax-hwpx | public_source_snapshot | RND | RND | RND | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| ax-pdf | public_source_snapshot | RND | RND | RND | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| bio | public_source_snapshot | COMMERCIALIZATION | COMMERCIALIZATION | COMMERCIALIZATION | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| certification-hwpx | public_source_snapshot | CERTIFICATION | CERTIFICATION | CERTIFICATION | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| certification-pdf | public_source_snapshot | CERTIFICATION | CERTIFICATION | CERTIFICATION | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| clean-structured | public_source_snapshot | ADOPTION | ADOPTION | ADOPTION | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| clean-legacy | public_source_snapshot | ADOPTION | ADOPTION | ADOPTION | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| loan-hwpx | public_source_snapshot | FINANCE | FINANCE | FINANCE | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| loan-pdf | public_source_snapshot | FINANCE | FINANCE | FINANCE | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| startup-structured | public_source_snapshot | DEMONSTRATION | DEMONSTRATION | DEMONSTRATION | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| startup-legacy | public_source_snapshot | DEMONSTRATION | DEMONSTRATION | DEMONSTRATION | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| cbam | public_source_snapshot | ADOPTION | ADOPTION | ADOPTION | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |
| documents-only | public_source_fragment | REVIEW_REQUIRED | REVIEW_REQUIRED | REVIEW_REQUIRED | NO_SUPPORT_ACTIVITY / NO_SAVED_ANALYSIS |
| award-fragment | public_source_fragment | REVIEW_REQUIRED | REVIEW_REQUIRED | REVIEW_REQUIRED | NO_SUPPORT_ACTIVITY / NO_SAVED_ANALYSIS |
| notice-appendix-heading | format_variant | CERTIFICATION | REVIEW_REQUIRED | CERTIFICATION | SAVED_CLASSIFICATION / VALIDATED |
| form-example | synthetic_boundary | REVIEW_REQUIRED | REVIEW_REQUIRED | REVIEW_REQUIRED | NO_USABLE_SOURCE / NO_SAVED_ANALYSIS |
| duplicate-name | synthetic_boundary | REVIEW_REQUIRED | REVIEW_REQUIRED | REVIEW_REQUIRED | NO_SUPPORT_ACTIVITY / AMBIGUOUS_SOURCE |
| fabricated-quote | synthetic_boundary | REVIEW_REQUIRED | REVIEW_REQUIRED | REVIEW_REQUIRED | NO_SUPPORT_ACTIVITY / QUOTE_MISMATCH |
| designation | synthetic_boundary | NOT_APPLICABLE | NOT_APPLICABLE | NOT_APPLICABLE | GENERAL_NOTICE / NO_SAVED_ANALYSIS |
| recruitment-over-general | synthetic_boundary | ADOPTION | ADOPTION | ADOPTION | RULE_CLASSIFICATION / NO_SAVED_ANALYSIS |

변경 사례: notice-appendix-heading

현재 결정 사유:

- `GENERAL_NOTICE`: 1건
- `NO_SUPPORT_ACTIVITY`: 4건
- `NO_USABLE_SOURCE`: 1건
- `RULE_CLASSIFICATION`: 13건
- `SAVED_CLASSIFICATION`: 1건

## 출처 및 기대 판단

- **ax-hwpx**: 현재 모집 대상은 연구개발 과제이며 사업목적에 AI·SW 응용 기술개발과 솔루션 확보를 명시한다. [공식 공고](https://www.bizinfo.go.kr/web/lay1/bbs/S1T122C128/AS/74/view.do?pblancId=PBLN_000000000127197)
- **ax-pdf**: 현재 모집 대상은 연구개발 과제이며 사업목적에 AI·SW 응용 기술개발과 솔루션 확보를 명시한다. [공식 공고](https://www.bizinfo.go.kr/web/lay1/bbs/S1T122C128/AS/74/view.do?pblancId=PBLN_000000000127197)
- **bio**: 지원내용의 현재 지원은 기술·제품·서비스 개발 사업화 자금이며 후속 R&D 연계와 구분한다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071550)
- **certification-hwpx**: 지원내용은 인증 획득에 필요한 인증비·시험비·컨설팅비이다. 해외시장 진출이라는 목적보다 직접 지원 활동을 우선한다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071736)
- **certification-pdf**: 지원내용은 인증 획득에 필요한 인증비·시험비·컨설팅비이다. 해외시장 진출이라는 목적보다 직접 지원 활동을 우선한다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071736)
- **clean-structured**: 사업목적에 작업장 환경개선 지원, 세부지원내용에 안전장치 교체·개보수와 안전장비를 명시한다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071619)
- **clean-legacy**: 사업목적에 작업장 환경개선 지원, 세부지원내용에 안전장치 교체·개보수와 안전장비를 명시한다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071619)
- **loan-hwpx**: 융자한도·금리·융자조건과 직접대출·대리대출이 실제 지원 방식이다. 수혜자의 스마트공장·고용 요건을 지원 활동으로 보지 않는다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071565)
- **loan-pdf**: 융자한도·금리·융자조건과 직접대출·대리대출이 실제 지원 방식이다. 수혜자의 스마트공장·고용 요건을 지원 활동으로 보지 않는다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071565)
- **startup-structured**: 주관기관의 지원내용에 공공 연계 실증(PoC) 관리가 있으며 조달 연계는 후속 활동이다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071648)
- **startup-legacy**: 주관기관의 지원내용에 공공 연계 실증(PoC) 관리가 있으며 조달 연계는 후속 활동이다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071648)
- **cbam**: 지원 유형1은 MRV 인프라 구축, 유형2는 제3자 검증이다. 동등한 선택형에서는 원문의 유형1을 대표로 사용한다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071591)
- **documents-only**: 보존된 자료는 제출서류·접수 구간뿐이며 지원 활동을 확인할 수 없다. 전체 공고가 일반공지라는 의미는 아니다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071591)
- **award-fragment**: 보존된 자료는 제출서류·접수 구간뿐이며 지원 활동을 확인할 수 없다. 전체 공고가 일반공지라는 의미는 아니다. [공식 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071647)
- **notice-appendix-heading**: 공고문을 붙임 1로 표시한 형식 변형이다. 붙임 번호만으로 공고 전체를 신청서 예시로 제외하면 안 된다.
- **form-example**: 신청서 작성 예시의 지원 활동은 현재 공고의 근거가 아니다.
- **duplicate-name**: 동일한 파일명이 중복되어 원문 출처를 특정하지 못하면 저장된 분류를 채택하지 않는다.
- **fabricated-quote**: 원문에 없는 근거 인용은 허용하지 않는다.
- **designation**: 기관 지정 사실 안내이며 지원 활동과 신규 모집이 없다.
- **recruitment-over-general**: 과거 일반공지 판단보다 현재 원문의 모집 및 지원 활동을 우선한다.
