package com.publicmonitor.backend.domain.settings.web.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdScalarDeserializer;

public class SettingsDaysDeserializer extends StdScalarDeserializer<Integer> {
    public SettingsDaysDeserializer() { super(Integer.class); }

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Integer.class,
                    "기간은 일 단위 정수로 입력해 주세요.");
        }
        return parser.getIntValue();
    }
}
