package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubmissionNoteFormatterTest {
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
}
