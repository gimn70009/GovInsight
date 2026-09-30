package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import java.util.List;

/** 게시판 주소와 선별 규칙은 서버에서 관리하고 사용자 설정은 별도로 보존한다. */
final class MonitoringSourceDefaults {

    static final List<Source> SOURCES = List.of(
            new Source("산업통상부", "사업공고", "산업·통상·에너지 분야의 지원사업 및 사업공고를 수집합니다.",
                    "https://www.motir.go.kr/kor/article/ATCL2826a2625", "/kor/article/ATCL2826a2625/"),
            new Source("과학기술정보통신부", "사업공고", "과학기술·정보통신 분야의 지원사업 공고를 수집합니다.",
                    "https://www.msit.go.kr/bbs/list.do?sCode=user&mId=311&mPid=121&pageIndex=1&bbsSeqNo=100", "/bbs/view.do"),
            new Source("기후에너지환경부", "공지·공고", "기후·에너지·환경 분야의 공지 및 공고를 수집합니다.",
                    "https://mcee.go.kr/home/web/board/list.do?menuId=10524&boardMasterId=39", "/home/web/board/read.do"),
            new Source("고용노동부", "국고보조사업", "고용·노동 분야의 국고보조사업 정보를 수집합니다.",
                    "https://www.moel.go.kr/info/govsupport/govsupportcon/govSupportSubList.do", "govSupportSubView.do"),
            new Source("국토교통부", "공지사항", "국토·교통 분야의 주요 공지사항을 수집합니다.",
                    "https://www.molit.go.kr/USR/BORD0201/m_69/LST.jsp?id=N01_B", "/USR/BORD0201/m_69/DTL.jsp"),
            new Source("한국산업기술진흥원", "사업공고", "한국산업기술진흥원의 산업기술 지원사업과 사업공고를 수집합니다.",
                    "https://www.kiat.or.kr/front/board/boardContentsListPage.do?MenuId=b159c9dac684471b87256f1e25404f5e&board_id=90",
                    "/front/board/boardContentsView.do")
    );

    private MonitoringSourceDefaults() {
    }

    record Source(String organizationName, String boardName, String description, String listUrl, String urlIncludePattern) {
        MonitoringSource create() {
            return MonitoringSource.create(organizationName, boardName, description, listUrl, urlIncludePattern, 2, true);
        }
    }
}
