package com.laker.postman.codegen.json;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Java JSON-library annotations optionally emitted for renamed properties. */
@Getter
@RequiredArgsConstructor
public enum JavaJsonSerializationStyle {
    PLAIN("Plain POJO", null, null),
    JACKSON2("Jackson 2", "com.fasterxml.jackson.annotation.JsonProperty", "JsonProperty"),
    JACKSON3("Jackson 3", "tools.jackson.annotation.JsonProperty", "JsonProperty"),
    FASTJSON1("fastjson 1", "com.alibaba.fastjson.annotation.JSONField", "JSONField"),
    FASTJSON2("fastjson 2", "com.alibaba.fastjson2.annotation.JSONField", "JSONField"),
    GSON("Gson", "com.google.gson.annotations.SerializedName", "SerializedName"),
    JSONB("JSON-B (Jakarta)", "jakarta.json.bind.annotation.JsonbProperty", "JsonbProperty"),
    MOSHI("Moshi", "com.squareup.moshi.Json", "Json");

    private final String displayName;
    private final String annotationImport;
    private final String annotationName;

    public String renderAnnotation(String jsonName) {
        return switch (this) {
            case JACKSON2, JACKSON3 -> "@" + annotationName + "(\"" + jsonName + "\")";
            case FASTJSON1, FASTJSON2 -> "@" + annotationName + "(name = \"" + jsonName + "\")";
            case GSON, JSONB, MOSHI -> "@" + annotationName + "(\"" + jsonName + "\")";
            case PLAIN -> "";
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
