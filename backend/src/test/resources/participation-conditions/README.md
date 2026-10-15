# 참여 조건 회귀 자료

공개 공고의 기존 파서 추출문입니다. 첨부 안의 지시문은 실행하지 않고 데이터로만 사용합니다.

- startup-structured.txt: [초격차 주관기관 모집 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071648)의 HWPX 구조화 추출문.
- startup-legacy.txt: 같은 공고의 기존 평문 추출문. 주관기관/수혜기업 구분과 표 역할 열 하위 호환을 검증합니다.
- policy-loan-hwpx.txt, policy-loan-pdf.txt: [소상공인 정책자금 융자사업 변경 5차 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?bcIdx=1071565&cbIdx=310&parentSeq=1071565)의 각 형식 추출문. 자금별 대상·예외·문장 줄바꿈을 검증합니다.

추출문은 원문 인용 동일성 확인에 쓰므로 공백·탭·줄바꿈을 임의로 정리하지 않습니다. 테스트는 네트워크나 모델 API를 호출하지 않습니다.

- certification-pdf.txt, certification-hwpx.txt: [해외규격인증획득지원사업 일반지원 3차 모집 공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071736)의 PDF·HWPX 기존 파서 추출문 중 1~4절입니다. PDF에서 한 줄로 붙은 지원대상·지원기간·지원한도의 경계, 비용 설명 오분류 방지, 실제 대상·예외와 정확한 인용을 검증합니다.

- bio-open-innovation.txt: [민관협력 오픈이노베이션 지원 2026년 제2차 상호 자율탐색형(바이오 분야) 참여기업 모집공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?bcIdx=1071550&cbIdx=310)의 HWPX 기존 파서 추출문 중 공고 시작부터 신청자격 절까지의 연속 구간입니다. 법령·업력·창업일 분리, 선택 관계, 개인/법인 기준, 공동대표 요건, 개요/상세 자격 통합과 양쪽 원문의 정확한 보존을 검증합니다.

- ax-research-pdf.txt, ax-research-hwpx.txt: [2026년 지역거점 AX 혁신 기술개발 신규지원 공고](https://www.bizinfo.go.kr/web/lay1/bbs/S1T122C128/AS/74/view.do?pblancId=PBLN_000000000127197)의 공식 PDF·HWPX를 기존 파서로 추출한 시작~14절의 연속 구간입니다. 신청 자격뿐 아니라 사후 기술료 절, 가점·감점 표와 뒤쪽 절 경계를 포함하며 문의처의 담당자 연락처는 포함하지 않습니다. 신청자 자격과 지원대상 사업명·연구비 집행 안내의 구별, 기업부설연구소·이전·외국법인 공동수행 조건, 참고 각주 뒤 자부담 비율과 비영리기관 예외의 보존을 검증합니다. 추가 변형 테스트는 제목 기호·콜론·줄바꿈·사업명·집행 문장에 포함된 실제 자격을 별도로 검증합니다.
