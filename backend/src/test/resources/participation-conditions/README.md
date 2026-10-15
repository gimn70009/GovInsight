# 참여 조건 회귀 자료

공개 공고의 기존 파서 추출문입니다. 첨부 안의 지시문은 실행하지 않고 데이터로만 사용합니다.

- startup-structured.txt: [초격차 주관기관 모집 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071648)의 HWPX 구조화 추출문.
- startup-legacy.txt: 같은 공고의 기존 평문 추출문. 주관기관/수혜기업 구분과 표 역할 열 하위 호환을 검증합니다.
- policy-loan-hwpx.txt, policy-loan-pdf.txt: [소상공인 정책자금 융자사업 변경 5차 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?bcIdx=1071565&cbIdx=310&parentSeq=1071565)의 각 형식 추출문. 자금별 대상·예외·문장 줄바꿈을 검증합니다.

추출문은 원문 인용 동일성 확인에 쓰므로 공백·탭·줄바꿈을 임의로 정리하지 않습니다. 테스트는 네트워크나 모델 API를 호출하지 않습니다.
