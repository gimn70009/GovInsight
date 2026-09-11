package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ZipArchiveContentTest {
    @Test
    void 새_파일이_앞에_추가되어도_기존_초안의_순번을_보존한다() {
        String previous = "[파일: 신청서.hwpx]\n기존 신청서\n\n[파일: 동의서.hwpx]\n기존 동의서";
        String incoming = "[파일: 새양식.hwp]\n신규 양식\n\n[파일: 신청서.hwpx]\n기존 신청서\n\n[파일: 동의서.hwpx]\n기존 동의서";
        String manifest = """
                [{"fileName":"새양식.hwp","status":"COMPLETED","partIndex":0},
                 {"fileName":"신청서.hwpx","status":"COMPLETED","partIndex":1},
                 {"fileName":"동의서.hwpx","status":"COMPLETED","partIndex":2}]
                """;
        var result = ZipArchiveContent.reconcile(previous, incoming, manifest);
        assertThat(ZipArchiveContent.parts(result.text())).extracting(ZipArchiveContent.Part::name)
                .containsExactly("신청서.hwpx", "동의서.hwpx", "새양식.hwp");
        assertThat(ZipArchiveContent.entries(result.entriesJson())).extracting(ZipArchiveContent.Entry::partIndex)
                .containsExactly(2, 0, 1);
        var again = ZipArchiveContent.reconcile(result.text(), incoming, manifest);
        assertThat(again).isEqualTo(result);
    }

    @Test
    void 중복_파일을_묶어도_다른_양식의_순번을_당기지_않는다() {
        String previous = "[파일: 양식.hwp]\n같은 내용\n\n[파일: 양식.hwpx]\n같은 내용\n\n[파일: 다른양식.pdf]\n다른 내용";
        String incoming = "[파일: 양식.hwpx]\n같은 내용\n\n[파일: 다른양식.pdf]\n다른 내용";
        String manifest = """
                [{"fileName":"양식.hwp","status":"DUPLICATE","partIndex":0},
                 {"fileName":"양식.hwpx","status":"COMPLETED","partIndex":0},
                 {"fileName":"다른양식.pdf","status":"COMPLETED","partIndex":1}]
                """;
        var result = ZipArchiveContent.reconcile(previous, incoming, manifest);
        var parts = ZipArchiveContent.parts(result.text());
        assertThat(parts.get(0).text()).isEmpty();
        assertThat(parts.get(1).text()).isEqualTo("같은 내용");
        assertThat(parts.get(2).text()).isEqualTo("다른 내용");
        assertThat(ZipArchiveContent.entries(result.entriesJson())).extracting(ZipArchiveContent.Entry::partIndex)
                .containsExactly(1, 1, 2);
    }

    @Test
    void 기존_메타데이터가_없는_수집_결과도_읽을_수_있다() {
        assertThat(ZipArchiveContent.entries("invalid")).isEmpty();
        assertThat(ZipArchiveContent.entries("null")).isEmpty();
        assertThat(ZipArchiveContent.reconcile("이전 본문", "새 본문", null).text()).isEqualTo("새 본문");
    }
    @Test
    void 앞에서_복구된_파일이_기존_파일의_식별자를_차지하지_않는다() {
        String previous = "[파일: 기존.hwpx]\n예전 추출 본문";
        String incoming = "[파일: 복구.hwp]\n예전 추출 본문\n\n[파일: 기존.hwpx]\n개선된 추출 본문";
        String manifest = """
                [{"fileName":"복구.hwp","status":"COMPLETED","partIndex":0},
                 {"fileName":"기존.hwpx","status":"COMPLETED","partIndex":1}]
                """;
        var result = ZipArchiveContent.reconcile(previous, incoming, manifest);
        assertThat(ZipArchiveContent.parts(result.text()).getFirst().name()).isEqualTo("기존.hwpx");
        assertThat(ZipArchiveContent.entries(result.entriesJson())).extracting(ZipArchiveContent.Entry::partIndex)
                .containsExactly(1, 0);
    }

}
