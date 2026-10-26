package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ReportContactPresentationTest {
    @Test void institutionsHaveSeparatePhoneEmailAndDetailListsWithoutSourceBullets() {
        var groups = ReportContactPresentation.groups("▸ IRIS 시스템 관련: IRIS 콜센터 또는 IRIS 홈페이지 사용문의 게시판: 1877-2041\n"
                + "□ KIST 양자기술활용연구거점사업단: 02-958-7253, 02-958-7255, contact@example.org, second@example.org");
        assertThat(groups).hasSize(2);
        assertThat(groups.getFirst().label()).isEqualTo("IRIS 시스템 관련: IRIS 콜센터 또는 IRIS 홈페이지 사용문의 게시판");
        assertThat(groups.getFirst().phones()).containsExactly("1877-2041");
        assertThat(groups.getLast().label()).isEqualTo("KIST 양자기술활용연구거점사업단");
        assertThat(groups.getLast().phones()).containsExactly("02-958-7253", "02-958-7255");
        assertThat(groups.getLast().emails()).containsExactly("contact@example.org", "second@example.org");
        assertThat(groups.getLast().details()).isEmpty();
    }

    @Test void unlabeledContinuationChannelsBelongToThePreviousContactEvenAfterItsFirstPhone() {
        var groups = ReportContactPresentation.groups("▶ 사업지원팀: 02-0000-1111\n이메일: apply@example.org\n"
                + "전화: 02-0000-1112 (내선 123)\n※ 평일 09:00~18:00, 공휴일 제외");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().label()).isEqualTo("사업지원팀");
        assertThat(groups.getFirst().phones()).containsExactly("02-0000-1111", "02-0000-1112 (내선 123)");
        assertThat(groups.getFirst().emails()).containsExactly("apply@example.org");
        assertThat(groups.getFirst().details()).containsExactly("평일 09:00~18:00, 공휴일 제외");
    }

    @Test void everyExplicitContactSurvivesIncludingSharedNumbersForDifferentRoles() {
        var groups = ReportContactPresentation.groups("접수 문의: 지원팀 02-0000-1111 / 기술 문의: 연구팀 02-0000-1111\n"
                + "계약 문의: 계약팀 02-0000-3333\n시스템 문의: 전산팀 02-0000-4444");
        assertThat(groups).hasSize(4);
        assertThat(groups).extracting(ReportContactPresentation.Contact::label)
                .containsExactly("접수 문의: 지원팀", "기술 문의: 연구팀", "계약 문의: 계약팀", "시스템 문의: 전산팀");
        assertThat(groups.get(0).phones()).containsExactly("02-0000-1111");
        assertThat(groups.get(1).phones()).containsExactly("02-0000-1111");
    }

    @Test void repeatedChannelsDeduplicateOnlyInsideTheSameExplicitRole() {
        var groups = ReportContactPresentation.groups("지원팀: 02-0000-1111, apply@example.org\n"
                + "지원팀: 02-0000-1111, apply@example.org\n연구팀: 02-0000-1111");
        assertThat(groups).hasSize(2);
        assertThat(groups.getFirst().phones()).containsExactly("02-0000-1111");
        assertThat(groups.getFirst().emails()).containsExactly("apply@example.org");
    }

    @Test void sourcePhoneFormattingExtensionsAndOperationalConditionsArePreserved() {
        var groups = ReportContactPresentation.groups("지원실\n☎ (02) 1234-5678 내선 42, 02-1234-5679/5680\n"
                + "이메일: apply@example.org (우편 접수 후 이메일 제출)\n문의 가능 시간: 평일 09:00~18:00");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().label()).isEqualTo("지원실");
        assertThat(groups.getFirst().phones()).containsExactly("(02) 1234-5678 내선 42", "02-1234-5679/5680");
        assertThat(groups.getFirst().details()).containsExactly("(우편 접수 후 이메일 제출)", "문의 가능 시간: 평일 09:00~18:00");
    }

    @Test void sourcePublisherAndSeparateProceduresDoNotBecomeSubmissionContacts() {
        var groups = ReportContactPresentation.groups("공고기관: 예시부 02-0000-0000\n"
                + "중복성 제기: 검토팀\n02-0000-1111\n이의 신청: 심사팀 02-0000-2222\n"
                + "사업 문의: 지원팀 02-0000-3333");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().label()).isEqualTo("사업 문의: 지원팀");
        assertThat(groups.getFirst().phones()).containsExactly("02-0000-3333");
    }

    @Test void contactWithoutPhoneStillKeepsItsExplicitBoardOrDepartment() {
        var groups = ReportContactPresentation.groups("□ 홈페이지 이용 문의 게시판");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().label()).isEqualTo("홈페이지 이용 문의 게시판");
        assertThat(groups.getFirst().phones()).isEmpty();
        assertThat(groups.getFirst().emails()).isEmpty();
    }

    @Test void communicationWordsInRolesAndConditionsAreNotRemovedAsFieldLabels() {
        var groups = ReportContactPresentation.groups("이메일 문의 지원팀: apply@example.org\n이메일 문의만 가능\n전화 문의는 불가");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().label()).isEqualTo("이메일 문의 지원팀");
        assertThat(groups.getFirst().emails()).containsExactly("apply@example.org");
        assertThat(groups.getFirst().details()).containsExactly("이메일 문의만 가능", "전화 문의는 불가");
    }

    @Test void emptyOrUnknownContactUsesTheExistingSourceCheckMessage() {
        assertThat(ReportContactPresentation.lines("원문 확인 필요")).containsExactly("원문에서 확인해 주세요.");
        assertThat(ReportContactPresentation.lines(null)).containsExactly("원문에서 확인해 주세요.");
    }

    @Test void channelTextIsNotInterpretedAsHtml() {
        var groups = ReportContactPresentation.groups("<img src=x onerror=alert(1)> 지원팀: 02-0000-0000");
        assertThat(groups.getFirst().label()).isEqualTo("<img src=x onerror=alert(1)> 지원팀");
        assertThat(groups.getFirst().phones()).containsExactly("02-0000-0000");
    }
    @Test void institutionHeadingOwnsFollowingPhonesInsteadOfPreviousCenter() {
        var groups = ReportContactPresentation.groups("IRIS 콜센터: 1877-2041\n"
                + "예시 양자기술활용연구거점사업단\n02-0000-7253");
        assertThat(groups).hasSize(2);
        assertThat(groups.getFirst().phones()).containsExactly("1877-2041");
        assertThat(groups.getLast().label()).isEqualTo("예시 양자기술활용연구거점사업단");
        assertThat(groups.getLast().phones()).containsExactly("02-0000-7253");
    }

    @Test void phoneShapedEmailIsOneIntactEmailChannel() {
        var groups = ReportContactPresentation.groups("문의: 02-1234-5678@example.org, 02-0000-1234");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().emails()).containsExactly("02-1234-5678@example.org");
        assertThat(groups.getFirst().phones()).containsExactly("02-0000-1234");
        assertThat(ReportEmailTemplate.render("보고서", "▸ 공고\n• 문의 담당: 02-1234-5678@example.org"))
                .contains("02-1234-5678@example.org");
    }

    @Test void businessMetadataDoesNotBecomeAContactOrAContactDetail() {
        var groups = ReportContactPresentation.groups("사업명 : 퀀텀 플랫폼 차세대 연구거점사업단 지원\n"
                + "IRIS 콜센터: 1877-2041\n사업 기간: 2026년~2029년\n과제명: 공동연구실 신규과제\n"
                + "이메일: help@example.org");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().label()).isEqualTo("IRIS 콜센터");
        assertThat(groups.getFirst().phones()).containsExactly("1877-2041");
        assertThat(groups.getFirst().emails()).containsExactly("help@example.org");
        assertThat(groups.getFirst().details()).isEmpty();
    }

    @Test void repeatedInstitutionContactPrefixDoesNotDuplicateTheSameCenter() {
        var groups = ReportContactPresentation.groups("IRIS 문의처: IRIS 콜센터 1877-2041\n"
                + "평일 09:00~18:00\nIRIS 콜센터: 1877-2041\n평일 09:00~18:00");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().label()).isEqualTo("IRIS 콜센터");
        assertThat(groups.getFirst().phones()).containsExactly("1877-2041");
        assertThat(groups.getFirst().details()).containsExactly("평일 09:00~18:00");
    }

    @Test void meaningfulRolesAtTheSameInstitutionAndNumberAreNotCombined() {
        var groups = ReportContactPresentation.groups("기술 문의처: IRIS 콜센터 1877-2041\n"
                + "접수 문의처: IRIS 콜센터 1877-2041");
        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(ReportContactPresentation.Contact::label)
                .containsExactly("기술 문의처: IRIS 콜센터", "접수 문의처: IRIS 콜센터");
        assertThat(groups).allSatisfy(group -> assertThat(group.phones()).containsExactly("1877-2041"));
    }

    @Test void wholeNumberWrapperIsRemovedAndCourtesyDoesNotBecomeAnInstruction() {
        var groups = ReportContactPresentation.groups("주택공사 선교통환경처 (055-922-3880)로 문의하시기 바랍니다.");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().label()).isEqualTo("주택공사 선교통환경처");
        assertThat(groups.getFirst().phones()).containsExactly("055-922-3880");
        assertThat(groups.getFirst().details()).isEmpty();
    }

    @Test void wholeNumberWrappersAreRemovedWhileAreaCodesAndExtensionsRemainIntact() {
        var groups = ReportContactPresentation.groups("문의처: 지원팀 (055-922-3880 내선 12), "
                + "(055-922-3881) 내선 13, (02) 1234-5678, 02-1234-5679 (내선 14)");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().phones()).containsExactly("055-922-3880 내선 12",
                "055-922-3881 내선 13", "(02) 1234-5678", "02-1234-5679 (내선 14)");
        assertThat(groups.getFirst().details()).isEmpty();
    }

    @Test void restrictionsRemainWhenEmptyCourtesyIsRemoved() {
        var groups = ReportContactPresentation.groups("지원팀: help@example.org\n로 문의하시기 바랍니다.\n"
                + "전화 문의 금지\n이메일 문의만 가능\n상담 시간: 평일 09:00~18:00\n"
                + "이메일로 문의하시기 바랍니다.");
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().details()).containsExactly("전화 문의 금지", "이메일 문의만 가능",
                "상담 시간: 평일 09:00~18:00", "이메일로 문의하시기 바랍니다.");
    }

    @Test void separateFactRowsMergeChannelsAndDetailsForTheSameExplicitContact() {
        var contacts = new java.util.ArrayList<ReportContactPresentation.Contact>();
        contacts.addAll(ReportContactPresentation.groups("IRIS 문의처: IRIS 콜센터 1877-2041\n평일 09:00~18:00"));
        contacts.addAll(ReportContactPresentation.groups("IRIS 콜센터: 1877-2041, help@example.org\n평일 09:00~18:00"));
        contacts.addAll(ReportContactPresentation.groups("기술 문의: IRIS 콜센터 1877-2041"));
        var groups = ReportContactPresentation.merge(contacts);
        assertThat(groups).hasSize(2);
        assertThat(groups.getFirst().label()).isEqualTo("IRIS 콜센터");
        assertThat(groups.getFirst().phones()).containsExactly("1877-2041");
        assertThat(groups.getFirst().emails()).containsExactly("help@example.org");
        assertThat(groups.getFirst().details()).containsExactly("평일 09:00~18:00");
        assertThat(groups.getLast().label()).isEqualTo("기술 문의: IRIS 콜센터");
    }

    @Test void nestedExtensionParenthesesSurviveOnlyTheOuterNumberWrapperRemoval() {
        var groups = ReportContactPresentation.groups("지원팀: (055-922-3880 (내선 12)), (055) 922-3881 (내선 13)");
        assertThat(groups.getFirst().phones()).containsExactly("055-922-3880 (내선 12)", "(055) 922-3881 (내선 13)");
        assertThat(groups.getFirst().details()).isEmpty();
    }
}
