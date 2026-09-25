package com.laker.postman.panel.toolbox;

import cn.hutool.json.JSONUtil;
import com.laker.postman.common.component.FallbackAwareRSyntaxTextArea;
import com.laker.postman.common.component.SearchableTextArea;
import com.laker.postman.common.constants.ModernColors;
import com.laker.postman.util.EditorThemeUtil;
import com.laker.postman.util.FontsUtil;
import com.laker.postman.util.I18nUtil;
import com.laker.postman.util.JsonUtil;
import com.laker.postman.util.MessageKeys;
import lombok.extern.slf4j.Slf4j;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * JSON工具面板 - 使用RSyntaxTextArea提供语法高亮和更好的编辑体验
 */
@Slf4j
public class JsonToolPanel extends JPanel {

    private RSyntaxTextArea inputArea;
    private RSyntaxTextArea outputArea;
    private JTextArea largeInputArea;
    private JTextArea largeOutputArea;
    private CardLayout inputCards;
    private CardLayout outputCards;
    private JPanel inputHolder;
    private JPanel outputHolder;
    private boolean largeInput;
    private boolean largeOutput;
    private JLabel statusLabel;

    public JsonToolPanel() {
        initUI();
    }

    private void initUI() {
        ToolboxWorkbench.applyRoot(this);

        // 顶部工具栏
        JButton formatBtn = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_FORMAT));
        JButton compressBtn = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_COMPRESS));
        JButton validateBtn = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_VALIDATE));
        JButton escapeBtn = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_ESCAPE));
        JButton unescapeBtn = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_UNESCAPE));
        JButton sortBtn = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_SORT_KEYS));

        formatBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_TOOLTIP_FORMAT));
        compressBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_TOOLTIP_COMPRESS));
        validateBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_TOOLTIP_VALIDATE));
        escapeBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_TOOLTIP_ESCAPE));
        unescapeBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_TOOLTIP_UNESCAPE));
        sortBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_TOOLTIP_SORT));

        JSeparator transformSeparator = ToolboxWorkbench.verticalSeparator();
        JPanel leftBtnPanel = ToolboxWorkbench.leftToolbar(
                formatBtn,
                compressBtn,
                validateBtn,
                transformSeparator,
                escapeBtn,
                unescapeBtn,
                sortBtn
        );

        JButton copyBtn = new JButton(I18nUtil.getMessage(MessageKeys.BUTTON_COPY));
        JButton pasteBtn = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_PASTE));
        JButton clearBtn = new JButton(I18nUtil.getMessage(MessageKeys.BUTTON_CLEAR));
        JButton swapBtn = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_SWAP));

        copyBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_TOOLTIP_COPY));
        pasteBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_TOOLTIP_PASTE));
        clearBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_TOOLTIP_CLEAR));
        swapBtn.setToolTipText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_TOOLTIP_SWAP));

        JPanel rightBtnPanel = ToolboxWorkbench.rightToolbar(copyBtn, pasteBtn, clearBtn, swapBtn);
        add(ToolboxWorkbench.toolbar(leftBtnPanel, rightBtnPanel), BorderLayout.NORTH);

        // 中间分割面板
        // 输入区域 - 使用RSyntaxTextArea
        inputArea = createJsonTextArea(true);
        inputArea.setEditable(true);
        SearchableTextArea searchableInputArea = new SearchableTextArea(inputArea);
        searchableInputArea.setLineNumbersEnabled(true);
        largeInputArea = createLargeTextArea(true, inputArea);
        inputCards = new CardLayout();
        inputHolder = createEditorHolder(inputCards, searchableInputArea, largeInputArea);
        JPanel inputPanel = ToolboxWorkbench.editorSection(
                I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_INPUT),
                inputHolder
        );

        // 输出区域 - 使用RSyntaxTextArea
        outputArea = createJsonTextArea(false);
        outputArea.setEditable(false);
        SearchableTextArea searchableOutputArea = new SearchableTextArea(outputArea, false);
        searchableOutputArea.setLineNumbersEnabled(true);
        largeOutputArea = createLargeTextArea(false, outputArea);
        outputCards = new CardLayout();
        outputHolder = createEditorHolder(outputCards, searchableOutputArea, largeOutputArea);
        JPanel outputPanel = ToolboxWorkbench.editorSection(
                I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_OUTPUT),
                outputHolder
        );
        add(ToolboxWorkbench.editorSplit(inputPanel, outputPanel, 300), BorderLayout.CENTER);

        // 底部状态栏
        statusLabel = new JLabel(" ");
        statusLabel.setFont(FontsUtil.getDefaultFontWithOffset(Font.PLAIN, -2));
        add(ToolboxWorkbench.statusBar(statusLabel), BorderLayout.SOUTH);

        // 按钮事件
        formatBtn.addActionListener(e -> formatJson());
        compressBtn.addActionListener(e -> compressJson());
        validateBtn.addActionListener(e -> validateJson());
        escapeBtn.addActionListener(e -> escapeJson());
        unescapeBtn.addActionListener(e -> unescapeJson());
        sortBtn.addActionListener(e -> sortJsonKeys());
        copyBtn.addActionListener(e -> copyToClipboard());
        pasteBtn.addActionListener(e -> pasteFromClipboard());
        clearBtn.addActionListener(e -> clearAll());
        swapBtn.addActionListener(e -> swapInputOutput());

        // 添加快捷键
        setupKeyBindings();
    }

    /**
     * 创建 JSON 编辑区域；输入框先拦截超长单行粘贴，避免语法编辑器在界面线程长时间布局。
     */
    private RSyntaxTextArea createJsonTextArea(boolean input) {
        RSyntaxTextArea textArea = input ? new FallbackAwareRSyntaxTextArea(10, 40) {
            @Override
            public void paste() {
                if (!pasteLargeInput()) {
                    super.paste();
                }
            }
        } : new FallbackAwareRSyntaxTextArea(10, 40);
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        textArea.setCodeFoldingEnabled(true); // 启用代码折叠
        textArea.setAntiAliasingEnabled(true); // 抗锯齿
        textArea.setAutoIndentEnabled(true); // 自动缩进
        textArea.setTabSize(2); // Tab大小为2个空格
        textArea.setTabsEmulated(true); // 用空格模拟Tab
        textArea.setMarkOccurrences(true); // 标记相同内容
        textArea.setAnimateBracketMatching(true); // 括号匹配动画
        EditorThemeUtil.loadTheme(textArea);
        EditorThemeUtil.installViewportClippedTokenPainter(textArea);
        return textArea;
    }

    /** Creates a plain editor for long single-line JSON while retaining the normal syntax editor for smaller text. */
    private JTextArea createLargeTextArea(boolean editable, RSyntaxTextArea source) {
        JTextArea area = new JTextArea();
        area.setEditable(editable);
        area.setFont(source.getFont());
        area.setBackground(source.getBackground());
        area.setForeground(source.getForeground());
        area.setCaretColor(source.getCaretColor());
        return area;
    }

    /** Places both editors in one slot so large payloads do not alter the toolbox layout. */
    private JPanel createEditorHolder(CardLayout cards, SearchableTextArea syntax, JTextArea plain) {
        JPanel holder = new JPanel(cards);
        holder.add(syntax, "syntax");
        holder.add(new JScrollPane(plain), "plain");
        return holder;
    }

    /** Long single lines are expensive in RSyntaxTextArea even with syntax coloring disabled. */
    private boolean needsPlainEditor(String text) {
        if (text.length() < 256 * 1024) {
            return false;
        }
        int lineLength = 0;
        for (int i = 0; i < text.length(); i++) {
            lineLength = text.charAt(i) == '\n' ? 0 : lineLength + 1;
            if (lineLength > 32 * 1024) {
                return true;
            }
        }
        return false;
    }

    /** Routes input to the editor that can display its longest line without blocking the UI. */
    private void setInputText(String text) {
        largeInput = needsPlainEditor(text);
        if (largeInput) {
            largeInputArea.setText(text);
            inputArea.setText("");
        } else {
            inputArea.setText(text);
            largeInputArea.setText("");
        }
        inputCards.show(inputHolder, largeInput ? "plain" : "syntax");
    }

    /** Returns whichever input editor currently holds the JSON text. */
    private String getInputText() {
        return largeInput ? largeInputArea.getText() : inputArea.getText();
    }

    /** Routes output through the same large-line guard used for input. */
    private void setOutputText(String text) {
        largeOutput = needsPlainEditor(text);
        if (largeOutput) {
            largeOutputArea.setText(text);
            outputArea.setText("");
        } else {
            outputArea.setText(text);
            largeOutputArea.setText("");
        }
        outputCards.show(outputHolder, largeOutput ? "plain" : "syntax");
    }

    /** Returns the current result text regardless of which editor displays it. */
    private String getOutputText() {
        return largeOutput ? largeOutputArea.getText() : outputArea.getText();
    }

    /** Intercepts a large clipboard paste before RSyntaxTextArea receives its long line. */
    private boolean pasteLargeInput() {
        try {
            Object clipboard = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (!(clipboard instanceof String pasted)) {
                return false;
            }
            String current = inputArea.getText();
            int start = inputArea.getSelectionStart();
            int end = inputArea.getSelectionEnd();
            if (current.length() - (end - start) + pasted.length() < 256 * 1024) {
                return false;
            }
            String updated = current.substring(0, start) + pasted + current.substring(end);
            if (!needsPlainEditor(updated)) {
                return false;
            }
            setInputText(updated);
            largeInputArea.setCaretPosition(start + pasted.length());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 设置快捷键
     */
    private void setupKeyBindings() {
        // Ctrl+Shift+F - Format
        addKeyBinding(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                "format", e -> formatJson());

        // Ctrl+Shift+C - Compress
        addKeyBinding(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                "compress", e -> compressJson());

        // Ctrl+Shift+V - Validate
        addKeyBinding(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                "validate", e -> validateJson());

        // Ctrl+L - Clear
        addKeyBinding(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.CTRL_DOWN_MASK),
                "clear", e -> clearAll());
    }

    private void addKeyBinding(KeyStroke keyStroke, String actionName, java.awt.event.ActionListener action) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(keyStroke, actionName);
        getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.actionPerformed(e);
            }
        });
    }

    /**
     * 格式化JSON
     */
    private void formatJson() {
        String input = getInputText().trim();
        if (input.isEmpty()) {
            setOutputText("");
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_EMPTY), false);
            return;
        }

        try {
            String formatted = JsonUtil.toJsonPrettyStr(input);
            setOutputText(formatted);
            int lines = formatted.split("\n").length;
            String message = I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_FORMATTED,
                    String.valueOf(lines), String.valueOf(formatted.length()));
            updateStatus(message, true);
        } catch (Exception ex) {
            log.error("JSON format error", ex);
            setOutputText("❌ " + I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_ERROR) + ":\n\n" + ex.getMessage());
            updateStatus("❌ " + I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_ERROR) + ": " + ex.getMessage(), false);
        }
    }

    /**
     * 压缩JSON
     */
    private void compressJson() {
        String input = getInputText().trim();
        if (input.isEmpty()) {
            setOutputText("");
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_EMPTY), false);
            return;
        }

        try {
            String compressed = JSONUtil.toJsonStr(JSONUtil.parse(input));
            setOutputText(compressed);
            int reduction = input.length() - compressed.length();
            double percent = (reduction * 100.0) / input.length();
            String message = I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_COMPRESSED,
                    String.valueOf(reduction), String.format("%.1f", percent));
            updateStatus(message, true);
        } catch (Exception ex) {
            log.error("JSON compress error", ex);
            setOutputText("❌ " + I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_ERROR) + ":\n\n" + ex.getMessage());
            updateStatus("❌ " + I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_ERROR) + ": " + ex.getMessage(), false);
        }
    }

    /**
     * 验证JSON
     */
    private void validateJson() {
        String input = getInputText().trim();
        if (input.isEmpty()) {
            setOutputText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_VALIDATION_EMPTY));
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_EMPTY), false);
            return;
        }

        try {
            Object parsed = JSONUtil.parse(input);
            String type = parsed.getClass().getSimpleName();
            int lines = input.split("\n").length;
            int chars = input.length();

            // 统计JSON结构信息
            StringBuilder info = new StringBuilder();
            info.append(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_VALIDATION_VALID)).append("\n\n");
            info.append(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_VALIDATION_TYPE)).append(": ").append(type).append("\n");
            info.append(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_VALIDATION_CHARACTERS)).append(": ").append(chars).append("\n");
            info.append(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_VALIDATION_LINES)).append(": ").append(lines).append("\n");

            // 尝试统计键值对数量
            if (parsed instanceof cn.hutool.json.JSONObject obj) {
                info.append(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_VALIDATION_KEYS)).append(": ").append(obj.size()).append("\n");
            } else if (parsed instanceof cn.hutool.json.JSONArray arr) {
                info.append(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_VALIDATION_ARRAY_LENGTH)).append(": ").append(arr.size()).append("\n");
            }

            setOutputText(info.toString());
            String message = I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_VALIDATED, type);
            updateStatus(message, true);
        } catch (Exception ex) {
            log.error("JSON validate error", ex);
            String errorMsg = "❌ " + I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_ERROR) + ":\n\n" + ex.getMessage();
            setOutputText(errorMsg);
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_INVALID), false);
        }
    }

    /**
     * Escape JSON字符串（转义特殊字符）
     */
    private void escapeJson() {
        String input = getInputText();
        if (input.isEmpty()) {
            setOutputText("");
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_EMPTY), false);
            return;
        }

        try {
            String escaped = JsonUtil.escapeJsonStringContent(input);
            setOutputText(escaped);
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_ESCAPED), true);
        } catch (Exception ex) {
            log.error("Escape error", ex);
            updateStatus("❌ " + ex.getMessage(), false);
        }
    }

    /**
     * Unescape JSON字符串（反转义）
     */
    private void unescapeJson() {
        String input = getInputText();
        if (input.isEmpty()) {
            setOutputText("");
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_EMPTY), false);
            return;
        }

        try {
            setOutputText(unescapeJsonContent(input));
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_UNESCAPED), true);
        } catch (Exception ex) {
            log.error("Unescape error", ex);
            updateStatus("❌ " + ex.getMessage(), false);
        }
    }

    /**
     * 排序JSON的键
     */
    private void sortJsonKeys() {
        String input = getInputText().trim();
        if (input.isEmpty()) {
            setOutputText("");
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_EMPTY), false);
            return;
        }

        try {
            Object parsed = JSONUtil.parse(input);
            // 使用有序的JSONObject来保持键的排序
            if (parsed instanceof cn.hutool.json.JSONObject obj) {
                String sorted = JsonUtil.toJsonPrettyStr(sortJsonObject(obj));
                setOutputText(sorted);
                updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_SORTED), true);
            } else {
                setOutputText(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_NOT_OBJECT) + "\n\n" +
                        I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_VALIDATION_TYPE) + ": " + parsed.getClass().getSimpleName());
                updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_NOT_OBJECT), false);
            }
        } catch (Exception ex) {
            log.error("Sort keys error", ex);
            setOutputText("❌ " + I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_ERROR) + ":\n\n" + ex.getMessage());
            updateStatus("❌ " + I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_ERROR) + ": " + ex.getMessage(), false);
        }
    }

    /**
     * 递归排序JSON对象的键
     */
    private cn.hutool.json.JSONObject sortJsonObject(cn.hutool.json.JSONObject obj) {
        cn.hutool.json.JSONObject sorted = new cn.hutool.json.JSONObject(true); // true表示使用LinkedHashMap保持顺序
        obj.keySet().stream().sorted().forEach(key -> {
            Object value = obj.get(key);
            if (value instanceof cn.hutool.json.JSONObject) {
                sorted.set(key, sortJsonObject((cn.hutool.json.JSONObject) value));
            } else if (value instanceof cn.hutool.json.JSONArray) {
                sorted.set(key, sortJsonArray((cn.hutool.json.JSONArray) value));
            } else {
                sorted.set(key, value);
            }
        });
        return sorted;
    }

    /**
     * 递归处理JSON数组中的对象
     */
    private cn.hutool.json.JSONArray sortJsonArray(cn.hutool.json.JSONArray arr) {
        cn.hutool.json.JSONArray sorted = new cn.hutool.json.JSONArray();
        for (Object item : arr) {
            if (item instanceof cn.hutool.json.JSONObject) {
                sorted.add(sortJsonObject((cn.hutool.json.JSONObject) item));
            } else if (item instanceof cn.hutool.json.JSONArray) {
                sorted.add(sortJsonArray((cn.hutool.json.JSONArray) item));
            } else {
                sorted.add(item);
            }
        }
        return sorted;
    }

    /**
     * 交换输入和输出区域的内容
     */
    private void swapInputOutput() {
        String inputText = getInputText();
        String outputText = getOutputText();
        setInputText(outputText);
        setOutputText(inputText);
        updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_SWAPPED), true);
    }

    /**
     * 从剪贴板粘贴
     */
    private void pasteFromClipboard() {
        try {
            String text = (String) Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
            if (text != null && !text.isEmpty()) {
                setInputText(text);
                updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_PASTED), true);
            }
        } catch (Exception ex) {
            log.error("Paste error", ex);
            updateStatus("❌ " + ex.getMessage(), false);
        }
    }

    /**
     * 复制到剪贴板
     */
    private void copyToClipboard() {
        String text = getOutputText();
        if (!text.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_COPIED), true);
        } else {
            updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_OUTPUT_EMPTY), false);
        }
    }

    /**
     * 清空所有区域
     */
    private void clearAll() {
        setInputText("");
        setOutputText("");
        updateStatus(I18nUtil.getMessage(MessageKeys.TOOLBOX_JSON_STATUS_CLEARED), true);
    }

    /**
     * 智能反转义：
     * 1. 如果输入本身是 JSON，对其中嵌套的 JSON 字符串递归展开
     * 2. 如果输入是被转义的 JSON 字符串，优先还原成 JSON
     * 3. 兜底再按普通文本反转义
     */
    private String unescapeJsonContent(String input) {
        JsonNode root = tryReadJson(input);
        if (root != null) {
            JsonNode expanded = expandEscapedJson(root);
            if (expanded.isTextual()) {
                String text = expanded.asText();
                JsonNode nestedJson = tryReadJsonContainer(text);
                return nestedJson != null ? JsonUtil.toJsonPrettyStr(nestedJson) : text;
            }
            return JsonUtil.toJsonPrettyStr(expanded);
        }

        String unescaped = unescapePlainText(input);
        JsonNode nestedJson = tryReadJsonContainer(unescaped);
        return nestedJson != null ? JsonUtil.toJsonPrettyStr(nestedJson) : unescaped;
    }

    private JsonNode expandEscapedJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode objectNode = JsonUtil.createJsonNode();
            for (java.util.Map.Entry<String, JsonNode> entry : node.properties()) {
                objectNode.set(entry.getKey(), expandEscapedJson(entry.getValue()));
            }
            return objectNode;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = JsonUtil.createArrayNode();
            for (JsonNode item : node) {
                arrayNode.add(expandEscapedJson(item));
            }
            return arrayNode;
        }
        if (!node.isTextual()) {
            return node;
        }

        String text = node.asText();
        JsonNode nestedJson = tryReadJsonContainer(text);
        if (nestedJson != null) {
            return expandEscapedJson(nestedJson);
        }

        String unescaped = unescapePlainText(text);
        JsonNode nestedUnescapedJson = tryReadJsonContainer(unescaped);
        if (nestedUnescapedJson != null) {
            return expandEscapedJson(nestedUnescapedJson);
        }

        return !text.equals(unescaped) ? JsonUtil.readTree(JsonUtil.toJsonStr(unescaped)) : node;
    }

    private JsonNode tryReadJson(String text) {
        try {
            return JsonUtil.readTree(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode tryReadJsonContainer(String text) {
        JsonNode jsonNode = tryReadJson(text);
        if (jsonNode != null && (jsonNode.isObject() || jsonNode.isArray())) {
            return jsonNode;
        }
        return null;
    }

    private String unescapePlainText(String input) {
        return JsonUtil.unescapeJsonStringContent(input);
    }

    /**
     * 更新状态栏
     */
    private void updateStatus(String message, boolean success) {
        statusLabel.setText(message);
        statusLabel.setForeground(success ? ModernColors.getSuccess() : ModernColors.getError());

        // 3秒后清除状态
        Timer timer = new Timer(3000, e -> statusLabel.setText(" "));
        timer.setRepeats(false);
        timer.start();
    }
}
