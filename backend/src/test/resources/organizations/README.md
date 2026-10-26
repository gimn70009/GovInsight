# 담당기관 표 회귀 자료

- smart-award-structured.txt는 [중기부 스마트제조혁신 유공 포상 후보자 추가 모집공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071646)의 공식 HWPX 기관 표를 현재 파서로 읽은 결과입니다.
- 주관기관과 중소벤처기업부가 각각 5칸씩 병합되어 있어 해당 값이 반복됩니다. 탭과 빈 셀을 원본 추출 결과대로 보존합니다.
- 기관 추출은 이름을 중소벤처기업부로 반환해야 하고, 근거는 반복 역할/기관을 늘어놓지 않는 실제 인접 셀 구간이어야 합니다. 원본 XML은 test-fixtures/document-tables/smart-award-organizations.xml입니다.

- japan-globalstars.txt는 [KIAT 일본 다자간 국제공동기술개발사업 공고](https://www.kiat.or.kr/front/board/boardContentsView.do?MenuId=b159c9dac684471b87256f1e25404f5e&board_id=90&contents_id=bb3b60e71514452a9351124a309f62af)의 공식 ZIP(ATCH_FILE_ID=P001652405, SEQ=1)과 HWP(SEQ=3)를 현재 파서로 읽어 필요한 행만 발췌한 회귀 자료입니다. 2026-10-21 확인했습니다.
- ZIP의 (양식1) 국문연구개발계획서 (공동펀딩형).hwpx에서 ‘주 관 기 관’과 ‘참여연구원’이 연결된 표 행, HWP의 두 접수처와 제출서류 표 머리글·7번 행을 보존합니다. 발췌를 한 입력에서 검사하기 위해 HWP 앞에는 [파일: …] 경계를 추가했습니다. 기관·인력명을 교체하거나 표의 탭을 정리하지 않았습니다.
- 양식의 참여연구원과 ‘주관 및 공동연구개발기관 기업부설연구소 인정서’는 담당기관이 아닙니다. 접수처 역할의 유레카 사무국과 한국산업기술진흥원만 반환하고 원문 발췌를 보존해야 합니다.
