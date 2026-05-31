package com.kazimir.declinemapper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** The final {@code result.json} payload — root of the schema described in the assignment. */
public record MappingResult(
        @JsonProperty("provider") String provider,
        @JsonProperty("version")  String version,
        @JsonProperty("mappings") List<Mapping> mappings,
        @JsonProperty("summary")  Summary summary
) {
}
