package com.laker.postman.service.swagger;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.laker.postman.collection.model.CollectionNode;
import com.laker.postman.collection.model.CollectionParseResult;
import com.laker.postman.collection.model.RequestGroup;
import com.laker.postman.request.model.HttpRequestItem;
import com.laker.postman.request.model.RequestImportMetadata;
import com.laker.postman.util.JsonUtil;
import com.laker.postman.util.MessageKeys;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Adds/updates operations inside one selected collection, leaving user values and unrelated requests intact. */
@UtilityClass
public class SwaggerIncrementalImport {
    /** Prepared collection and counts; changed requests identify editor tabs requiring a safe refresh. */
    public record Result(CollectionNode collection, List<HttpRequestItem> changedRequests,
                         int added, int updated, int unchanged, int adopted, boolean needsSave) { }

    /**
     * Prepares a merge without mutating the current tree. Endpoint identity wins, with a unique operationId
     * fallback for method/path changes. Ambiguous matches fail before saving; missing remote operations are retained.
     * Legacy requests adopt a baseline using visible structure because their historical types were not persisted.
     */
    public static Result merge(CollectionNode target, CollectionParseResult incoming) {
        CollectionNode result = copyGroups(target);
        RequestGroup group = JsonUtil.deepCopy(target.asGroup(), RequestGroup.class);
        group.setImportSource(incoming.getGroup().getImportSource());
        CollectionNode prepared = CollectionNode.group(group);
        prepared.getChildren().addAll(result.getChildren());
        result = prepared;
        List<CollectionNode> existingNodes = requestNodes(result);
        Set<String> matched = new HashSet<>();
        List<HttpRequestItem> changed = new ArrayList<>();
        int added = 0, updated = 0, unchanged = 0, adopted = 0;
        boolean metadataChanged = false;
        for (CollectionNode remoteGroup : incoming.getChildren()) {
            for (HttpRequestItem fresh : SwaggerRequestContract.requests(List.of(remoteGroup))) {
                CollectionNode match = findMatch(existingNodes, fresh);
                if (match == null) {
                    CollectionNode destination = destinationGroup(result, remoteGroup);
                    destination.addChild(CollectionNode.request(fresh));
                    added++;
                    continue;
                }
                HttpRequestItem local = match.asRequest();
                if (!matched.add(local.getId())) throw new IllegalArgumentException(MessageKeys.COLLECTIONS_IMPORT_SWAGGER_AMBIGUOUS);
                RequestImportMetadata previous = local.getImportMetadata();
                boolean same = previous != null
                        ? Objects.equals(previous.getDefinition(), fresh.getImportMetadata().getDefinition())
                        : Objects.equals(visibleShape(local), visibleShape(fresh));
                if (same && previous != null
                        && Objects.equals(previous.getOperationId(), fresh.getImportMetadata().getOperationId())) {
                    unchanged++;
                    continue;
                }
                HttpRequestItem merged = same ? JsonUtil.deepCopy(local, HttpRequestItem.class) : update(local, fresh);
                merged.setImportMetadata(fresh.getImportMetadata());
                metadataChanged = true;
                if (previous == null) adopted++;
                replaceRequest(result, local.getId(), merged);
                if (same) {
                    unchanged++;
                } else {
                    updated++;
                    changed.add(merged);
                }
            }
        }
        boolean needsSave = added > 0 || updated > 0 || metadataChanged
                || !Objects.equals(target.asGroup().getImportSource(), group.getImportSource());
        return new Result(result, List.copyOf(changed), added, updated, unchanged, adopted, needsSave);
    }

    /** Copies only tree structure, preserving untouched request/group objects until the prepared result is saved. */
    private static CollectionNode copyGroups(CollectionNode node) {
        CollectionNode copy = new CollectionNode(node.getType(), node.getData());
        for (CollectionNode child : node.getChildren()) copy.addChild(child.isGroup() ? copyGroups(child) : child);
        return copy;
    }

    /** Enumerates only request nodes; saved examples remain on their owning request. */
    private static List<CollectionNode> requestNodes(CollectionNode node) {
        List<CollectionNode> result = new ArrayList<>();
        if (node.isRequest()) result.add(node);
        else for (CollectionNode child : node.getChildren()) result.addAll(requestNodes(child));
        return result;
    }

