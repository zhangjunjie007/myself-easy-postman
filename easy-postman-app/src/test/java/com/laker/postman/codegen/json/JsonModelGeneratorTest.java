package com.laker.postman.codegen.json;

import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

public class JsonModelGeneratorTest {
    private static final String SAMPLE = """
            {"user_id": 7, "profile": {"display-name": "Ada"}, "active": true}
            """;

    @Test
    public void shouldGenerateDependencyFreeJavaModel() {
        String code = JsonModelGenerator.generate(SAMPLE, "UserResponse", JsonModelLanguage.JAVA);

        assertTrue(code.contains("public class UserResponse"));
        assertTrue(!code.contains("com.fasterxml.jackson"));
        assertTrue(!code.contains("@JsonProperty"));
        assertTrue(code.contains("private Long userId"));
        assertTrue(code.contains("class UserResponseProfile"));
        assertTrue(code.contains("public Long getUserId() {\n        return userId;\n    }"));
        assertTrue(code.contains("public void setUserId(Long userId) {\n        this.userId = userId;\n    }"));
    }

    @Test
    public void shouldGenerateTypeScriptAndCSharpModels() {
        String typeScript = JsonModelGenerator.generate(SAMPLE, "UserResponse", JsonModelLanguage.TYPESCRIPT);
        String csharp = JsonModelGenerator.generate(SAMPLE, "UserResponse", JsonModelLanguage.CSHARP);

        assertTrue(typeScript.contains("export interface UserResponse"));
        assertTrue(typeScript.contains("'display-name': string"));
        assertTrue(csharp.contains("public class UserResponse"));
        assertTrue(csharp.contains("[JsonPropertyName(\"user_id\")]"));
    }

    @Test
    public void shouldDescribeTheRootTypeForAnArray() {
        String code = JsonModelGenerator.generate("[{\"id\": 1}, {\"id\": 2}]", "User", JsonModelLanguage.TYPESCRIPT);

        assertTrue(code.contains("export type UserItemList = UserItem[]"));
        assertTrue(code.contains("export interface UserItem"));
    }

    @Test
    public void shouldMergeFieldsFromArrayObjectSamplesAndEscapeJavaKeywords() {
        String code = JsonModelGenerator.generate("[{\"id\": 1, \"class\": \"a\"}, {\"id\": 2, \"name\": \"b\"}]",
                "User", JsonModelLanguage.JAVA);

        assertTrue(code.contains("private Long id"));
        assertTrue(code.contains("private String class_"));
        assertTrue(code.contains("private String name"));
        assertTrue(!code.contains("class UserItem2"));
    }

    @Test
    public void shouldMarkFieldsMissingFromSomeArraySamplesAsOptionalInTypeScript() {
        String code = JsonModelGenerator.generate("[{\"id\": 1}, {\"id\": 2, \"name\": \"Ada\"}]",
                "User", JsonModelLanguage.TYPESCRIPT);

        assertTrue(code.contains("name?: string"));
    }

    @Test
    public void shouldKeepUnicodePropertyNamesWithoutDuplicates() {
        String json = "{\"姓名\": \"Ada\", \"年龄\": 18, \"收货地址\": \"Shanghai\"}";

        String java = JsonModelGenerator.generate(json, "Response", JsonModelLanguage.JAVA);
        String csharp = JsonModelGenerator.generate(json, "Response", JsonModelLanguage.CSHARP);
        String typeScript = JsonModelGenerator.generate(json, "Response", JsonModelLanguage.TYPESCRIPT);

        assertTrue(java.contains("private String 姓名"));
        assertTrue(java.contains("private Long 年龄"));
        assertTrue(java.contains("private String 收货地址"));
        assertTrue(java.contains("public String get姓名()"));
        assertTrue(!java.contains("private String value"));
        assertTrue(csharp.contains("public string 姓名"));
        assertTrue(typeScript.contains("姓名: string"));
    }

    @Test
    public void shouldGenerateSelectedJavaLibraryAnnotationForRenamedProperties() {
        String json = "{\"user_id\": 7}";

        String fastjson2 = JsonModelGenerator.generate(json, "Response", JsonModelLanguage.JAVA,
                JavaJsonSerializationStyle.FASTJSON2);
        String jackson = JsonModelGenerator.generate(json, "Response", JsonModelLanguage.JAVA,
                JavaJsonSerializationStyle.JACKSON2);

        assertTrue(fastjson2.contains("import com.alibaba.fastjson2.annotation.JSONField;"));
        assertTrue(fastjson2.contains("@JSONField(name = \"user_id\")"));
        assertTrue(jackson.contains("import com.fasterxml.jackson.annotation.JsonProperty;"));
        assertTrue(jackson.contains("@JsonProperty(\"user_id\")"));
    }

    @Test
    public void shouldGenerateNewtonsoftJsonAttributesForCSharp() {
        String code = JsonModelGenerator.generate("{\"user_id\": 7}", "Response", JsonModelLanguage.CSHARP,
                JavaJsonSerializationStyle.PLAIN, JavaModelStyle.PLAIN_POJO,
                CSharpJsonSerializationStyle.NEWTONSOFT_JSON);

        assertTrue(code.contains("using Newtonsoft.Json;"));
        assertTrue(code.contains("[JsonProperty(\"user_id\")]"));
        assertTrue(!code.contains("System.Text.Json.Serialization"));
    }

    @Test
    public void shouldGenerateLombokAndRecordStyles() {
        String lombok = JsonModelGenerator.generate("{\"user_id\": 7}", "Response", JsonModelLanguage.JAVA,
                JavaJsonSerializationStyle.PLAIN, JavaModelStyle.LOMBOK);
        String record = JsonModelGenerator.generate("{\"user_id\": 7}", "Response", JsonModelLanguage.JAVA,
                JavaJsonSerializationStyle.FASTJSON2, JavaModelStyle.RECORD);

        assertTrue(lombok.contains("import lombok.Data;"));
        assertTrue(lombok.contains("@Data\npublic class Response"));
        assertTrue(!lombok.contains("getUserId()"));
        assertTrue(record.contains("public record Response("));
        assertTrue(record.contains("@JSONField(name = \"user_id\")"));
        assertTrue(record.contains("Long userId"));
    }
}
