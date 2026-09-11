# 영문 초안이 반복해서 실패하던 문제

**핵심:** 출력 표기를 먼저 정리하고, 실제로 미완성인 문장을 따로 검증했습니다.

[프로젝트 소개](../../README.md) · [주요 구현 내용](../IMPLEMENTATION.md)

## 어떤 문제였나

영문 신청서 내용을 생성하고도 “문장이 완결된 문단과 종결 부호로 작성해야 합니다” 검증에서 반복 실패했습니다. 줄바꿈·강조 표기처럼 코드로 정리할 수 있는 형식 문제까지 재생성으로 이어지고 있었습니다.

## 어떻게 바꿨나

1. 양식의 작성 지침에서 국문·영문을 판정합니다.
2. 불필요한 Markdown·제목을 정리하고, 문장 중간의 줄바꿈을 연결합니다.
3. 정리된 문단에서 종결·언어를 검사합니다. 약어·따옴표·괄호를 고려하되, 미완성 절이나 작성 안내가 남으면 거부합니다.

예를 들어 아래 두 줄은 한 문장으로 정리합니다.

```text
입력: We will develop and
      evaluate the model
결과: We will develop and evaluate the model.
```

`We will develop and`처럼 뒤 내용이 없는 문장은 마침표만 붙여 통과시키지 않습니다.

## 무엇을 확인했나

- 줄바꿈·강조·코드 블록 등 표기만 다른 정상 본문은 회귀 테스트에서 **추가 모델 호출 없이** 처리했습니다.
- 잘못된 언어, 미완성 문장, 본문에 남은 내부 근거 표식은 거부하는지 확인했습니다.
- 오류 로그에는 원문 전체 대신 실패한 항목·문단 위치를 남기도록 했습니다.

이 검증은 문장 형식과 처리 흐름을 확인합니다. 회사 사실과 실제 제출 적합성은 별도 검토가 필요합니다.

<details>
<summary>검증 코드와 실행 방법</summary>

[본문 정리·검증 코드](../../ai/app/domains/analysis/proposal_language.py) · [형식 회귀 테스트](../../ai/tests/domains/analysis/test_english_body_format.py) · [언어 판정 테스트](../../ai/tests/domains/analysis/test_proposal_language.py)

`ai` 폴더에서 실행합니다. 외부 모델은 호출하지 않습니다.

```powershell
.\.venv\Scripts\python.exe -m pytest tests/domains/analysis/test_english_body_format.py tests/domains/analysis/test_proposal_language.py
```

</details>