    /** Matches within the selected collection only; duplicate endpoints/operation IDs require user cleanup. */
    private static CollectionNode findMatch(List<CollectionNode> nodes, HttpRequestItem fresh) {
        RequestImportMetadata metadata = fresh.getImportMetadata();
        List<CollectionNode> endpoints = nodes.stream().filter(node -> {
            HttpRequestItem local = node.asRequest();
            RequestImportMetadata old = local.getImportMetadata();
            String path = old == null ? requestPath(local.getUrl()) : old.getPath();
            String method = old == null ? local.getMethod() : old.getMethod();
            return Objects.equals(method, metadata.getMethod()) && Objects.equals(path, metadata.getPath());
        }).toList();
        if (endpoints.size() > 1) throw new IllegalArgumentException(MessageKeys.COLLECTIONS_IMPORT_SWAGGER_AMBIGUOUS);
        if (endpoints.size() == 1) return endpoints.get(0);
        if (metadata.getOperationId().isBlank()) return null;
        List<CollectionNode> operations = nodes.stream().filter(node -> node.asRequest().getImportMetadata() != null
                && Objects.equals(node.asRequest().getImportMetadata().getOperationId(), metadata.getOperationId())).toList();
        if (operations.size() > 1) throw new IllegalArgumentException(MessageKeys.COLLECTIONS_IMPORT_SWAGGER_AMBIGUOUS);
        return operations.isEmpty() ? null : operations.get(0);
    }

    /** Extracts the documented path from variable-based or absolute legacy URLs, ignoring configured query values. */
    private static String requestPath(String url) {
        String path = url == null ? "" : url.replaceFirst("^\\{\\{[^}]+}}", "");
        if (path.matches("(?i)^https?://.*")) path = path.replaceFirst("(?i)^https?://[^/]*", "");
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    /** Keeps existing tag placement; only new operations reuse/create the remote tag group. */
    private static CollectionNode destinationGroup(CollectionNode collection, CollectionNode remoteGroup) {
        if (remoteGroup.isRequest()) return collection;
        List<CollectionNode> groups = collection.getChildren().stream().filter(CollectionNode::isGroup)
                .filter(node -> Objects.equals(node.asGroup().getName(), remoteGroup.asGroup().getName())).toList();
        if (groups.size() > 1) throw new IllegalArgumentException(MessageKeys.COLLECTIONS_IMPORT_SWAGGER_AMBIGUOUS);
        if (!groups.isEmpty()) return groups.get(0);
        CollectionNode created = CollectionNode.group(remoteGroup.asGroup());
        collection.addChild(created);
        return created;
    }

    /** Replaces one prepared request while retaining its ID, examples and original group position. */
    private static void replaceRequest(CollectionNode group, String id, HttpRequestItem replacement) {
        List<CollectionNode> children = group.getChildren();
        for (int index = 0; index < children.size(); index++) {
            CollectionNode child = children.get(index);
            if (child.isGroup()) replaceRequest(child, id, replacement);
            else if (Objects.equals(child.asRequest().getId(), id)) children.set(index, CollectionNode.request(replacement));
        }
    }

    /**
     * Uses the prior generated template as a three-way baseline. User credentials, scripts, settings, IDs and
     * examples are retained; declared removed fields disappear, new/type-changed fields get generated defaults.
     * An unparseable custom body/path conflicts instead of silently overwriting the user's configuration.
     */
    private static HttpRequestItem update(HttpRequestItem local, HttpRequestItem fresh) {
        JSONObject current = JSONUtil.parseObj(JSONUtil.toJsonStr(local));
        JSONObject next = JSONUtil.parseObj(fresh.getImportMetadata().getTemplate());
        JSONObject previous = local.getImportMetadata() == null ? new JSONObject()
                : JSONUtil.parseObj(local.getImportMetadata().getTemplate());
        current.set("method", fresh.getMethod());
        if (local.getImportMetadata() != null && !Objects.equals(local.getImportMetadata().getPath(), fresh.getImportMetadata().getPath())) {
            String oldPath = local.getImportMetadata().getPath();
            String url = local.getUrl();
            int query = url.indexOf('?');
            String head = query < 0 ? url : url.substring(0, query);
            if (!head.endsWith(oldPath)) throw new IllegalArgumentException(MessageKeys.COLLECTIONS_IMPORT_SWAGGER_CONFLICT);
            current.set("url", head.substring(0, head.length() - oldPath.length()) + fresh.getImportMetadata().getPath()
                    + (query < 0 ? "" : url.substring(query)));
        }
        for (String field : List.of("name", "description")) {
            if (Objects.equals(current.get(field), previous.get(field))) current.set(field, next.get(field));
        }
        for (String field : List.of("paramsList", "headersList", "pathVariablesList", "formDataList", "urlencodedList")) {
            current.set(field, mergeRows(field, current.getJSONArray(field), previous.getJSONArray(field), next.getJSONArray(field),
                    local.getImportMetadata(), fresh.getImportMetadata()));
        }
        if (!Objects.equals(local.getBodyType(), fresh.getBodyType())) {
            current.set("bodyType", fresh.getBodyType());
            current.set("body", fresh.getBody());
        } else if (!Objects.equals(previous.getStr("body", ""), fresh.getBody())
                || !Objects.equals(bodySchema(local.getImportMetadata()), bodySchema(fresh.getImportMetadata()))) {
            current.set("body", mergeBody(local.getBody(), previous.getStr("body", ""), fresh.getBody(),
                    bodySchema(local.getImportMetadata()), bodySchema(fresh.getImportMetadata())));
        }
        return JSONUtil.toBean(current, HttpRequestItem.class);
    }

    /** Retains values/enabled flags for compatible keys and user-added rows; only prior declared removals are deleted. */
    private static JSONArray mergeRows(String field, JSONArray current, JSONArray previous, JSONArray next,
                                       RequestImportMetadata oldMetadata, RequestImportMetadata newMetadata) {
        Map<String, JSONObject> oldRows = rowsByKey(current, field);
        Map<String, JSONObject> declared = rowsByKey(previous, field);
        Map<String, JSONObject> newRows = rowsByKey(next, field);
        JSONArray result = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : newRows.entrySet()) {
            JSONObject saved = oldRows.get(entry.getKey());
            JSONObject row = entry.getValue();
            String in = field.equals("paramsList") ? "query" : field.equals("headersList") ? "header" : "formData";
            boolean compatible = saved != null && Objects.equals(saved.getStr("type", ""), row.getStr("type", ""))
                    && (oldMetadata == null || Objects.equals(parameterType(oldMetadata, in, row.getStr("key")),
                    parameterType(newMetadata, in, row.getStr("key"))));
            if (compatible) {
                JSONObject oldDefault = declared.get(entry.getKey());
                if (field.equals("headersList") && entry.getKey().equals("content-type") && oldDefault != null
                        && Objects.equals(saved.getStr("value"), oldDefault.getStr("value"))) {
                    saved = JSONUtil.parseObj(saved.toString());
                    saved.set("value", row.get("value"));
                }
                row = saved;
            }
            result.add(row);
        }
        for (Map.Entry<String, JSONObject> entry : oldRows.entrySet()) {
            if (!newRows.containsKey(entry.getKey()) && !declared.containsKey(entry.getKey())) result.add(entry.getValue());
        }
        return result;
    }

