package com.laker.postman.panel.toolbox;

import com.laker.postman.util.I18nUtil;
import com.laker.postman.util.JsonUtil;
import com.laker.postman.util.MessageKeys;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** DSL keyword and current-index field suggestions; never executes or changes a query. */
final class ElasticsearchDslCompletionSupport {
    private final DefaultCompletionProvider provider = new DefaultCompletionProvider() {
        /** Dot-separated mapping fields and index-style names form a single completion token. */
        @Override
        protected boolean isValidChar(char character) {
            return super.isValidChar(character) || character == '.' || character == '-';
        }
    };

    /**
     * Installs automatic suggestions and Ctrl+Space activation without changing editor contents.
     * @param editor editable JSON editor, initialized on the EDT
     */
    ElasticsearchDslCompletionSupport(RSyntaxTextArea editor) {
        provider.setAutoActivationRules(true, ".\"");
        replaceFields(Map.of());
        AutoCompletion completion = new AutoCompletion(provider);
        completion.setAutoActivationEnabled(true);
        completion.setAutoActivationDelay(200);
        completion.setShowDescWindow(true);
        completion.install(editor);
    }

    /**
     * Replaces previous-index fields on the EDT while retaining common DSL keywords.
     * @param fields current-index field names and their ES types; empty clears index-specific suggestions
     */
    void replaceFields(Map<String, String> fields) {
        provider.clear();
        for (String keyword : new String[]{"query", "match_all", "match", "match_phrase", "multi_match", "term", "terms",
                "range", "bool", "must", "filter", "must_not", "should", "minimum_should_match", "exists", "field",
                "prefix", "wildcard", "regexp", "ids", "values", "nested", "path", "from", "size", "sort", "asc", "desc",
                "_source", "track_total_hits", "aggs", "aggregations", "avg", "sum", "min", "max", "value_count",
                "cardinality", "date_histogram", "gte", "gt", "lte", "lt", "format", "boost", "operator"}) {
            provider.addCompletion(new BasicCompletion(provider, keyword,
                    I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_COMPLETION_KEYWORD)));
        }
        fields.forEach((name, type) -> provider.addCompletion(new BasicCompletion(provider, name,
                I18nUtil.getMessage(MessageKeys.TOOLBOX_ES_COMPLETION_FIELD, type))));
    }

    /**
     * Reads typeless or legacy typed mappings, preserving full object and multi-field paths.
     * @param mapping the complete GET /index/_mapping response body
     * @return field name to ES type, including real .keyword/.enum fields rather than assumed suffixes
     */
    static Map<String, String> readFields(String mapping) {
        Map<String, String> fields = new LinkedHashMap<>();
        JsonNode root = JsonUtil.readTree(mapping);
        if (!root.isObject()) throw new IllegalArgumentException("Expected an index Mapping object");
        for (JsonNode index : root) {
            JsonNode mappings = index.path("mappings");
            if (!mappings.isObject()) throw new IllegalArgumentException("Missing index Mapping");
            if (mappings.has("properties")) collect(fields, mappings.path("properties"), "", 0);
            else for (JsonNode type : mappings) collect(fields, type.path("properties"), "", 0);
            collect(fields, mappings.path("runtime"), "", 0);
        }
        return fields;
    }

    /** Bounds suggestions to 5,000 fields/64 levels so large definitions cannot exhaust the editor UI. */
    private static void collect(Map<String, String> fields, JsonNode properties, String prefix, int depth) {
        if (depth > 64 || fields.size() > 5000) throw new IllegalArgumentException("Mapping exceeds completion limits");
        for (var entry : properties.properties()) {
            String name = prefix + entry.getKey();
            JsonNode field = entry.getValue();
            fields.putIfAbsent(name, field.path("type").asString("object"));
            collect(fields, field.path("properties"), name + ".", depth + 1);
            collect(fields, field.path("fields"), name + ".", depth + 1);
        }
    }
}
