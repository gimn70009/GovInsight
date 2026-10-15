# 담당기관 표 회귀 자료

- smart-award-structured.txt는 [중기부 스마트제조혁신 유공 포상 후보자 추가 모집공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071646)의 공식 HWPX 기관 표를 현재 파서로 읽은 결과입니다.
- 주관기관과 중소벤처기업부가 각각 5칸씩 병합되어 있어 해당 값이 반복됩니다. 탭과 빈 셀을 원본 추출 결과대로 보존합니다.
- 기관 추출은 이름을 중소벤처기업부로 반환해야 하고, 근거는 반복 역할/기관을 늘어놓지 않는 실제 인접 셀 구간이어야 합니다. 원본 XML은 test-fixtures/document-tables/smart-award-organizations.xml입니다.
