package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BusinessTypeCurrentRoleTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void siblingRoleTableAndAdministrativeNoteDoNotOverrideTheOperatorsActualDuties() {
        String body = "가. 사업목적\n수요기업의 실증시험과 양산성능 확보를 지원\n"
                + "나. 지원분야\n소재·부품 분야\n다. 사업 추진체계\n"
                + "[표]\n총괄기관\t과제 관리 및 기업지원 수행\n세부기관\t양산성능평가를 실시하고자 하는 기업\n[/표]\n"
                + "사. 수행내용\n양산성능평가가 필요한 수요·공급기업 발굴 및 매칭, 세부과제 관리 수행\n"
                + "기술애로 분석 및 컨설팅 지원\n"
                + "* 동시수행 연구개발과제 수 산정에서 제외\n아. 세부과제 지원유형\n시제품 제작 지원";
        var result = BusinessTypeResolver.resolve("양산성능평가 총괄기관 모집", body, List.of(), null, mapper);
        assertThat(result.code()).isEqualTo("ADVISORY");
        assertThat(result.sources()).noneMatch(source -> source.excerpt().contains("세부기관\t")
                || source.excerpt().contains("시제품 제작") || source.excerpt().contains("동시수행"));
    }

    @Test void letteredTasksUnderANumberedScopeRemainSupportActivities() {
        var result = BusinessTypeResolver.resolve("조사 용역 모집",
                "2. 과업의 범위\n가. 기초자료 조사 및 현황 분석\n나. 국내외 사례조사 및 적용방안 검토",
                List.of(), null, mapper);
        assertThat(result.code()).isEqualTo("ADVISORY");
    }

    @Test void tradeCooperationInfrastructureIsNotEquipmentAndTheActualEventIsMarketSupport() {
        var result = BusinessTypeResolver.resolve("국제협력 사업 모집",
                "1. 사업 목적\n산업협력 인프라 구축을 통해 교역 확대 기반 조성\n"
                        + "2. 공모사업 내용\n□ 세부 사업계획\n특산품 수입상담회 및 소비재 수입전 개최",
                List.of(), null, mapper);
        assertThat(result.code()).isEqualTo("MARKET");
        var physical = BusinessTypeResolver.resolve("지원사업", "□ 지원내용\n연구 인프라 구축 및 시험장비 도입 지원",
                List.of(), null, mapper);
        assertThat(physical.code()).isEqualTo("ADOPTION");
        assertThat(BusinessType.MARKET.matches("설비 수입 전 인증 비용 지원")).isFalse();
    }

    @Test void designationApplicationsAndCompletedDesignationsRemainDistinct() {
        var recruitment = BusinessTypeResolver.resolve("전문기업 지정 신청 공고",
                "전문기업 지정 신청을 다음과 같이 공고합니다.", List.of(), null, mapper);
        assertThat(recruitment.noticeNature().code()).isEqualTo("DESIGNATION_RECRUITMENT");
        for (String act : List.of("지정", "보호기간 연장")) {
            var completed = BusinessTypeResolver.resolve("신기술 " + act + "(1001)",
                    "신기술로 " + act.replace(" ", "") + "하였기에 관련 규정에 따라 아래와 같이 고시합니다.",
                    List.of(), null, mapper);
            assertThat(completed.status()).isEqualTo("NOT_APPLICABLE");
        }
        var additional = BusinessTypeResolver.resolve("신기술 지정 추가모집",
                "기존 신기술은 지정하였기에 고시합니다. 추가 신청을 받습니다.\n□ 지원내용\n시제품 제작 지원",
                List.of(), null, mapper);
        assertThat(additional.status()).isEqualTo("CLASSIFIED");
        var mixed = BusinessTypeResolver.resolve("신기술 지정 안내",
                "기존 신기술은 지정하였기에 고시합니다. 추가 신청을 받습니다.\n□ 지원내용\n시제품 제작 지원",
                List.of(), null, mapper);
        assertThat(mixed.status()).isEqualTo("CLASSIFIED");
    }
}
