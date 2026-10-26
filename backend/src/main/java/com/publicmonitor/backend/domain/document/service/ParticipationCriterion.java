package com.publicmonitor.backend.domain.document.service;

import java.util.regex.Pattern;

/** Stable categories shared by every notice; patterns identify topics, never eligibility. */
enum ParticipationCriterion {
    BUSINESS_FORM("COMPANY", "기업 기본 요건", "기업 형태", "법인\\s*사업자|개인\\s*사업자|예비\\s*창업자|외국\\s*법인|기업\\s*형태|기관\\s*유형|기업(?=\\s*만\\s*가능)|(?<![가-힣])기업(?=\\s*[,·ㆍ])|대학(?!원)|연구기관|의료기관"),
    COMPANY_SIZE("COMPANY", "기업 기본 요건", "기업 규모", "중소기업(?!진흥|기본법)|중견기업|소상공인|스타트업|대기업|소기업(?!진흥)|기업\\s*규모"),
    BUSINESS_AGE("COMPANY", "기업 기본 요건", "업력", "업력|창업\\s*(?:후|한|[0-9])|설립\\s*(?:후|일|[0-9])|회사성립연월일"),
    LOCATION("COMPANY", "기업 기본 요건", "소재지", "(?:서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충청|전라|경상|제주|국내|해외)[가-힣]*\\s*(?:소재|지역|내\\s)|소재지|본사|본점|사업장|이전\\s*예정"),
    LEGAL_FORM("COMPANY", "기업 기본 요건", "설립 형태", "주식회사|유한회사|협동조합|(?:설립|법인)\\s*형태|조합(?!원)"),
    REPRESENTATIVE("COMPANY", "기업 기본 요건", "대표자 조건", "대표자|대표의\\s*(?:연령|나이|성별|국적)|청년\\s*대표|여성\\s*대표|장애인\\s*대표|재창업자"),
    REVENUE("FINANCIAL", "재무 요건", "매출액", "매출(?:액|실적|규모)?"),
    CAPITAL("FINANCIAL", "재무 요건", "자본금·자본잠식", "자본금|자기자본|자본\\s*(?:전액\\s*)?잠식|완전자본잠식"),
    CREDIT("FINANCIAL", "재무 요건", "부채비율·신용등급·기업평가등급", "부채\\s*비율|신용\\s*등급|기업\\s*평가\\s*등급|이자보상"),
    INVESTMENT("FINANCIAL", "재무 요건", "투자유치 실적", "투자\\s*유치|누적\\s*투자|VC\\s*투자"),
    EXPORT("FINANCIAL", "재무 요건", "수출 실적", "수출(?:액|비중|실적|희망|\\s*\\(?희망|\\s*기업|\\s*중소기업)?"),
    IP("TECHNOLOGY", "기술·인증 요건", "특허·지식재산권", "특허|지식재산권|산업재산권|실용신안"),
    CERTIFICATION("TECHNOLOGY", "기술·인증 요건", "기업 인증", "기업\\s*인증|(?<![가-힣])벤처\\s*기업|이노비즈|메인비즈|기업\\s*부설\\s*연구소|연구\\s*개발\\s*전담\\s*부서|여성기업|장애인기업|사회적기업|social\\s*venture|소셜벤처"),
    TRL("TECHNOLOGY", "기술·인증 요건", "기술 성숙도", "TRL|기술\\s*성숙도"),
    QUALITY("TECHNOLOGY", "기술·인증 요건", "품질·보안 인증", "ISO\\s*[0-9]*|GS\\s*인증|KC\\s*인증|ISMS|품질\\s*인증|보안\\s*인증"),
    TCB("TECHNOLOGY", "기술·인증 요건", "기술평가 등급", "TCB|기술\\s*평가\\s*등급"),
    SECTOR("INDUSTRY", "사업·산업 요건", "업종·산업 분야", "업종|산업\\s*분야|지원\\s*분야|KSIC|한국표준산업분류|제조업|제조(?=\\s*[’'」])|AI[·ㆍ/\\s]|바이오|반도체|정보통신기업"),
    PRODUCT("INDUSTRY", "사업·산업 요건", "제품·서비스 요건", "시제품|상용화\\s*제품|제품\\s*보유|서비스\\s*보유|사업화\\s*단계|CBAM\\s*대상\\s*품목"),
    EXPERIENCE("INDUSTRY", "사업·산업 요건", "유관산업 경험", "사업\\s*실적|납품\\s*(?:이력|실적)|레퍼런스|관련\\s*(?:분야|산업).*?(?:경험|실적)"),
    BUSINESS_ITEM("INDUSTRY", "사업·산업 요건", "사업 아이템", "사업\\s*아이템|공고\\s*취지|과제\\s*(?:내용|분야)|과제제안요구서|과제제안요청서"),
    HEADCOUNT("WORKFORCE", "인력 요건", "상시근로자 수", "상시\\s*(?:근로자|종업원)|직원\\s*수|종업원\\s*수"),
    EXPERTS("WORKFORCE", "인력 요건", "전문 인력", "전문\\s*인력|전담\\s*인력|연구\\s*인력|석[·ㆍ]?박사|박사|석사|자격증\\s*보유|연구책임자"),
    EMPLOYMENT("WORKFORCE", "인력 요건", "고용 실적", "고용\\s*(?:실적|증가|인원)|청년\\s*고용|신규\\s*채용"),
    DUPLICATE("RESTRICTIONS", "이력·참여 제한 요건", "중복 수혜 제한", "중복\\s*(?:수혜|지원|과제)|(?<![가-힣])기\\s*수혜|(?<![가-힣])기\\s*지원|기존.*?참여기업|동시(?:에)?\\s*(?:참여|수행)|과거\\s*지원|수혜\\s*이력"),
    COMPLETION("RESTRICTIONS", "이력·참여 제한 요건", "졸업·수료 이력", "졸업|수료|프로그램\\s*참여\\s*이력"),
    CLOSURE("RESTRICTIONS", "이력·참여 제한 요건", "휴·폐업 여부", "휴업|폐업|휴[·ㆍ‧/・]폐업|휴[·ㆍ‧/・]?(?:업)?\\s*및\\s*폐업"),
    TAX("RESTRICTIONS", "이력·참여 제한 요건", "국세·지방세 체납", "국세(?!청)|지방세|세금\\s*체납|조세\\s*체납"),
    DEFAULT("RESTRICTIONS", "이력·참여 제한 요건", "연체·신용불량·회생·파산", "연체|신용\\s*불량|채무\\s*불이행|회생|파산|부도"),
    SANCTIONS("RESTRICTIONS", "이력·참여 제한 요건", "정부지원사업 참여 제한 처분", "참여\\s*제한|제재\\s*처분|부정\\s*수급|환수금|제재부가금|의무사항.*?불이행"),
    EXCLUDED_SECTOR("RESTRICTIONS", "이력·참여 제한 요건", "제외 업종", "제외\\s*업종|도박|유흥|금융[·ㆍ‧/・]?보험업|사행성"),
    PLAN("EXECUTION", "사업 수행 관련 요건", "사업 수행 의지·계획", "(?:사업|연구\\s*개발)\\s*계획서|발표\\s*평가|수행\\s*의지|수행\\s*계획|사업\\s*계획"),
    MATCHING("EXECUTION", "사업 수행 관련 요건", "자부담 능력", "자부담|민간\\s*부담금|기관\\s*부담\\s*연구개발비|현금\\s*부담|현물\\s*매칭|매칭\\s*비율"),
    CONSORTIUM("EXECUTION", "사업 수행 관련 요건", "협력 구조", "컨소시[엄움]|공동\\s*연구개발기관|공동기관|참여기관\\s*구성|산학연|수요기업\\s*확약|협약기관|임상시험\\s*참여기관"),
    LINKAGE("EXECUTION", "사업 수행 관련 요건", "상호 연계 요건", "액셀러레이터|지역\\s*거점기관|상호\\s*연계|기관과의\\s*협업"),
    PREF_REGION("PREFERENCE", "우대·가점 항목", "지역 우대", "비수도권|인구\\s*소멸|인구\\s*감소|지역\\s*우대|소외지역"),
    PREF_SOCIAL("PREFERENCE", "우대·가점 항목", "사회적 가치", "ESG|사회적\\s*기업|사회적\\s*가치|일자리|고용"),
    PREF_REPRESENTATIVE("PREFERENCE", "우대·가점 항목", "청년·여성·장애인 대표 우대", "청년기업|여성기업|장애인기업|(?:대표|대표자|대표이사).{0,12}(?:청년|여성|장애인)|(?:청년|여성|장애인).{0,12}대표"),
    PREF_INVESTMENT_EXPORT("PREFERENCE", "우대·가점 항목", "투자유치·수출 실적 우대", "투자|수출"),
    PREF_AWARDS("PREFERENCE", "우대·가점 항목", "정부 포상·수상 이력", "포상|수상|표창"),
    PREF_CLUSTER("PREFERENCE", "우대·가점 항목", "특구·클러스터 입주 우대", "규제\\s*자유\\s*특구|클러스터|특구|입주");

    final String group;
    final String groupLabel;
    final String label;
    final Pattern topic;

    ParticipationCriterion(String group, String groupLabel, String label, String expression) {
        this.group = group;
        this.groupLabel = groupLabel;
        this.label = label;
        this.topic = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
    }
}
