package com.laker.postman.codegen.json;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** C# JSON-library attributes optionally emitted for renamed properties. */
@Getter
@RequiredArgsConstructor
public enum CSharpJsonSerializationStyle {
    SYSTEM_TEXT_JSON("System.Text.Json", "System.Text.Json.Serialization", "JsonPropertyName"),
    NEWTONSOFT_JSON("Newtonsoft.Json", "Newtonsoft.Json", "JsonProperty");

    private final String displayName;
    private final String annotationImport;
    private final String annotationName;

    public String renderAnnotation(String jsonName) {
        return "[" + annotationName + "(\"" + jsonName + "\")]";
    }

    @Override
    public String toString() {
        return displayName;
    }
}
