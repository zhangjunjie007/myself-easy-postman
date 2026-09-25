package com.laker.postman.codegen.json;

import tools.jackson.databind.JsonNode;
import com.laker.postman.util.JsonUtil;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generates editable model definitions from a JSON example. */
@UtilityClass
public class JsonModelGenerator {
    public static String generate(String json, String rootTypeName, JsonModelLanguage language) {
        return generate(json, rootTypeName, language, JavaJsonSerializationStyle.PLAIN);
    }

    public static String generate(String json, String rootTypeName, JsonModelLanguage language,
                                  JavaJsonSerializationStyle javaSerializationStyle) {
        return generate(json, rootTypeName, language, javaSerializationStyle, JavaModelStyle.PLAIN_POJO);
    }

    public static String generate(String json, String rootTypeName, JsonModelLanguage language,
                                  JavaJsonSerializationStyle javaSerializationStyle, JavaModelStyle javaModelStyle) {
        return generate(json, rootTypeName, language, javaSerializationStyle, javaModelStyle,
                CSharpJsonSerializationStyle.SYSTEM_TEXT_JSON);
    }

    public static String generate(String json, String rootTypeName, JsonModelLanguage language,
                                  JavaJsonSerializationStyle javaSerializationStyle, JavaModelStyle javaModelStyle,
                                  CSharpJsonSerializationStyle csharpSerializationStyle) {
        JsonNode root = JsonUtil.readTree(json);
        Context context = new Context();
        Type rootType = infer(root, context, typeName(rootTypeName, "Response"));
        return switch (language) {
            case JAVA -> renderJava(context.models, rootType, javaSerializationStyle, javaModelStyle);
            case TYPESCRIPT -> renderTypeScript(context.models, rootType);
            case CSHARP -> renderCSharp(context.models, rootType, csharpSerializationStyle);
        };
    }

