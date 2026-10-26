package com.publicmonitor.backend.domain.document.web.dto;

import java.util.regex.Pattern;

/** Remove only a leading description of the section, never an actual company claim. */
final class ProposalBodyPresentation {
    private static final Pattern INTRO = Pattern.compile(
            "^\\s*(?:본|이|해당)\\s*(?:항목|절|장|부분|작성란)(?:에서는|에는|은|는|에서)\\s+"
            + "[^.!?。]{0,500}?(?:기술|설명|서술|소개|정리|제시|작성)(?:합니다|하겠습니다)[.。]\\s*"
            + "(?=(?:당사(?:는|가|의|에서)|저희|우리\\s*회사))");

    private ProposalBodyPresentation() {}

    static String clean(String body) {
        return body == null ? null : INTRO.matcher(body).replaceFirst("");
    }
}
