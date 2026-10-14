# 문서 표 파싱 회귀 자료

- `startup-table.xml`: [중기부 초격차 스타트업 주관기관 모집공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071648)의 공식 HWPX에서 추출한 제출서류 표 XML입니다. 공고의 문장과 실제 셀 주소·병합 범위를 보존하며 XML 네임스페이스 접두어만 ElementTree가 다시 직렬화했습니다.
- 실제 헤더의 `제출 서류`는 구분/서류명 두 열을 병합합니다. 첫 4개 서류의 필수 표시와 첫 3개 서류의 `기관장 직인본 (HWP 또는 PDF)`도 병합되어 있습니다. 마지막 확인서의 민간기관 조건을 다른 행에 적용하지 않아야 합니다.
- Python은 이 XML을 읽은 결과를 `backend/src/test/resources/submission-documents/startup-structured.txt`와 비교합니다. Java는 그 추출 결과로 5개 서류, 파일 형식·직인·발표 대체·민간기관 조건을 검증합니다.
- DOCX·XLSX는 테스트에서 실제 ZIP/XML 파일을 생성합니다. HWP는 실제 레코드 구조를 갖는 바이트와 OLE 스트림 대체 객체를 사용합니다. 외부 문서·AI API를 호출하지 않습니다.


- cbam-table.xml: [중기부 CBAM 대응 인프라구축 사업 4차 모집공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071591)의 공식 HWPX 제출표 XML입니다. Python 추출 결과가 backend/src/test/resources/submission-documents/cbam-structured.txt의 표와 일치하는지 검사합니다. 이 텍스트는 실제 공고 전체의 새 파싱 결과로, Java에서 11개 서류·유형별 O/X·서류명 안의 해당 시 조건·공통 발급/유효 조건을 검증합니다. 머리글은 ‘제출서류 목록’이며, 기존 평문 회귀 자료만으로 놓쳤던 새 표 형식의 누락을 재현합니다.


- smart-award-organizations.xml: [중기부 스마트제조혁신 유공 포상 후보자 추가 모집공고](https://www.mss.go.kr/site/smba/ex/bbs/View.do?cbIdx=310&bcIdx=1071646)의 공식 HWPX 기관 표 XML입니다. 역할과 기관명이 각각 가로 5칸에 병합되어 있으며 중간 1칸은 비어 있습니다. Python은 추출 결과를 backend/src/test/resources/organizations/smart-award-structured.txt와 비교하고, Java는 반복 역할명이 기관명에 붙지 않는지와 실제 연속 원문 근거를 검증합니다.