    private static Type infer(JsonNode node, Context context, String suggestedName) {
        if (node == null || node.isNull()) return Type.UNKNOWN;
        if (node.isTextual()) return Type.STRING;
        if (node.isBoolean()) return Type.BOOLEAN;
        if (node.isIntegralNumber()) return Type.INTEGER;
        if (node.isNumber()) return Type.NUMBER;
        if (node.isArray()) {
            int limit = Math.min(node.size(), 100);
            Type element = Type.UNKNOWN;
            for (int i = 0; i < limit; i++) {
                element = merge(element, infer(node.get(i), context, suggestedName + "Item"), context);
            }
            return new Type(Kind.ARRAY, null, element, null);
        }
        if (node.isObject()) {
            String name = context.uniqueName(suggestedName);
            Model model = new Model(name);
            context.models.add(model);
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                model.fields.add(new Field(field.getKey(), infer(field.getValue(), context,
                        name + typeName(field.getKey(), "Value")), false));
            }
            return new Type(Kind.OBJECT, name, null, model);
        }
        return Type.UNKNOWN;
    }

    private static Type merge(Type left, Type right, Context context) {
        if (left.kind == Kind.UNKNOWN) return right;
        if (right.kind == Kind.UNKNOWN) return left;
        if (left.kind == Kind.OBJECT && right.kind == Kind.OBJECT) {
            mergeModels(left.model, right.model, context);
            return left;
        }
        if (left.kind == Kind.ARRAY && right.kind == Kind.ARRAY) {
            return new Type(Kind.ARRAY, null, merge(left.element, right.element, context), null);
        }
        if (left.kind == right.kind) return left;
        if ((left.kind == Kind.INTEGER && right.kind == Kind.NUMBER) || (left.kind == Kind.NUMBER && right.kind == Kind.INTEGER)) {
            return Type.NUMBER;
        }
        return Type.UNKNOWN;
    }

    private static void mergeModels(Model target, Model source, Context context) {
        if (target == source) return;
        Set<String> sourceNames = new LinkedHashSet<>();
        for (Field sourceField : source.fields) sourceNames.add(sourceField.jsonName);
        for (int i = 0; i < target.fields.size(); i++) {
            Field targetField = target.fields.get(i);
            if (!sourceNames.contains(targetField.jsonName)) {
                target.fields.set(i, new Field(targetField.jsonName, targetField.type, true));
            }
        }
        for (Field sourceField : source.fields) {
            int targetIndex = target.indexOf(sourceField.jsonName);
            if (targetIndex < 0) {
                target.fields.add(new Field(sourceField.jsonName, sourceField.type, true));
            } else {
                Field targetField = target.fields.get(targetIndex);
                target.fields.set(targetIndex, new Field(targetField.jsonName,
                        merge(targetField.type, sourceField.type, context), targetField.optional || sourceField.optional));
            }
        }
        context.models.remove(source);
    }

    private static String renderJava(List<Model> models, Type root, JavaJsonSerializationStyle serializationStyle,
                                     JavaModelStyle modelStyle) {
        StringBuilder out = new StringBuilder();
        boolean hasMappedProperty = models.stream().anyMatch(JsonModelGenerator::hasJavaMappedProperty);
        if (hasMappedProperty && serializationStyle.getAnnotationImport() != null) {
            out.append("import ").append(serializationStyle.getAnnotationImport()).append(";\n\n");
        }
        if (modelStyle == JavaModelStyle.LOMBOK) out.append("import lombok.Data;\n\n");
        if (root.kind == Kind.ARRAY) out.append("// Root JSON type: ").append(javaType(root)).append("\n\n");
        for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
            Model model = models.get(modelIndex);
            Map<Field, String> fieldNames = javaFieldNames(model);
            renderJavaModel(out, model, fieldNames, serializationStyle, modelStyle, modelIndex == 0);
        }
        return out.toString().trim();
    }

    private static boolean hasJavaMappedProperty(Model model) {
        Map<Field, String> fieldNames = javaFieldNames(model);
        return model.fields.stream().anyMatch(field -> !fieldNames.get(field).equals(field.jsonName));
    }

    private static void renderJavaModel(StringBuilder out, Model model, Map<Field, String> fieldNames,
                                        JavaJsonSerializationStyle serializationStyle, JavaModelStyle modelStyle,
                                        boolean rootModel) {
        if (modelStyle == JavaModelStyle.RECORD) {
            out.append(rootModel ? "public record " : "record ").append(model.name).append("(\n");
            for (int index = 0; index < model.fields.size(); index++) {
                Field field = model.fields.get(index);
                appendJavaMapping(out, field.jsonName, fieldNames.get(field), serializationStyle);
                out.append("    ").append(javaType(field.type)).append(' ').append(fieldNames.get(field));
                out.append(index == model.fields.size() - 1 ? "\n" : ",\n");
            }
            out.append(") {\n}\n\n");
            return;
        }
        if (modelStyle == JavaModelStyle.LOMBOK) out.append("@Data\n");
        out.append(rootModel ? "public class " : "class ").append(model.name).append(" {\n");
        for (Field field : model.fields) {
            String javaName = fieldNames.get(field);
            appendJavaMapping(out, field.jsonName, javaName, serializationStyle);
            out.append("    private ").append(javaType(field.type)).append(' ').append(javaName).append(";\n");
        }
        if (modelStyle == JavaModelStyle.PLAIN_POJO) appendJavaAccessors(out, model, fieldNames);
        out.append("}\n\n");
    }

    private static void appendJavaAccessors(StringBuilder out, Model model, Map<Field, String> fieldNames) {
        if (!model.fields.isEmpty()) out.append('\n');
        for (Field field : model.fields) {
            String fieldName = fieldNames.get(field);
            String accessor = typeName(fieldName, "Value");
            String type = javaType(field.type);
            out.append("    public ").append(type).append(" get").append(accessor).append("() {\n")
                    .append("        return ").append(fieldName).append(";\n")
                    .append("    }\n\n")
                    .append("    public void set").append(accessor).append('(').append(type).append(' ')
                    .append(fieldName).append(") {\n")
                    .append("        this.").append(fieldName).append(" = ").append(fieldName).append(";\n")
                    .append("    }\n");
            if (model.fields.indexOf(field) < model.fields.size() - 1) out.append('\n');
        }
    }

    private static void appendJavaMapping(StringBuilder out, String jsonName, String fieldName,
                                          JavaJsonSerializationStyle serializationStyle) {
        if (fieldName.equals(jsonName)) return;
        if (serializationStyle.getAnnotationImport() == null) {
            out.append("    // JSON key: ").append(escape(jsonName)).append("\n");
        } else {
            out.append("    ").append(serializationStyle.renderAnnotation(escape(jsonName))).append("\n");
        }
    }

    private static String renderTypeScript(List<Model> models, Type root) {
        StringBuilder out = new StringBuilder();
        if (root.kind == Kind.ARRAY) out.append("export type ").append(root.element.name == null ? "Response" : root.element.name)
                .append("List = ").append(tsType(root)).append(";\n\n");
        for (Model model : models) {
            out.append("export interface ").append(model.name).append(" {\n");
            for (Field field : model.fields) {
                String rendered = isTypeScriptIdentifier(field.jsonName) ? field.jsonName : "'" + escape(field.jsonName) + "'";
                out.append("  ").append(rendered).append(field.optional ? "?: " : ": ").append(tsType(field.type)).append(";\n");
            }
            out.append("}\n\n");
        }
        return out.toString().trim();
    }

    private static String renderCSharp(List<Model> models, Type root, CSharpJsonSerializationStyle serializationStyle) {
        StringBuilder out = new StringBuilder("using System.Collections.Generic;\n");
        if (models.stream().anyMatch(JsonModelGenerator::hasCSharpMappedProperty)) {
            out.append("using ").append(serializationStyle.getAnnotationImport()).append(";\n");
        }
        out.append('\n');
        if (root.kind == Kind.ARRAY) out.append("// Root JSON type: ").append(csharpType(root)).append("\n\n");
        for (Model model : models) {
            out.append("public class ").append(model.name).append("\n{\n");
            Map<Field, String> fieldNames = csharpFieldNames(model);
            for (Field field : model.fields) {
                String name = fieldNames.get(field);
                if (!name.equals(field.jsonName)) out.append("    ")
                        .append(serializationStyle.renderAnnotation(escape(field.jsonName))).append("\n");
                out.append("    public ").append(csharpType(field.type)).append(' ').append(name).append(" { get; set; }\n");
            }
            out.append("}\n\n");
        }
        return out.toString().trim();
    }

    private static String javaType(Type type) { return switch (type.kind) {
        case STRING -> "String"; case BOOLEAN -> "Boolean"; case INTEGER -> "Long"; case NUMBER -> "java.math.BigDecimal";
        case OBJECT -> type.name; case ARRAY -> "java.util.List<" + javaType(type.element) + ">"; case UNKNOWN -> "Object"; }; }
    private static String tsType(Type type) { return switch (type.kind) {
        case STRING -> "string"; case BOOLEAN -> "boolean"; case INTEGER, NUMBER -> "number"; case OBJECT -> type.name;
        case ARRAY -> tsType(type.element) + "[]"; case UNKNOWN -> "unknown"; }; }
    private static String csharpType(Type type) { return switch (type.kind) {
        case STRING -> "string"; case BOOLEAN -> "bool"; case INTEGER -> "long"; case NUMBER -> "decimal"; case OBJECT -> type.name;
        case ARRAY -> "List<" + csharpType(type.element) + ">"; case UNKNOWN -> "object"; }; }

    private static Map<Field, String> javaFieldNames(Model model) { return allocateFieldNames(model, false, "value"); }
    private static Map<Field, String> csharpFieldNames(Model model) { return allocateFieldNames(model, true, "Value"); }
    private static boolean hasCSharpMappedProperty(Model model) {
        Map<Field, String> fieldNames = csharpFieldNames(model);
        return model.fields.stream().anyMatch(field -> !fieldNames.get(field).equals(field.jsonName));
    }
    private static Map<Field, String> allocateFieldNames(Model model, boolean upperFirst, String fallback) {
        Map<Field, String> names = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (Field field : model.fields) {
            String base = unicodeIdentifier(field.jsonName) ? field.jsonName : identifier(field.jsonName, upperFirst, fallback);
            String name = base;
            int suffix = 2;
            while (!used.add(name)) name = base + suffix++;
            names.put(field, name);
        }
        return names;
    }
    private static boolean unicodeIdentifier(String value) {
        return value != null && !value.isBlank() && value.codePoints().anyMatch(codePoint -> codePoint > 127)
                && isJavaIdentifier(value) && !JAVA_KEYWORDS.contains(value);
    }
    private static boolean isJavaIdentifier(String value) {
        return value.codePoints().findFirst().stream().allMatch(Character::isJavaIdentifierStart)
                && value.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart);
    }
    private static boolean isTypeScriptIdentifier(String value) {
        return unicodeIdentifier(value) || (value != null && value.matches("[A-Za-z_$][A-Za-z0-9_$]*"));
    }
    private static String javaFieldName(String jsonName) {
        return unicodeIdentifier(jsonName) ? jsonName : identifier(jsonName, false, "value");
    }
    private static String typeName(String value, String fallback) {
        return unicodeIdentifier(value) ? value : identifier(value, true, fallback);
    }
    private static String identifier(String raw, boolean upperFirst, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String[] parts = raw.replaceAll("([a-z])([A-Z])", "$1 $2").split("[^A-Za-z0-9]+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) if (!part.isBlank()) result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        if (result.isEmpty()) result.append(fallback);
        if (!upperFirst) result.setCharAt(0, Character.toLowerCase(result.charAt(0)));
        if (Character.isDigit(result.charAt(0))) result.insert(0, '_');
        if (!upperFirst && JAVA_KEYWORDS.contains(result.toString())) result.append('_');
        return result.toString();
    }
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private enum Kind { STRING, BOOLEAN, INTEGER, NUMBER, OBJECT, ARRAY, UNKNOWN }
    private static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
            "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "try", "void", "volatile", "while");
    private record Type(Kind kind, String name, Type element, Model model) {
        static final Type STRING = new Type(Kind.STRING, null, null, null); static final Type BOOLEAN = new Type(Kind.BOOLEAN, null, null, null);
        static final Type INTEGER = new Type(Kind.INTEGER, null, null, null); static final Type NUMBER = new Type(Kind.NUMBER, null, null, null);
        static final Type UNKNOWN = new Type(Kind.UNKNOWN, null, null, null);
    }
    private static final class Context {
        private final List<Model> models = new ArrayList<>(); private final Set<String> names = new LinkedHashSet<>();
        String uniqueName(String preferred) { String candidate = preferred; int index = 2; while (!names.add(candidate)) candidate = preferred + index++; return candidate; }
    }
    private static final class Model {
        private final String name;
        private final List<Field> fields = new ArrayList<>();
        Model(String name) { this.name = name; }
        int indexOf(String jsonName) {
            for (int i = 0; i < fields.size(); i++) if (fields.get(i).jsonName.equals(jsonName)) return i;
            return -1;
        }
    }
    private record Field(String jsonName, Type type, boolean optional) { }
}
