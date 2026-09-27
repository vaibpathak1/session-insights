package io.sessioninsights.common.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/** The one JSON configuration for the wire contracts, shared by producers and consumers. */
public final class WireJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            // newer SDKs may add fields; older collectors ignore them
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            // an unknown event type is a contract violation, not a null
            .disable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            // parse errors must never echo payload content into logs or responses
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private WireJson() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }
}
