package com.laker.postman.service.swagger;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.laker.postman.collection.model.CollectionNode;
import com.laker.postman.collection.model.CollectionParseResult;
import com.laker.postman.request.model.HttpRequestItem;
import com.laker.postman.request.model.RequestImportMetadata;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.DigestUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Captures document contracts separately from generated samples and user-entered values. */
@UtilityClass
class SwaggerRequestContract {
    private static final Set<String> DOCUMENTATION = Set.of("description", "summary", "title", "example",
            "examples", "default", "externalDocs", "deprecated");
    private static final Set<String> NAMED_ENTRIES = Set.of("properties", "patternProperties", "content",
            "responses", "headers", "$defs", "definitions", "dependentSchemas");

    /** Associates a source hash and resolved contracts with requests; signed source URLs are never persisted. */
    static void attach(CollectionParseResult result, String json, URI source) {
        result.getGroup().setImportSource(DigestUtils.sha256Hex(source.normalize().toString()));
        JSONObject root = JSONUtil.parseObj(json);
        JSONObject paths = root.getJSONObject("paths");
        if (paths == null) return;
        for (HttpRequestItem request : requests(result.getChildren())) {
            String path = request.getUrl().replaceFirst("^\\{\\{baseUrl}}", "");
            JSONObject pathItem = paths.getJSONObject(path);
            if (pathItem == null) throw new IllegalArgumentException("Missing operation path");
            JSONObject operation = pathItem.getJSONObject(request.getMethod().toLowerCase(Locale.ROOT));
            if (operation == null) throw new IllegalArgumentException("Missing operation");
            Map<String, Object> definition = new TreeMap<>();
            definition.put("method", request.getMethod());
            definition.put("path", path);
            Map<String, Object> parameters = new TreeMap<>();
            int[] budget = {20_000};
            for (JSONObject parameter : SwaggerCommonUtil.mergeParameters(root, pathItem, operation)) {
                parameters.put(parameter.getStr("in", "") + ":" + parameter.getStr("name", ""),
                        canonical(root, parameter, new HashSet<>(), false, 0, budget));
            }
            definition.put("parameters", parameters);
            definition.put("requestBody", canonical(root, operation.get("requestBody"), new HashSet<>(), false, 0, budget));
            definition.put("consumes", operation.containsKey("consumes") ? operation.get("consumes") : root.get("consumes"));
            definition.put("responses", canonical(root, operation.get("responses"), new HashSet<>(), true, 0, budget));
            RequestImportMetadata metadata = new RequestImportMetadata();
            metadata.setOperationId(operation.getStr("operationId", ""));
            metadata.setPath(path);
            metadata.setMethod(request.getMethod());
            metadata.setDefinition(JSONUtil.toJsonStr(definition));
            metadata.setTemplate(JSONUtil.toJsonStr(request));
            request.setImportMetadata(metadata);
        }
    }

    /** Flattens groups for operation matching without changing the parsed collection. */
    static List<HttpRequestItem> requests(List<CollectionNode> nodes) {
        List<HttpRequestItem> result = new ArrayList<>();
        for (CollectionNode node : nodes) {
            if (node.isRequest()) result.add(node.asRequest());
            else result.addAll(requests(node.getChildren()));
        }
        return result;
    }

    /**
     * Sorts contract keys and expands local references so referenced type changes are observable.
     * Documentation/default samples do not constitute a signature change; named schema fields retain their names.
     * Cycles retain their reference, and expansion is bounded to reject pathological documents before any write.
     */
    private static Object canonical(JSONObject root, Object value, Set<String> refs, boolean named,
                                    int depth, int[] budget) {
        if (depth > 64 || --budget[0] < 0) throw new IllegalArgumentException("Contract expansion is too large");
        if (value instanceof JSONObject object) {
            String ref = object.getStr("$ref", "");
            boolean expanded = !named && !ref.isBlank() && refs.add(ref);
            try {
                if (expanded) object = SwaggerCommonUtil.resolveRefObject(root, object);
                Map<String, Object> result = new TreeMap<>();
                for (String key : object.keySet()) {
                    if (!named && (DOCUMENTATION.contains(key) || (key.startsWith("x-") && !key.equals("x-nullable")))) continue;
                    if (expanded && key.equals("$ref") && SwaggerCommonUtil.resolveLocalRef(root, ref) != null) continue;
                    Object child = canonical(root, object.get(key), refs, !named && NAMED_ENTRIES.contains(key), depth + 1, budget);
                    if (!named && (key.equals("required") || key.equals("enum")) && child instanceof List<?> list) {
                        child = list.stream().sorted(Comparator.comparing(JSONUtil::toJsonStr)).toList();
                    }
                    result.put(key, child);
                }
                return result;
            } finally {
                if (expanded) refs.remove(ref);
            }
        }
        if (value instanceof JSONArray array) {
            List<Object> result = new ArrayList<>();
            for (Object child : array) result.add(canonical(root, child, refs, false, depth + 1, budget));
            return result;
        }
        return value;
    }
}
