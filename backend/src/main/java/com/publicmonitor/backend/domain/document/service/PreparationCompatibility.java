package com.publicmonitor.backend.domain.document.service;

import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Adapts legacy company inputs for display without modifying stored analyses. */
public final class PreparationCompatibility {
    private PreparationCompatibility() {}

    public static String normalize(String json, ObjectMapper mapper) {
        JsonNode root = mapper.readTree(json);
        if (!(root.path("preparation") instanceof ObjectNode preparation)) return json;
        JsonNode inputs = preparation.remove("companyInputs");
        if (inputs == null || !inputs.isArray()) return mapper.writeValueAsString(root);
        ArrayNode agenda = array(preparation, "meetingAgenda", mapper);
        ArrayNode eligibility = array(preparation, "eligibilityChecklist", mapper);
        ArrayNode documents = array(preparation, "submissionDocuments", mapper);
        for (JsonNode item : inputs) {
            String title = item.path("title").asText();
            String compact = title.replaceAll("\\s+", "");
            boolean document = compact.matches(".*(확인서|증명서|계획서|확약서|등기부등본|등록증|현황표|신청서|동의서).*" )
                    && !compact.matches(".*(보유여부|자격|충족여부).*" );
            String origin = item.path("source").path("origin").asText();
            String level = item.path("requirementLevel").asText();
            if (document) {
                appendDistinct(documents, item);
            } else if ((origin.equals("NOTICE_BODY") || origin.equals("ATTACHMENT"))
                    && (level.equals("MANDATORY") || level.equals("CONDITIONAL"))) {
                appendDistinct(eligibility, item);
            } else {
                String text = title + ": " + item.path("detail").asText()
                        + " " + item.path("nextAction").asText();
                JsonNode source = item.path("source");
                if (!source.path("excerpt").asText().isBlank()) {
                    text += " 근거: " + source.path("attachmentName").asText("")
                            + " " + source.path("sectionTitle").asText("")
                            + " " + source.path("location").asText("")
                            + " " + source.path("excerpt").asText();
                }
                boolean exists = false;
                for (JsonNode existing : agenda) {
                    if (key(existing.asText()).equals(key(text))) exists = true;
                }
                if (!exists) agenda.add(text);
            }
        }
        return mapper.writeValueAsString(root);
    }

    private static ArrayNode array(ObjectNode preparation, String name, ObjectMapper mapper) {
        if (preparation.path(name) instanceof ArrayNode array) return array;
        ArrayNode array = mapper.createArrayNode();
        preparation.set(name, array);
        return array;
    }

    private static void appendDistinct(ArrayNode array, JsonNode item) {
        for (JsonNode existing : array) {
            if (existing.equals(item)) return;
        }
        array.add(item);
    }

    private static String key(String value) {
        return value.replaceAll("[\\s\\p{Punct}]", "").toLowerCase(Locale.ROOT);
    }
}
