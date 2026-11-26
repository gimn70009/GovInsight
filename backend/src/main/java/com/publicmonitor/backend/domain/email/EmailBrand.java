package com.publicmonitor.backend.domain.email;

/** Embedded branding shared by both report email formats. */
public final class EmailBrand {
    public static final String CONTENT_ID = "govinsight-brand";
    public static final String RESOURCE = "brand/govinsight-logo.png";

    private EmailBrand() {}

    public static String html() {
        return """
                <img src="cid:govinsight-brand" width="160" height="40" alt="GovInsight" style="display:block;width:160px;height:40px;border:0">
                """;
    }
}
