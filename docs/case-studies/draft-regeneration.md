# 초안 재작성과 실패 복구

**핵심:** 새 결과가 저장될 때까지 기존 초안을 유지하고, 요청 ID와 버전으로 중복·충돌을 구분했습니다.

[프로젝트 소개](../../README.md) · [구현 현황](../PLAN.md)

## 어떤 문제였나

저장된 초안 재사용은 빠르지만, 사용자가 다시 작성을 요청할 때는 새 결과가 필요합니다. 재작성 도중 실패하거나 같은 요청이 재전송되더라도 기존 내용을 잃지 않아야 했습니다.

## 어떻게 바꿨나

| 상황 | 처리 |
| --- | --- |
| 저장한 초안 다시 열기 | 저장본 반환, 모델 호출 없음 |
| 다시 작성 요청 | 완료 캐시를 우회하고 기존 본문·수정 요청 전달 |
| 생성·저장 실패 | 기존 초안 유지 |
| 새 결과 저장 성공 | 본문 교체, 교체 전 결과를 직전 초안으로 보관 |
| 같은 요청 재전송 | 요청 ID로 구분해 중복 처리 방지 |
| 오래된 버전에서 수정 | 저장 전 버전 검사로 충돌 차단 |
| 직전 초안 복원 | 모델 호출 없이 현재·직전 결과 교환 |

예를 들어 ‘A 초안 → 다시 작성 → 생성 실패’에서는 화면과 DB에 A가 남습니다. 새 B 초안 저장까지 성공하면 B로 교체하고 A로 복원할 수 있습니다.

## 무엇을 확인했나

- 백엔드·AI 테스트로 캐시 우회, 실패 시 보존, 동시 요청, 버전 충돌, 복원을 확인했습니다.
- 브라우저의 모의 API로 화면 이탈·재진입, 새로고침, 응답 유실 상황을 확인했습니다.
- 완료 결과는 DB에 저장합니다. 진행 상태는 서버 메모리에 있어 서버 재시작 시 미완료 작업을 자동 재개하지는 않습니다.

<details>
<summary>검증 코드와 실행 방법</summary>

[백엔드 저장·재작성 테스트](../../backend/src/test/java/com/publicmonitor/backend/domain/document/service/ProposalWriteServiceTest.java) · [AI 생성 테스트](../../ai/tests/domains/analysis/test_proposal_writer.py)

각 모듈 폴더에서 실행합니다. 외부 모델은 호출하지 않습니다.

```powershell
# backend
.\gradlew.bat test --tests "*ProposalWriteServiceTest"

# ai
.\.venv\Scripts\python.exe -m pytest tests/domains/analysis/test_proposal_writer.py
```

</details>
