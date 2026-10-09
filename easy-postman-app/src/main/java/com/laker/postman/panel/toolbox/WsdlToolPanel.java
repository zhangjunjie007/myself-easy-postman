package com.laker.postman.panel.toolbox;

import com.formdev.flatlaf.extras.components.FlatTextField;
import com.formdev.flatlaf.util.SystemFileChooser;
import com.laker.postman.common.UiSingletonFactory;
import com.laker.postman.common.async.EasyTaskExecutor;
import com.laker.postman.common.component.notification.NotificationCenter;
import com.laker.postman.panel.collections.tree.CollectionTreePanel;
import com.laker.postman.service.WorkspaceService;
import com.laker.postman.service.common.TreeNodeBuilder;
import com.laker.postman.service.wsdl.WsdlImportService;
import com.laker.postman.util.FileChooserUtil;
import com.laker.postman.util.FontsUtil;
import com.laker.postman.util.I18nUtil;
import com.laker.postman.util.MessageKeys;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Font;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Toolbox-owned WSDL import page; generated requests use the existing collection and HTTP editor. */
public class WsdlToolPanel extends JPanel {
    private FlatTextField urlField;
    private JButton urlButton;
    private JButton fileButton;
    private JTextArea statusArea;
    private boolean importRunning;

    /** Builds the cached toolbox page without loading a workspace or starting a network request. */
    public WsdlToolPanel() {
        initUI();
    }

