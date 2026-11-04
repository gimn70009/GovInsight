package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubmissionNoteFormatterTest {
    @Test
    void 제출조건_뒤에_붙은_문의_표제와_깨진_글머리표를_표시하지_않는다() {
        String condition = "제출서류 등에 누락·착오가 있더라도 신청마감일 이후 수정·보완·교체 등 일체 불가";
        for (String marker : new String[]{"□", "\uF06F", "\uFFFD", ""}) {
            String raw = "* " + condition + " " + marker
                    + " 신청·접수 시스템(K-Startup) 문의 :국번없이 1357 (시스템운영팀)";
            assertThat(SubmissionNoteFormatter.format(raw)).isEqualTo(condition + ".");
        }
        assertThat(SubmissionNoteFormatter.format("※ " + condition + "\n문의처: 서류 제출 지원센터"))
                .isEqualTo(condition + ".");
        assertThat(SubmissionNoteFormatter.format("* " + condition
                + " \uF06F 신청·접수 시스템(K-Startup 문의 :국번없이 1357"))
                .isEqualTo(condition + ".");
        assertThat(SubmissionNoteFormatter.format("* 문의처: 서류 제출 지원센터")).isEmpty();

        assertThat(SubmissionNoteFormatter.actionable("* 문의처: 제출서류 확인 지원센터"))
                .isFalse();
    }

    @Test
    void 문의라는_단어가_포함된_실제_제출조건과_기호는_보존한다() {
        for (String condition : new String[]{
                "제출서류는 담당자에게 문의 후 보완하여 제출",
                "문의처로 제출한 서류는 인정하지 않음",
                "제출서류는 PDF·HWP 중 택 1, 10MB 이하이며 O/X를 표시",
                "모든 서류는 제출하되, 장애가 발생한 경우 문의처 확인 후 2일 이내 보완"}) {
            assertThat(SubmissionNoteFormatter.format(condition)).isEqualTo(condition + ".");
        }
    }


    @Test
    void 날짜_기준과_숫자_범위_필수표현을_보존하면서_문장을_정리한다() {
        assertThat(SubmissionNoteFormatter.format("* 신청일 포함 2개월 이내 발급분"))
                .isEqualTo("신청일 포함 2개월 이내에 발급된 서류여야 합니다.");
        assertThat(SubmissionNoteFormatter.format("* 마감일 기준 기간유효 必"))
                .isEqualTo("마감일 기준으로 유효한 서류여야 합니다.");
        assertThat(SubmissionNoteFormatter.format("* 가점은 모집공고일 기준으로 해당 여부를 검토하며, 한도는 최대 5점, 증빙자료(제출자료) 미제출 시 불인정"))
                .isEqualTo("가점은 모집공고일 기준으로 해당 여부를 검토합니다. 한도는 최대 5점입니다. 증빙자료(제출자료) 미제출 시 인정되지 않습니다.");
    }

    @Test
    void 부정_예외_선택조건을_삭제하거나_제출의무로_바꾸지_않는다() {
        for (String condition : new String[]{"신청일 포함 1개월 이내 발급분 제외", "개인기업은 사업자등록증 제출 면제", "확인서 또는 증명서 중 택 1", "해당 기업만 제출", "접수 마감일 이후 발급분은 인정하지 않음"}) {
            String formatted = SubmissionNoteFormatter.format(condition);
            assertThat(formatted).isEqualTo(condition.replace("발급분 제외", "발급된 서류 제외") + ".");
        }
    }

    @Test
    void 대상없는_빈제출문구만_제외하고_특정대상_면제는_보존한다() {
        for (String empty : new String[]{"(제출서류 없음)", "※ 제출 서류 불필요", "구비서류 해당 없음"}) {
            assertThat(SubmissionNoteFormatter.empty(empty)).isTrue();
            assertThat(SubmissionNoteFormatter.actionable(empty)).isFalse();
        }
        assertThat(SubmissionNoteFormatter.empty("개인기업은 제출서류 없음")).isFalse();
        assertThat(SubmissionNoteFormatter.actionable("개인기업은 제출서류 없음")).isTrue();
    }
    @Test
    void 전체_신청서류_공통_표현과_개별_서류_조건을_구별한다() {
        for (String note : new String[]{"모든 제출서류는 PDF로 제출", "전체 신청 서류는 원본 제출", "공통 구비서류는 접수일까지 제출", "모든 작성서류는 서명 필수"}) {
            assertThat(SubmissionNoteFormatter.common(note)).isTrue();
        }
        assertThat(SubmissionNoteFormatter.common("인증(지정) 유효기간은 공고일 기준 90일 이상")).isFalse();
        assertThat(SubmissionNoteFormatter.common("신청서는 대표자 서명 후 제출")).isFalse();
    }
}
