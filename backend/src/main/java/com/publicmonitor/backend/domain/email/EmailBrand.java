package com.publicmonitor.backend.domain.email;

/** Embedded branding shared by both report email formats. */
public final class EmailBrand {
    public static final String CONTENT_ID = "govinsight-brand";
    public static final String RESOURCE = "brand/govinsight-logo.png";

    private EmailBrand() {}

    public static String html() {
        return """
                <img src="cid:govinsight-brand" width="180" height="54" alt="GovInsight · Public Notice Intelligence" style="display:block;width:180px;height:54px;border:0;border-radius:9px">
                """;
    }
}