    /** Creates a compact import form with wrapping instructions/status and the configured UI font. */
    private void initUI() {
        ToolboxWorkbench.applyRoot(this);
        JPanel form = new JPanel(new MigLayout("insets 0, novisualpadding, fillx, gap 8 10",
                "[pref!][grow,fill][pref!]", ""));
        form.setOpaque(false);
        form.add(ToolboxWorkbench.sectionTitle(I18nUtil.getMessage(MessageKeys.TOOLBOX_WSDL)), "span 3, wrap");

        urlField = new FlatTextField();
        urlField.setFont(FontsUtil.getDefaultFont(Font.PLAIN));
        urlField.setPlaceholderText(I18nUtil.getMessage(MessageKeys.TOOLBOX_WSDL_PLACEHOLDER));
        JLabel label = new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_WSDL_URL));
        label.setFont(FontsUtil.getDefaultFont(Font.PLAIN));
        label.setLabelFor(urlField);
        urlButton = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_WSDL_IMPORT_URL));
        urlButton.setFont(FontsUtil.getDefaultFont(Font.PLAIN));
        urlButton.addActionListener(event -> importWsdlUrl());
        urlField.addActionListener(event -> importWsdlUrl());
        form.add(label);
        form.add(urlField, "wmin 0");
        form.add(urlButton, "wrap");

        fileButton = new JButton(I18nUtil.getMessage(MessageKeys.COLLECTIONS_IMPORT_WSDL_FILE));
        fileButton.setFont(FontsUtil.getDefaultFont(Font.PLAIN));
        fileButton.addActionListener(event -> importWsdlFile());
        form.add(fileButton, "span 3, left, wrap");
        JTextArea help = createReadOnlyText();
        help.setText(I18nUtil.getMessage(MessageKeys.TOOLBOX_WSDL_HELP));
        form.add(help, "span 3, growx, wmin 0, wrap");
        statusArea = createReadOnlyText();
        form.add(statusArea, "span 3, growx, wmin 0");
        add(form, BorderLayout.NORTH);
    }

    /** Keeps long Chinese/English instructions and errors readable without creating editable inputs. */
    private JTextArea createReadOnlyText() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(FontsUtil.getDefaultFont(Font.PLAIN));
        return area;
    }

    /** Validates the inline HTTP(S) address; empty input and repeated Enter cannot start an import. */
    private void importWsdlUrl() {
        if (importRunning) return;
        try {
            URI source = URI.create(urlField.getText().trim());
            if (source.getHost() == null || source.getUserInfo() != null
                    || !("http".equalsIgnoreCase(source.getScheme()) || "https".equalsIgnoreCase(source.getScheme()))) {
                throw new IllegalArgumentException();
            }
            importWsdl(source);
        } catch (IllegalArgumentException exception) {
            showError(MessageKeys.COLLECTIONS_IMPORT_WSDL_INVALID);
        }
    }

    /** Selects a WSDL/XML file; cancelling keeps existing requests and form state intact. */
    private void importWsdlFile() {
        if (importRunning) return;
        SystemFileChooser chooser = FileChooserUtil.createOpenFileChooser("collections.import.wsdl",
                I18nUtil.getMessage(MessageKeys.COLLECTIONS_IMPORT_WSDL_FILE));
        chooser.setFileFilter(FileChooserUtil.extensionFilter(
                I18nUtil.getMessage(MessageKeys.COLLECTIONS_IMPORT_WSDL_FILTER), "wsdl", "xml"));
        if (chooser.showOpenDialog(this) == SystemFileChooser.APPROVE_OPTION) {
            importWsdl(chooser.getSelectedFile().toURI());
        }
    }

    /**
     * Waits up to 60 seconds for the initial collection load and loads/parses WSDL off EDT,
     * then inserts a complete group only in the original workspace.
     * Parse failures insert nothing; save failures retain the generated group for export.
     * @param source selected file or validated HTTP(S) WSDL URI; downloads use no business token
     */
    private void importWsdl(URI source) {
        if (importRunning) return;
        var workspace = WorkspaceService.getInstance().getCurrentWorkspace();
        CollectionTreePanel panel = UiSingletonFactory.getInstance(CollectionTreePanel.class);
        setImportRunning(true);
        statusArea.setText(I18nUtil.getMessage(MessageKeys.COLLECTIONS_IMPORT_WSDL_LOADING));
        EasyTaskExecutor.execute(() -> {
            try {
                panel.getCollectionTreePersistence().whenTreeLoaded().get(60, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException exception) {
                throw new IOException(MessageKeys.TOOLBOX_WSDL_COLLECTION_LOAD_FAILED);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(MessageKeys.TOOLBOX_WSDL_COLLECTION_LOAD_FAILED);
            }
            return WsdlImportService.importWsdl(source);
        }, result -> {
            setImportRunning(false);
            if (WorkspaceService.getInstance().getCurrentWorkspace() != workspace) {
                showError(MessageKeys.COLLECTIONS_IMPORT_WSDL_WORKSPACE_CHANGED);
                return;
            }
            DefaultMutableTreeNode group = TreeNodeBuilder.buildFromParseResult(result);
            panel.getRootTreeNode().add(group);
            panel.getTreeModel().reload();
            panel.getRequestTree().expandPath(new TreePath(group.getPath()));
            if (!panel.getCollectionTreePersistence().trySaveCurrentTree()) {
                showError(MessageKeys.COLLECTIONS_IMPORT_WSDL_SAVE_FAILED);
                return;
            }
            statusArea.setText(I18nUtil.getMessage(MessageKeys.TOOLBOX_WSDL_SUCCESS));
            NotificationCenter.showSuccess(statusArea.getText());
        }, error -> {
            setImportRunning(false);
            String key = error.getMessage();
            showError(key != null && (key.startsWith("collections.import.wsdl.")
                    || MessageKeys.TOOLBOX_WSDL_COLLECTION_LOAD_FAILED.equals(key))
                    ? key : MessageKeys.COLLECTIONS_IMPORT_WSDL_INVALID);
        }, "WSDL-Import");
    }

    /** Disables both sources and the Enter shortcut until the single active import finishes. */
    private void setImportRunning(boolean running) {
        importRunning = running;
        urlField.setEnabled(!running);
        urlButton.setEnabled(!running);
        fileButton.setEnabled(!running);
    }

    /** Displays a translated error inline and through the existing notification system. */
    private void showError(String key) {
        statusArea.setText(I18nUtil.getMessage(key));
        NotificationCenter.showError(statusArea.getText());
    }
}
