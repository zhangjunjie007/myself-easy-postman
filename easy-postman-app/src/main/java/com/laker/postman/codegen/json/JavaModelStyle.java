package com.laker.postman.codegen.json;

/** Java source form used by the JSON model generator. */
public enum JavaModelStyle {
    PLAIN_POJO("Plain POJO"),
    LOMBOK("Lombok"),
    RECORD("Record");

    private final String displayName;

    JavaModelStyle(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
