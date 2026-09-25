package com.laker.postman.panel.collections.editor.request.sub;

import com.laker.postman.codegen.json.JsonModelGenerator;
import com.laker.postman.codegen.json.JsonModelLanguage;
import com.laker.postman.codegen.json.CSharpJsonSerializationStyle;
import com.laker.postman.codegen.json.JavaJsonSerializationStyle;
import com.laker.postman.codegen.json.JavaModelStyle;
import com.laker.postman.common.component.FallbackAwareRSyntaxTextArea;
import com.laker.postman.common.component.SyntaxEditorScrollPane;
import com.laker.postman.common.component.ToolWindowSurfaceStyle;
import com.laker.postman.common.component.button.CloseButton;
import com.laker.postman.common.component.button.CopyButton;
import com.laker.postman.common.constants.ModernColors;
import com.laker.postman.util.EditorThemeUtil;
import com.laker.postman.util.I18nUtil;
import com.laker.postman.util.MessageKeys;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;

/** Shared JSON model preview for request and response bodies. */
public final class JsonModelGeneratorDialog extends JDialog {
    private final String json;
    private final JComboBox<JsonModelLanguage> languageCombo = new JComboBox<>(JsonModelLanguage.values());
    private final JComboBox<Object> serializationCombo = new JComboBox<>();
    private final JComboBox<JavaModelStyle> javaModelStyleCombo = new JComboBox<>(JavaModelStyle.values());
    private final JLabel javaSerializationLabel = new JLabel(I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_JAVA_SERIALIZATION));
    private final JLabel javaModelStyleLabel = new JLabel(I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_JAVA_MODEL_STYLE));
    private final JTextField rootNameField;
    private final RSyntaxTextArea preview = new FallbackAwareRSyntaxTextArea();
    private final JLabel hint = new JLabel(I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_HINT));

    private JsonModelGeneratorDialog(Component owner, String json, String suggestedRootName) {
        super(owner instanceof Window window ? window : SwingUtilities.getWindowAncestor(owner),
                I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_TITLE), ModalityType.APPLICATION_MODAL);
        this.json = json;
        this.rootNameField = new JTextField(suggestedRootName);
        ToolWindowSurfaceStyle.applyDialogWindowChrome(this);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(createContent(), BorderLayout.CENTER);
        add(createFooter(), BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setPreferredSize(new Dimension(760, 560));
        pack();
        setMinimumSize(new Dimension(620, 440));
        setLocationRelativeTo(getOwner());
        languageCombo.addActionListener(event -> {
            updateLanguageOptionsVisibility();
            regenerate();
        });
        serializationCombo.addActionListener(event -> regenerate());
        javaModelStyleCombo.addActionListener(event -> regenerate());
        rootNameField.getDocument().addDocumentListener((SimpleDocumentListener) this::regenerate);
        updateLanguageOptionsVisibility();
        regenerate();
    }

    public static void showDialog(Component owner, String json, String suggestedRootName) {
        if (!com.laker.postman.util.JsonUtil.isTypeJSON(json)) {
            com.laker.postman.common.component.notification.NotificationCenter.showWarning(
                    I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_INVALID_JSON));
            return;
        }
        new JsonModelGeneratorDialog(owner, json, suggestedRootName).setVisible(true);
    }

    private JComponent createContent() {
        JPanel outer = new JPanel(new BorderLayout());
        ToolWindowSurfaceStyle.applyDialogSurface(outer);
        JPanel header = new JPanel(new BorderLayout());
        ToolWindowSurfaceStyle.applyDialogHeader(header, 12, 16, 10, 16);
        hint.setForeground(ModernColors.getTextSecondary());
        header.add(hint, BorderLayout.CENTER);
        outer.add(header, BorderLayout.NORTH);

        JPanel content = new JPanel(new MigLayout("insets 12 16 12 16,fill,novisualpadding", "[right]8[grow,fill]", "[]10[grow,fill]"));
        ToolWindowSurfaceStyle.applyDialogSurface(content);
        JLabel languageLabel = new JLabel(I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_LANGUAGE));
        JLabel rootLabel = new JLabel(I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_ROOT_NAME));
        JPanel form = new JPanel(new MigLayout("insets 0,fillx,novisualpadding", "[right]8[180!,fill]18[right]8[grow,fill]", "[]6[]"));
        ToolWindowSurfaceStyle.applyDialogSurface(form);
        form.add(languageLabel);
        form.add(languageCombo, "growx");
        form.add(rootLabel);
        form.add(rootNameField, "growx");
        form.add(javaSerializationLabel, "cell 0 1,hidemode 3");
        form.add(serializationCombo, "cell 1 1,growx,hidemode 3");
        form.add(javaModelStyleLabel, "cell 2 1,hidemode 3");
        form.add(javaModelStyleCombo, "cell 3 1,growx,hidemode 3");
        content.add(form, "span 2,growx,wrap");

        preview.setEditable(false);
        preview.setCodeFoldingEnabled(true);
        preview.setHighlightCurrentLine(false);
        EditorThemeUtil.loadTheme(preview);
        EditorThemeUtil.installViewportClippedTokenPainter(preview);
        SyntaxEditorScrollPane scrollPane = new SyntaxEditorScrollPane(preview);
        scrollPane.setLineNumbersEnabled(true);
        ToolWindowSurfaceStyle.applyDialogScrollPane(scrollPane);
        content.add(scrollPane, "span 2,grow,push");
        outer.add(content, BorderLayout.CENTER);
        return outer;
    }

    private JComponent createFooter() {
        JPanel footer = new JPanel(new MigLayout("insets 6 16 6 16,fillx,novisualpadding", "[grow][]6[]", "[]"));
        ToolWindowSurfaceStyle.applyDialogFooter(footer);
        footer.setPreferredSize(new Dimension(0, 46));
        JButton close = new CloseButton();
        close.getAccessibleContext().setAccessibleName(close.getToolTipText());
        JButton copy = new CopyButton();
        copy.setToolTipText(I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_COPY));
        copy.getAccessibleContext().setAccessibleName(copy.getToolTipText());
        close.addActionListener(event -> dispose());
        copy.addActionListener(event -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(preview.getText()), null);
            com.laker.postman.common.component.notification.NotificationCenter.showSuccess(
                    I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_COPIED));
        });
        footer.add(new JLabel(), "growx");
        footer.add(close);
        footer.add(copy);
        return footer;
    }

    private void regenerate() {
        JsonModelLanguage language = (JsonModelLanguage) languageCombo.getSelectedItem();
        try {
            preview.setSyntaxEditingStyle(language.getSyntaxStyle());
            preview.setText(JsonModelGenerator.generate(json, rootNameField.getText(), language,
                    selectedJavaSerializationStyle(),
                    (JavaModelStyle) javaModelStyleCombo.getSelectedItem(),
                    selectedCSharpSerializationStyle()));
            preview.setCaretPosition(0);
            hint.setText(I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_HINT));
        } catch (RuntimeException exception) {
            preview.setText("");
            hint.setText(I18nUtil.getMessage(MessageKeys.JSON_MODEL_GENERATOR_INVALID_JSON));
        }
    }

    private void updateLanguageOptionsVisibility() {
        boolean java = languageCombo.getSelectedItem() == JsonModelLanguage.JAVA;
        boolean csharp = languageCombo.getSelectedItem() == JsonModelLanguage.CSHARP;
        if (java && !(serializationCombo.getSelectedItem() instanceof JavaJsonSerializationStyle)) {
            serializationCombo.setModel(new DefaultComboBoxModel<>(JavaJsonSerializationStyle.values()));
        } else if (csharp && !(serializationCombo.getSelectedItem() instanceof CSharpJsonSerializationStyle)) {
            serializationCombo.setModel(new DefaultComboBoxModel<>(CSharpJsonSerializationStyle.values()));
        }
        javaSerializationLabel.setVisible(java || csharp);
        serializationCombo.setVisible(java || csharp);
        javaModelStyleLabel.setVisible(java);
        javaModelStyleCombo.setVisible(java);
        javaSerializationLabel.getParent().revalidate();
    }

    private JavaJsonSerializationStyle selectedJavaSerializationStyle() {
        Object selection = serializationCombo.getSelectedItem();
        return selection instanceof JavaJsonSerializationStyle style ? style : JavaJsonSerializationStyle.PLAIN;
    }

    private CSharpJsonSerializationStyle selectedCSharpSerializationStyle() {
        Object selection = serializationCombo.getSelectedItem();
        return selection instanceof CSharpJsonSerializationStyle style ? style : CSharpJsonSerializationStyle.SYSTEM_TEXT_JSON;
    }

    private interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
        void changed();
        default void insertUpdate(javax.swing.event.DocumentEvent event) { changed(); }
        default void removeUpdate(javax.swing.event.DocumentEvent event) { changed(); }
        default void changedUpdate(javax.swing.event.DocumentEvent event) { changed(); }
    }
}