    /** Reads schema type/format rather than sample text, so query parameter type changes can reset incompatible values. */
    private static String parameterType(RequestImportMetadata metadata, String in, String key) {
        JSONObject parameters = JSONUtil.parseObj(metadata.getDefinition()).getJSONObject("parameters");
        JSONObject parameter = parameters == null ? null : parameters.getJSONObject(in + ":" + key);
        if (parameter == null) return "";
        JSONObject schema = parameter.getJSONObject("schema");
        if (schema == null) schema = parameter;
        return schema.getStr("type", "") + ":" + schema.getStr("format", "");
    }

    /** Indexes rows in display order, comparing HTTP header names case-insensitively. */
    private static Map<String, JSONObject> rowsByKey(JSONArray rows, String field) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        if (rows != null) for (Object value : rows) {
            JSONObject row = (JSONObject) value;
            String key = row.getStr("key", "");
            result.put(field.equals("headersList") ? key.toLowerCase(Locale.ROOT) : key, row);
        }
        return result;
    }

    /** Merges JSON structure, keeping exact text when content is unchanged; opaque custom payloads conflict. */
    private static String mergeBody(String current, String previous, String next, JSONObject oldSchema, JSONObject newSchema) {
        if (Objects.equals(current, previous) || current == null || current.isBlank()) return next;
        try {
            Object configured = JSONUtil.parse(current);
            Object merged = mergeJson(previous.isBlank() ? new JSONObject() : JSONUtil.parse(previous),
                    next.isBlank() ? new JSONObject() : JSONUtil.parse(next), configured, oldSchema, newSchema);
            return Objects.equals(merged, configured) ? current : JSONUtil.toJsonPrettyStr(merged);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(MessageKeys.COLLECTIONS_IMPORT_SWAGGER_CONFLICT);
        }
    }

    /** Three-way JSON merge preserves nested values/custom fields and applies changes to every configured array element. */
    private static Object mergeJson(Object previous, Object next, Object current, JSONObject oldSchema, JSONObject newSchema) {
        if (oldSchema != null && newSchema != null
                && (!Objects.equals(oldSchema.get("type"), newSchema.get("type"))
                || !Objects.equals(oldSchema.get("format"), newSchema.get("format")))) return next;
        if (next instanceof JSONObject nextObject && current instanceof JSONObject currentObject) {
            JSONObject oldObject = previous instanceof JSONObject object ? object : new JSONObject();
            JSONObject result = new JSONObject();
            for (String key : nextObject.keySet()) {
                Object value = currentObject.containsKey(key)
                        ? mergeJson(oldObject.get(key), nextObject.get(key), currentObject.get(key),
                        propertySchema(oldSchema, key), propertySchema(newSchema, key)) : nextObject.get(key);
                result.set(key, value);
            }
            for (String key : currentObject.keySet()) {
                if (!nextObject.containsKey(key) && !oldObject.containsKey(key)) result.set(key, currentObject.get(key));
            }
            return result;
        }
        if (next instanceof JSONArray nextArray && current instanceof JSONArray currentArray && !nextArray.isEmpty()) {
            JSONArray oldArray = previous instanceof JSONArray array ? array : new JSONArray();
            JSONArray result = new JSONArray();
            for (Object item : currentArray) result.add(mergeJson(oldArray.isEmpty() ? null : oldArray.get(0), nextArray.get(0), item,
                    oldSchema == null ? null : oldSchema.getJSONObject("items"),
                    newSchema == null ? null : newSchema.getJSONObject("items")));
            return result;
        }
        if (previous == null || valueType(previous).equals(valueType(next))) return current;
        return next;
    }

    /** Reads the body schema from the media type selected by the parser or a Swagger 2 body parameter. */
    private static JSONObject bodySchema(RequestImportMetadata metadata) {
        if (metadata == null) return null;
        JSONObject definition = JSONUtil.parseObj(metadata.getDefinition());
        JSONObject body = definition.getJSONObject("requestBody");
        JSONObject content = body == null ? null : body.getJSONObject("content");
        if (content != null && !content.isEmpty()) {
            String type = content.containsKey("application/json") ? "application/json" : content.keySet().iterator().next();
            JSONArray headers = JSONUtil.parseObj(metadata.getTemplate()).getJSONArray("headersList");
            if (headers != null) for (Object value : headers) {
                JSONObject header = (JSONObject) value;
                if ("Content-Type".equalsIgnoreCase(header.getStr("key")) && content.containsKey(header.getStr("value"))) {
                    type = header.getStr("value");
                    break;
                }
            }
            return content.getJSONObject(type).getJSONObject("schema");
        }
        JSONObject parameters = definition.getJSONObject("parameters");
        if (parameters != null) for (String key : parameters.keySet()) {
            if (key.startsWith("body:")) return parameters.getJSONObject(key).getJSONObject("schema");
        }
        return null;
    }

    /** Looks up nested field types without interpreting configured values or variable expressions. */
    private static JSONObject propertySchema(JSONObject schema, String key) {
        JSONObject properties = schema == null ? null : schema.getJSONObject("properties");
        return properties == null ? null : properties.getJSONObject(key);
    }

    /** Compares sample JSON categories rather than Java integer/long implementation classes. */
    private static String valueType(Object value) {
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof JSONObject) return "object";
        if (value instanceof JSONArray) return "array";
        return "string";
    }

    /** Legacy fallback ignores values/types absent from old files, but compares parameter names, body kind and JSON structure. */
    private static Object visibleShape(HttpRequestItem request) {
        JSONObject json = JSONUtil.parseObj(JSONUtil.toJsonStr(request));
        Map<String, Object> result = new TreeMap<>();
        result.put("bodyType", request.getBodyType());
        for (String field : List.of("paramsList", "headersList", "formDataList", "urlencodedList")) {
            result.put(field, rowsByKey(json.getJSONArray(field), field).keySet().stream().sorted().toList());
        }
        String body = request.getBody();
        try {
            result.put("body", body == null || body.isBlank() ? "" : jsonShape(JSONUtil.parse(body)));
        } catch (RuntimeException exception) {
            result.put("body", "text");
        }
        return result;
    }

    /** Masks scalar sample values while retaining object field names and array nesting for legacy comparison. */
    private static Object jsonShape(Object value) {
        if (value instanceof JSONObject object) {
            Map<String, Object> result = new TreeMap<>();
            for (String key : object.keySet()) result.put(key, jsonShape(object.get(key)));
            return result;
        }
        if (value instanceof JSONArray array) return array.isEmpty() ? List.of() : List.of(jsonShape(array.get(0)));
        return "value";
    }
}
