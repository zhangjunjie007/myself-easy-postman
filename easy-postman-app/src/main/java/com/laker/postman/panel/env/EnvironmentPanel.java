package com.laker.postman.panel.env;

import com.laker.postman.common.component.notification.NotificationCenter;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.formdev.flatlaf.util.SystemFileChooser;
import com.laker.postman.common.UiSingletonPanel;
import com.laker.postman.common.UiSingletonFactory;
import com.laker.postman.common.component.SearchTextField;
import com.laker.postman.common.component.ToolWindowActionToolbar;
import com.laker.postman.common.component.AppToolWindowChrome;
import com.laker.postman.common.component.ToolWindowSidebarToolbar;
import com.laker.postman.common.component.ToolWindowSurfaceStyle;
import com.laker.postman.common.component.button.PlusButton;
import com.laker.postman.common.component.button.SaveButton;
import com.laker.postman.common.component.combobox.EnvironmentComboBox;
import com.laker.postman.common.component.dialog.TextInputDialog;
import com.laker.postman.common.component.list.EnvironmentListCellRenderer;
import com.laker.postman.model.Environment;
import com.laker.postman.model.PlmAuthConfig;
import com.laker.postman.environment.EnvironmentItem;
import com.laker.postman.model.Variable;
import com.laker.postman.model.Workspace;
import com.laker.postman.panel.topmenu.TopMenuBar;
import com.laker.postman.service.EnvironmentService;
import com.laker.postman.service.PlmEnvironmentAuthService;
import com.laker.postman.request.util.HttpUrlUtil;
import com.laker.postman.service.ideahttp.IntelliJHttpEnvParser;
import com.laker.postman.service.postman.PostmanEnvironmentParser;
import com.laker.postman.panel.workspace.WorkspaceTransferCoordinator;
import com.laker.postman.util.*;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境列表及 PLM 认证参数面板
 */
@Slf4j
public class EnvironmentPanel extends UiSingletonPanel {
    public static final String EXPORT_FILE_NAME = "EasyPostman-Environments.json";
    private static final String[] JIT_FIELDS = {"type", "baseUrl", "uaaBaseUrl", "tokenUrl", "method",
            "clientId", "clientSecret", "grantType", "principalHeader", "principalPrefix", "principal",
            "tokenJsonPath", "headerName", "headerPrefix"};
    private static final String[] PASSWORD_FIELDS = {"type", "baseUrl", "uaaBaseUrl", "tokenUrl", "method",
            "clientId", "clientSecret", "username", "password", "grantType", "tokenJsonPath",
            "headerName", "headerPrefix"};
    private static final String[] PIN_FIELDS = {"type", "baseUrl", "uaaBaseUrl", "tokenUrl", "method",
            "pin", "pinLocation", "pinName", "bodyTemplate", "headers", "tokenJsonPath",
            "headerName", "headerPrefix"};
    private JTable authTable;
    private DefaultTableModel authTableModel;
    private transient Environment currentEnvironment;
    private JList<EnvironmentItem> environmentList;
    private DefaultListModel<EnvironmentItem> environmentListModel;
    private SearchTextField searchField;
    private PlusButton plusBtn;
    private String originalAuthSnapshot;
    private boolean isLoadingData = false; // 用于控制是否正在加载数据，防止自动保存
    private SearchTextField tableSearchField; // 表格搜索框
    private JPanel toolbarPanel; // 表格工具栏面板

    @Override
    protected void initUI() {
        setLayout(new BorderLayout());
        ToolWindowSurfaceStyle.applyBackground(this);
        setPreferredSize(new Dimension(700, 400));

        // 左侧环境列表面板
        JPanel leftPanel = new JPanel(new BorderLayout());
        ToolWindowSurfaceStyle.applyCard(leftPanel);
        leftPanel.setPreferredSize(new Dimension(AppToolWindowChrome.DEFAULT_SIDE_WIDTH, 200));
        leftPanel.setMinimumSize(new Dimension(220, 160));
        // 顶部搜索和导入导出按钮
        leftPanel.add(getSearchAndImportPanel(), BorderLayout.NORTH);

        // 环境列表
        environmentListModel = new DefaultListModel<>();
        environmentList = new JList<>(environmentListModel);
        environmentList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); // 支持多选
        environmentList.setFixedCellHeight(28); // 设置每行高度
        environmentList.setCellRenderer(new EnvironmentListCellRenderer());
        environmentList.setFixedCellWidth(0); // 让JList自适应宽度
        environmentList.setVisibleRowCount(-1); // 让JList显示所有行
        JScrollPane envListScroll = new JScrollPane(environmentList);
        envListScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); // 禁用横向滚动条
        ToolWindowSurfaceStyle.applyListScrollPaneCard(envListScroll, environmentList);
        leftPanel.add(envListScroll, BorderLayout.CENTER);

        // 右侧直接编辑当前环境的 PLM 认证参数。
        JPanel rightPanel = new JPanel(new BorderLayout());
        ToolWindowSurfaceStyle.applyCard(rightPanel);
        rightPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        // 顶部工具栏：保存按钮和搜索框
        JPanel tableToolbarPanel = createTableToolbar();
        rightPanel.add(tableToolbarPanel, BorderLayout.NORTH);

        // 固定字段表格保留原有双栏布局，不提供无关的任意变量行。
        JPanel tableContainer = new JPanel(new BorderLayout());
        ToolWindowSurfaceStyle.applyCard(tableContainer);
        tableContainer.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        authTableModel = new DefaultTableModel(new Object[]{"Name", "Value"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1 && !"type".equals(getValueAt(row, 0));
            }
        };
        authTable = new JTable(authTableModel);
        authTable.setRowHeight(32);
        authTable.setFillsViewportHeight(true);
        authTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JScrollPane authScrollPane = new JScrollPane(authTable);
        ToolWindowSurfaceStyle.applyTableScrollPaneCard(authScrollPane, authTable);
        tableContainer.add(authScrollPane, BorderLayout.CENTER);
        rightPanel.add(tableContainer, BorderLayout.CENTER);


        // 使用 JSplitPane 将左右两个面板组合，支持拖动调整大小
        JSplitPane splitPane = AppToolWindowChrome.createHorizontalCardSplitPane(
                leftPanel,
                rightPanel,
                AppToolWindowChrome.DEFAULT_SIDE_WIDTH
        );
        splitPane.setResizeWeight(0.3); // 设置左侧面板调整权重（30%）

        add(splitPane, BorderLayout.CENTER);

        initAuthAutoSave();
    }

    /** 创建 PLM 参数表格的保存和搜索工具栏。 */
    private JPanel createTableToolbar() {
        SaveButton saveButton = new SaveButton();
        saveButton.addActionListener(e -> saveAuthManually());

        // 表格搜索框
        tableSearchField = new SearchTextField();
        tableSearchField.setPreferredSize(new Dimension(200, ToolWindowSidebarToolbar.SEARCH_HEIGHT));
        tableSearchField.setMaximumSize(new Dimension(200, ToolWindowSidebarToolbar.SEARCH_HEIGHT));
        tableSearchField.addActionListener(e -> filterTableRows());

        // 监听搜索选项变化，触发重新过滤
        tableSearchField.addPropertyChangeListener("caseSensitive", evt -> {
            if (!tableSearchField.getText().isEmpty()) {
                filterTableRows();
            }
        });
        tableSearchField.addPropertyChangeListener("wholeWord", evt -> {
            if (!tableSearchField.getText().isEmpty()) {
                filterTableRows();
            }
        });

        toolbarPanel = ToolWindowActionToolbar.right(saveButton, tableSearchField);

        return toolbarPanel;
    }

    /** Saves a committed table value immediately, without writing any token to the environment. */
    private void initAuthAutoSave() {
        authTableModel.addTableModelListener(event -> {
            if (!isLoadingData && event.getType() == TableModelEvent.UPDATE) {
                saveAuth();
            }
        });
    }

    /** Supplies the fixed JIT fields for a new environment; the principal remains a visible placeholder. */
    private PlmAuthConfig defaultAuth() {
        PlmAuthConfig auth = new PlmAuthConfig();
        auth.setType("jit-token");
        auth.setTokenUrl("{{uaaBaseUrl}}/uaa/oauth2/token");
        auth.setMethod("POST");
        auth.setClientId("app");
        auth.setClientSecret("secret");
        auth.setGrantType("jit");
        auth.setPrincipalHeader("dnname");
        auth.setPrincipalPrefix("T=");
        auth.setPrincipal("REPLACE_WITH_ID_CARD_NUMBER");
        auth.setTokenJsonPath("access_token");
        auth.setHeaderName("Authorization");
        auth.setHeaderPrefix("Bearer ");
        return auth;
    }

    private JPanel getSearchAndImportPanel() {
        plusBtn = new PlusButton();
        plusBtn.setToolTipText("New / Import");
        plusBtn.addActionListener(e -> showPlusMenu());

        searchField = new SearchTextField();

        return new ToolWindowSidebarToolbar(plusBtn, searchField);
    }

    private void showPlusMenu() {
        JPopupMenu menu = new JPopupMenu();
        ToolWindowSurfaceStyle.applyPopupMenuCard(menu);

        // ── 新建环境
        JMenuItem newEnvItem = new JMenuItem(I18nUtil.getMessage(MessageKeys.ENV_BUTTON_ADD),
                IconUtil.createThemed("icons/plus.svg", IconUtil.SIZE_MEDIUM, IconUtil.SIZE_MEDIUM));
        newEnvItem.addActionListener(e -> addEnvironment());
        menu.add(newEnvItem);

        menu.addSeparator();

        // ── 导入
        JMenuItem importEasyToolsItem = new JMenuItem(I18nUtil.getMessage(MessageKeys.ENV_MENU_IMPORT_EASY),
                IconUtil.create("icons/easy.svg", IconUtil.SIZE_MEDIUM, IconUtil.SIZE_MEDIUM));
        importEasyToolsItem.addActionListener(e -> importEnvironments());
        menu.add(importEasyToolsItem);

        JMenuItem importPostmanItem = new JMenuItem(I18nUtil.getMessage(MessageKeys.ENV_MENU_IMPORT_POSTMAN),
                IconUtil.create("icons/postman.svg", IconUtil.SIZE_MEDIUM, IconUtil.SIZE_MEDIUM));
        importPostmanItem.addActionListener(e -> importPostmanEnvironments());
        menu.add(importPostmanItem);

        JMenuItem importIntelliJItem = new JMenuItem(I18nUtil.getMessage(MessageKeys.ENV_MENU_IMPORT_INTELLIJ),
                IconUtil.create("icons/idea-http.svg", IconUtil.SIZE_MEDIUM, IconUtil.SIZE_MEDIUM));
        importIntelliJItem.addActionListener(e -> importIntelliJEnvironments());
        menu.add(importIntelliJItem);

        menu.show(plusBtn, 0, plusBtn.getHeight());
    }

    @Override
    protected void registerListeners() {
        // 联动菜单栏右上角下拉框
        EnvironmentComboBox topComboBox = UiSingletonFactory.getInstance(TopMenuBar.class).getEnvironmentComboBox();
        if (topComboBox != null) {
            topComboBox.setOnEnvironmentChange(env -> {
                environmentListModel.clear();
                List<Environment> envs = EnvironmentService.getAllEnvironments();
                for (Environment envItem : envs) {
                    environmentListModel.addElement(new EnvironmentItem(envItem));
                }
                if (!environmentListModel.isEmpty()) {
                    environmentList.setSelectedIndex(topComboBox.getSelectedIndex()); // 设置选中当前激活环境
                }
                loadActiveEnvironmentVariables();
            });
        }
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                reloadEnvironmentList(searchField.getText());
            }

            public void removeUpdate(DocumentEvent e) {
                reloadEnvironmentList(searchField.getText());
            }

            public void changedUpdate(DocumentEvent e) {
                reloadEnvironmentList(searchField.getText());
            }
        });

        // 表格搜索框监听器
        tableSearchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                filterTableRows();
            }

            public void removeUpdate(DocumentEvent e) {
                filterTableRows();
            }

            public void changedUpdate(DocumentEvent e) {
                filterTableRows();
            }
        });
        environmentList.addListSelectionListener(e -> { // 监听环境列表左键
            if (!e.getValueIsAdjusting()) {
                EnvironmentItem item = environmentList.getSelectedValue();
                if (item == null || item.getEnvironment() == currentEnvironment) {
                    return; // 没有切换环境，不处理
                }
                loadAuth(item.getEnvironment());
            }
        });
        // 环境列表右键菜单
        addRightMenuList();

        // 添加手动保存快捷键（虽然有自动保存，但保留手动保存让用户有掌控感）
        addSaveKeyStroke();

        // 默认加载当前激活环境的 PLM 参数
        loadActiveEnvironmentVariables();

        // 环境列表加载与搜索
        reloadEnvironmentList("");

    }

    /**
     * 添加手动保存快捷键（Cmd+S / Ctrl+S）
     * 虽然已有自动保存，但保留手动保存快捷键让用户有主动掌控感
     */
    private void addSaveKeyStroke() {
        KeyStroke saveKeyStroke = KeyStroke.getKeyStroke("meta S"); // Mac Command+S
        KeyStroke saveKeyStroke2 = KeyStroke.getKeyStroke("control S"); // Windows/Linux Ctrl+S
        String actionKey = "saveEnvironmentVariables";
        this.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(saveKeyStroke, actionKey);
        this.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(saveKeyStroke2, actionKey);
        this.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                // 先将焦点转移到面板自身，触发 table cell editor 的
                // terminateEditOnFocusLost 机制，确保正在编辑的 cell
                // 值提交到 model，然后再执行保存逻辑。
                EnvironmentPanel.this.requestFocusInWindow();
                SwingUtilities.invokeLater(() -> saveAuthManually());
            }
        });
    }


    private void addRightMenuList() {
        JPopupMenu envListMenu = new JPopupMenu();
        ToolWindowSurfaceStyle.applyPopupMenuCard(envListMenu);
        JMenuItem addItem = new JMenuItem(I18nUtil.getMessage(MessageKeys.ENV_BUTTON_ADD),
                IconUtil.createThemed("icons/environments.svg", IconUtil.SIZE_SMALL, IconUtil.SIZE_SMALL));
        addItem.addActionListener(e -> addEnvironment());
        envListMenu.add(addItem);
        envListMenu.addSeparator();
        JMenuItem renameItem = new JMenuItem(I18nUtil.getMessage(MessageKeys.ENV_BUTTON_RENAME),
                IconUtil.createThemed("icons/refresh.svg", IconUtil.SIZE_SMALL, IconUtil.SIZE_SMALL));
        // 设置 F2 快捷键显示
        renameItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0));
        JMenuItem copyItem = new JMenuItem(I18nUtil.getMessage(MessageKeys.ENV_BUTTON_DUPLICATE),
                IconUtil.createThemed("icons/duplicate.svg", IconUtil.SIZE_SMALL, IconUtil.SIZE_SMALL)); // 复制菜单项
        JMenuItem deleteItem = new JMenuItem(I18nUtil.getMessage(MessageKeys.ENV_BUTTON_DELETE),
                IconUtil.createThemed("icons/close.svg", IconUtil.SIZE_SMALL, IconUtil.SIZE_SMALL));
        // 设置 Delete 快捷键显示
        deleteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));

        // 动态更新删除菜单项文本（显示选中数量）
        envListMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                int selectedCount = environmentList.getSelectedIndices().length;
                if (selectedCount > 1) {
                    deleteItem.setText(I18nUtil.getMessage(MessageKeys.ENV_BUTTON_DELETE) + " (" + selectedCount + ")");
                } else {
                    deleteItem.setText(I18nUtil.getMessage(MessageKeys.ENV_BUTTON_DELETE));
                }

                // 复制和重命名只在单选时可用
                renameItem.setEnabled(selectedCount == 1);
                copyItem.setEnabled(selectedCount == 1);
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // 菜单隐藏时无需处理
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                // 菜单取消时无需处理
            }
        });

        JMenuItem exportPostmanItem = new JMenuItem(I18nUtil.getMessage(MessageKeys.ENV_BUTTON_EXPORT_POSTMAN),
                IconUtil.create("icons/postman.svg", IconUtil.SIZE_SMALL, IconUtil.SIZE_SMALL)); // 彩色
        exportPostmanItem.addActionListener(e -> exportSelectedEnvironmentAsPostman());
        renameItem.addActionListener(e -> renameSelectedEnvironment());
        copyItem.addActionListener(e -> copySelectedEnvironment()); // 复制事件
        deleteItem.addActionListener(e -> deleteSelectedEnvironments()); // 改为批量删除
        envListMenu.add(renameItem);
        envListMenu.add(copyItem);
        envListMenu.add(deleteItem);
        envListMenu.addSeparator();
        envListMenu.add(exportPostmanItem);

        // 转移到其他工作区
        JMenuItem moveToWorkspaceItem = new JMenuItem(I18nUtil.getMessage(MessageKeys.WORKSPACE_TRANSFER_MENU_ITEM),
                IconUtil.createThemed("icons/workspace.svg", IconUtil.SIZE_SMALL, IconUtil.SIZE_SMALL));
        moveToWorkspaceItem.addActionListener(e -> moveEnvironmentToWorkspace());
        envListMenu.add(moveToWorkspaceItem);

        // 添加键盘监听器，支持 F2 重命名和 Delete 删除
        environmentList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int selectedCount = environmentList.getSelectedIndices().length;
                if (selectedCount > 0) {
                    if (e.getKeyCode() == KeyEvent.VK_F2 && selectedCount == 1) {
                        // F2 重命名（仅单选时）
                        renameSelectedEnvironment();
                    } else if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                        // Delete 或 Backspace 批量删除
                        deleteSelectedEnvironments();
                    }
                }
            }
        });

        environmentList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3) { // 右键菜单
                    int idx = environmentList.locationToIndex(e.getPoint());
                    if (idx >= 0) {
                        // 只有当点击的项目不在当前选中列表中时，才改变选择
                        int[] selectedIndices = environmentList.getSelectedIndices();
                        boolean isSelected = false;
                        for (int selectedIdx : selectedIndices) {
                            if (selectedIdx == idx) {
                                isSelected = true;
                                break;
                            }
                        }
                        // 如果点击的项目未被选中，则设置为当前选中项
                        if (!isSelected) {
                            environmentList.setSelectedIndex(idx);
                        }
                        // 如果点击的项目已经被选中，则保持当前的多选状态
                    }
                    envListMenu.show(environmentList, e.getX(), e.getY());
                }
                // 双击激活环境并联动下拉框
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                    int idx = environmentList.locationToIndex(e.getPoint());
                    if (idx >= 0) {
                        environmentList.setSelectedIndex(idx);
                        EnvironmentItem item = environmentList.getModel().getElementAt(idx);
                        if (item != null) {
                            Environment env = item.getEnvironment();
                            // 激活环境
                            EnvironmentService.setActiveEnvironment(env.getId());
                            // 联动顶部下拉框
                            EnvironmentComboBox comboBox = UiSingletonFactory.getInstance(TopMenuBar.class).getEnvironmentComboBox();
                            if (comboBox != null) {
                                comboBox.setSelectedEnvironment(env);
                            }
                            // 刷新面板
                            refreshUI();
                        }
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) mousePressed(e);
            }
        });
        // 拖拽排序支持
        environmentList.setDragEnabled(true);
        environmentList.setDropMode(DropMode.INSERT);
        environmentList.setTransferHandler(new TransferHandler() {
            private int fromIndex = -1;

            @Override
            protected Transferable createTransferable(JComponent c) {
                fromIndex = environmentList.getSelectedIndex();
                EnvironmentItem selected = environmentList.getSelectedValue();
                return new StringSelection(selected != null ? selected.toString() : "");
            }

            @Override
            public int getSourceActions(JComponent c) {
                return MOVE;
            }

            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop();
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                JList.DropLocation dl = (JList.DropLocation) support.getDropLocation();
                int toIndex = dl.getIndex();
                if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return false;
                EnvironmentItem moved = environmentListModel.getElementAt(fromIndex);
                environmentListModel.remove(fromIndex);
                if (toIndex > fromIndex) toIndex--;
                environmentListModel.add(toIndex, moved);
                environmentList.setSelectedIndex(toIndex);
                // 1. 同步顺序到 EnvironmentService
                persistEnvironmentOrder();
                // 2. 同步到顶部下拉框
                syncComboBoxOrder();
                return true;
            }
        });
    }

    /** Keeps the existing public refresh entry point while showing PLM fields for the active environment. */
    public void loadActiveEnvironmentVariables() {
        Environment env = EnvironmentService.getActiveEnvironment();
        loadAuth(env);
    }

    /** Loads the business base URL and PLM authentication fields into the existing table. */
    private void loadAuth(Environment env) {
        if (authTable.isEditing()) {
            authTable.getCellEditor().stopCellEditing();
        }
        isLoadingData = true;
        try {
            currentEnvironment = env;
            authTableModel.setRowCount(0);
            if (env != null) {
                PlmAuthConfig auth = env.getAuth() == null ? defaultAuth() : env.getAuth();
                JSONObject values = JSONUtil.parseObj(JSONUtil.toJsonStr(auth));
                String[] fields = switch (auth.getType() == null ? "jit-token" : auth.getType()) {
                    case "password-token" -> PASSWORD_FIELDS;
                    case "pin-token" -> PIN_FIELDS;
                    default -> JIT_FIELDS;
                };
                for (String field : fields) {
                    Object value = switch (field) {
                        case "baseUrl", "uaaBaseUrl" -> env.get(field);
                        default -> values.get(field);
                    };
                    authTableModel.addRow(new Object[]{field, value == null ? ""
                            : value instanceof String ? value : JSONUtil.toJsonStr(value)});
                }
            }
            originalAuthSnapshot = JSONUtil.toJsonStr(authTableModel.getDataVector());
            filterTableRows();
        } finally {
            isLoadingData = false;
        }
    }

    /** Persists separate business/UAA URLs and PLM fields; restores the old environment on save failure. */
    private boolean saveAuth() {
        if (currentEnvironment == null) {
            return false;
        }
        String snapshot = JSONUtil.toJsonStr(authTableModel.getDataVector());
        if (snapshot.equals(originalAuthSnapshot) && currentEnvironment.getAuth() != null) {
            return true;
        }
        Environment env = currentEnvironment;
        PlmAuthConfig previousAuth = env.getAuth();
        List<Variable> previousVariables = env.getVariableList();
        try {
            JSONObject values = JSONUtil.parseObj(JSONUtil.toJsonStr(
                    previousAuth == null ? defaultAuth() : previousAuth));
            String uaaBaseUrl = null;
            String baseUrl = null;
            for (int row = 0; row < authTableModel.getRowCount(); row++) {
                String field = String.valueOf(authTableModel.getValueAt(row, 0));
                String value = String.valueOf(authTableModel.getValueAt(row, 1));
                switch (field) {
                    case "uaaBaseUrl" -> uaaBaseUrl = value;
                    case "baseUrl" -> baseUrl = value;
                    case "bodyTemplate", "headers" -> values.set(field,
                            value.isBlank() ? null : JSONUtil.parse(value));
                    default -> values.set(field, value);
                }
            }
            PlmAuthConfig auth = JSONUtil.toBean(values, PlmAuthConfig.class);
            PlmEnvironmentAuthService.validate(auth);
            if (baseUrl != null && !baseUrl.isBlank()) {
                // Validate with the same URL rules used when sending relative paths.
                HttpUrlUtil.resolveAgainstBaseUrl(
                        "/",
                        baseUrl
                );
            }

            // Copy variables before syncing URLs, so a failed disk save cannot alter the old environment.
            List<Variable> editedVariables = new ArrayList<>();
            if (previousVariables != null) {
                for (Variable variable : previousVariables) {
                    editedVariables.add(new Variable(variable.isEnabled(), variable.getKey(), variable.getValue()));
                }
            }
            env.setVariableList(editedVariables);
            updateUrlVariable(env, "uaaBaseUrl", uaaBaseUrl);
            updateUrlVariable(
                    env,
                    "baseUrl",
                    baseUrl == null ? null : baseUrl.trim()
            );
            env.setAuth(auth);
            EnvironmentService.saveEnvironment(env);
            originalAuthSnapshot = snapshot;
            return true;
        } catch (Exception exception) {
            env.setAuth(previousAuth);
            env.setVariableList(previousVariables);
            JOptionPane.showMessageDialog(this, I18nUtil.getMessage(MessageKeys.ENV_PLM_AUTH_INVALID),
                    I18nUtil.getMessage(MessageKeys.GENERAL_ERROR), JOptionPane.ERROR_MESSAGE);
            SwingUtilities.invokeLater(() -> {
                if (currentEnvironment == env) {
                    loadAuth(env);
                }
            });
            return false;
        }
    }

    /** Sets or removes one URL variable while preserving other imported request variables. */
    private void updateUrlVariable(Environment env, String key, String value) {
        if (value == null || value.isBlank()) {
            env.removeVariable(key);
        } else {
            env.set(key, value);
        }
    }

    /** Commits the current cell and shows feedback for the existing Save button or Ctrl+S shortcut. */
    private void saveAuthManually() {
        if (authTable.isEditing()) {
            authTable.getCellEditor().stopCellEditing();
        }
        if (saveAuth()) {
            NotificationCenter.showSuccess(I18nUtil.getMessage(MessageKeys.ENV_DIALOG_SAVE_SUCCESS));
        }
    }

    /**
     * 导出所有环境变量为JSON文件
     */
    public void exportEnvironments() {
        SystemFileChooser fileChooser = FileChooserUtil.createSaveFileChooser(
                "environments.export",
                I18nUtil.getMessage(MessageKeys.ENV_DIALOG_EXPORT_TITLE));
        fileChooser.setSelectedFile(new File(EXPORT_FILE_NAME));
        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == SystemFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(fileToSave), StandardCharsets.UTF_8)) {
                java.util.List<Environment> envs = EnvironmentService.getAllEnvironments();
                writer.write(JSONUtil.toJsonPrettyStr(envs));
                NotificationCenter.showSuccess(I18nUtil.getMessage(MessageKeys.ENV_DIALOG_EXPORT_SUCCESS));
            } catch (Exception ex) {
                log.error("Export Error", ex);
                JOptionPane.showMessageDialog(this,
                        I18nUtil.getMessage(MessageKeys.ENV_DIALOG_EXPORT_FAIL, ex.getMessage()),
                        I18nUtil.getMessage(MessageKeys.GENERAL_ERROR), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** 导入环境 JSON；解析异常可能包含明文凭据，不能直接输出到日志或弹窗。 */
    private void importEnvironments() {
        SystemFileChooser fileChooser = FileChooserUtil.createOpenFileChooser(
                "environments.import.easyPostman",
                I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_EASY_TITLE));
        int userSelection = fileChooser.showOpenDialog(this);
        if (userSelection == SystemFileChooser.APPROVE_OPTION) {
            File fileToOpen = fileChooser.getSelectedFile();
            try {
                java.util.List<Environment> envs = JSONUtil.toList(JSONUtil.readJSONArray(fileToOpen, StandardCharsets.UTF_8), Environment.class);
                // 导入新环境
                refreshListAndComboFromAdd(envs);
            } catch (Exception ex) {
                log.warn("Import Error: invalid environment data");
                JOptionPane.showMessageDialog(this,
                        I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_EASY_FAIL,
                                I18nUtil.getMessage(MessageKeys.ENV_PLM_AUTH_INVALID)),
                        I18nUtil.getMessage(MessageKeys.GENERAL_ERROR), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void refreshListAndComboFromAdd(List<Environment> envs) {
        EnvironmentComboBox environmentComboBox = UiSingletonFactory.getInstance(TopMenuBar.class).getEnvironmentComboBox();
        for (Environment env : envs) {
            EnvironmentService.saveEnvironment(env);
            environmentComboBox.addItem(new EnvironmentItem(env)); // 添加到下拉框
            environmentListModel.addElement(new EnvironmentItem(env)); // 添加到列表
        }
        NotificationCenter.showSuccess(I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_EASY_SUCCESS));
    }

    /**
     * 导入Postman环境变量JSON文件
     */
    private void importPostmanEnvironments() {
        SystemFileChooser fileChooser = FileChooserUtil.createOpenFileChooser(
                "environments.import.postman",
                I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_POSTMAN_TITLE));
        int userSelection = fileChooser.showOpenDialog(this);
        if (userSelection == SystemFileChooser.APPROVE_OPTION) {
            java.io.File fileToOpen = fileChooser.getSelectedFile();
            try {
                String json = FileUtil.readString(fileToOpen, StandardCharsets.UTF_8);
                List<Environment> envs = PostmanEnvironmentParser.parsePostmanEnvironments(json);
                if (!envs.isEmpty()) {
                    // 导入新环境
                    refreshListAndComboFromAdd(envs);
                } else {
                    JOptionPane.showMessageDialog(this,
                            I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_POSTMAN_INVALID),
                            I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_POSTMAN_TITLE), JOptionPane.WARNING_MESSAGE);
                }
            } catch (Exception ex) {
                log.error("Import Error", ex);
                JOptionPane.showMessageDialog(this,
                        I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_POSTMAN_FAIL, ex.getMessage()),
                        I18nUtil.getMessage(MessageKeys.GENERAL_ERROR), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * 导入IntelliJ IDEA HTTP Client环境变量JSON文件
     */
    private void importIntelliJEnvironments() {
        SystemFileChooser fileChooser = FileChooserUtil.createOpenFileChooser(
                "environments.import.intellijHttp",
                I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_INTELLIJ_TITLE));
        int userSelection = fileChooser.showOpenDialog(this);
        if (userSelection == SystemFileChooser.APPROVE_OPTION) {
            File fileToOpen = fileChooser.getSelectedFile();
            try {
                String json = FileUtil.readString(fileToOpen, StandardCharsets.UTF_8);
                List<Environment> envs = IntelliJHttpEnvParser.parseIntelliJEnvironments(json);
                if (!envs.isEmpty()) {
                    // 导入新环境
                    refreshListAndComboFromAdd(envs);
                } else {
                    JOptionPane.showMessageDialog(this,
                            I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_INTELLIJ_INVALID),
                            I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_INTELLIJ_TITLE), JOptionPane.WARNING_MESSAGE);
                }
            } catch (Exception ex) {
                log.error("Import IntelliJ Error", ex);
                JOptionPane.showMessageDialog(this,
                        I18nUtil.getMessage(MessageKeys.ENV_DIALOG_IMPORT_INTELLIJ_FAIL, ex.getMessage()),
                        I18nUtil.getMessage(MessageKeys.GENERAL_ERROR), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // 新增环境
    private void addEnvironment() {
        TextInputDialog.showRequiredName(this,
                I18nUtil.getMessage(MessageKeys.ENV_DIALOG_ADD_TITLE),
                "",
                I18nUtil.getMessage(MessageKeys.ENV_DIALOG_NAME_EMPTY)
        ).ifPresent(name -> {
            Environment env = new Environment(name);
            env.setId("env-" + IdUtil.simpleUUID());
            EnvironmentService.saveEnvironment(env);
            environmentListModel.addElement(new EnvironmentItem(env));
            environmentList.setSelectedValue(new EnvironmentItem(env), true);
            EnvironmentComboBox environmentComboBox = UiSingletonFactory.getInstance(TopMenuBar.class).getEnvironmentComboBox();
            if (environmentComboBox != null) {
                environmentComboBox.addItem(new EnvironmentItem(env));
            }
        });
    }

    private void reloadEnvironmentList(String filter) {
        environmentListModel.clear();
        java.util.List<Environment> envs = EnvironmentService.getAllEnvironments();
        int activeIdx = -1;
        for (Environment env : envs) {
            if (filter == null || filter.isEmpty() || env.getName().toLowerCase().contains(filter.toLowerCase())) {
                environmentListModel.addElement(new EnvironmentItem(env));
                if (env.isActive()) {
                    activeIdx = environmentListModel.size() - 1;
                }
            }
        }
        if (!environmentListModel.isEmpty()) {
            environmentList.setSelectedIndex(Math.max(activeIdx, 0));
        }
        // 无结果且有搜索词时搜索框变红
        boolean noResult = environmentListModel.isEmpty() && filter != null && !filter.isEmpty();
        searchField.setNoResult(noResult);
    }

    private void renameSelectedEnvironment() {
        EnvironmentItem item = environmentList.getSelectedValue();
        if (item == null) return;
        Environment env = item.getEnvironment();
        TextInputDialog.showRequiredName(this,
                I18nUtil.getMessage(MessageKeys.ENV_DIALOG_RENAME_TITLE),
                env.getName(),
                I18nUtil.getMessage(MessageKeys.ENV_DIALOG_NAME_EMPTY)
        ).ifPresent(newName -> {
            if (newName.equals(env.getName())) {
                return;
            }
            env.setName(newName);
            EnvironmentService.saveEnvironment(env);
            environmentListModel.setElementAt(new EnvironmentItem(env), environmentList.getSelectedIndex());
            // 同步刷新顶部环境下拉框
            UiSingletonFactory.getInstance(TopMenuBar.class).getEnvironmentComboBox().reload();
        });
    }

    /**
     * 批量删除选中的环境
     */
    private void deleteSelectedEnvironments() {
        List<EnvironmentItem> selectedItems = environmentList.getSelectedValuesList();
        if (selectedItems == null || selectedItems.isEmpty()) {
            return;
        }

        int selectedCount = selectedItems.size();
        String message;
        String title = I18nUtil.getMessage(MessageKeys.ENV_DIALOG_DELETE_TITLE);

        if (selectedCount == 1) {
            // 单个删除
            Environment env = selectedItems.get(0).getEnvironment();
            message = I18nUtil.getMessage(MessageKeys.ENV_DIALOG_DELETE_PROMPT, env.getName());
        } else {
            // 批量删除
            message = I18nUtil.getMessage(MessageKeys.ENV_DIALOG_DELETE_BATCH_PROMPT, selectedCount);
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                message,
                title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            int deletedCount = 0;
            // 删除选中的环境
            for (EnvironmentItem item : selectedItems) {
                try {
                    Environment env = item.getEnvironment();
                    environmentListModel.removeElement(item);
                    EnvironmentService.deleteEnvironment(env.getId());
                    deletedCount++;
                    log.info("已删除环境: {}", env.getName());
                } catch (Exception e) {
                    log.error("删除环境失败: {}", item.getEnvironment().getName(), e);
                }
            }

            // 刷新顶部下拉框
            UiSingletonFactory.getInstance(TopMenuBar.class).getEnvironmentComboBox().reload();

            // 设置当前的变量表格为激活环境
            loadActiveEnvironmentVariables();

            // 显示删除成功消息
            if (deletedCount > 0) {
                NotificationCenter.showSuccess(
                        I18nUtil.getMessage(MessageKeys.ENV_DIALOG_DELETE_SUCCESS, deletedCount)
                );
            }
        }
    }


    /** Duplicates an environment including its independently editable PLM authentication configuration. */
    private void copySelectedEnvironment() {
        EnvironmentItem item = environmentList.getSelectedValue();
        if (item == null) return;
        Environment env = item.getEnvironment();
        try {
            Environment copy = new Environment(env.getName() + " " + I18nUtil.getMessage(MessageKeys.ENV_NAME_COPY_SUFFIX));
            copy.setId("env-" + IdUtil.simpleUUID());
            // 复制变量
            for (String key : env.getVariables().keySet()) {
                copy.addVariable(key, env.getVariable(key));
            }
            copy.setAuth(copyAuth(env.getAuth()));
            EnvironmentService.saveEnvironment(copy);
            EnvironmentItem copyItem = new EnvironmentItem(copy);
            environmentListModel.addElement(copyItem);
            EnvironmentComboBox environmentComboBox = UiSingletonFactory.getInstance(TopMenuBar.class).getEnvironmentComboBox();
            if (environmentComboBox != null) {
                environmentComboBox.addItem(copyItem);
            }
            environmentList.setSelectedValue(copyItem, true);
        } catch (Exception ex) {
            log.error("复制环境失败", ex);
            JOptionPane.showMessageDialog(this,
                    I18nUtil.getMessage(MessageKeys.ENV_DIALOG_COPY_FAIL, ex.getMessage()),
                    I18nUtil.getMessage(MessageKeys.GENERAL_ERROR), JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Copies persisted auth settings without sharing the mutable instance between environments. */
    private PlmAuthConfig copyAuth(PlmAuthConfig auth) {
        return auth == null ? null : JSONUtil.toBean(JSONUtil.parseObj(JSONUtil.toJsonStr(auth)), PlmAuthConfig.class);
    }

    /**
     * 刷新环境列表和 PLM 参数表，保持激活环境高亮和选中。
     */
    public void refreshUI() {
        // 获取当前激活环境id
        Environment active = EnvironmentService.getActiveEnvironment();
        String activeId = active != null ? active.getId() : null;
        // 重新加载环境列表
        environmentListModel.clear();
        java.util.List<Environment> envs = EnvironmentService.getAllEnvironments();
        int selectIdx = -1;
        for (int i = 0; i < envs.size(); i++) {
            Environment env = envs.get(i);
            EnvironmentItem item = new EnvironmentItem(env);
            environmentListModel.addElement(item);
            if (activeId != null && activeId.equals(env.getId())) {
                selectIdx = i;
            }
        }
        // 先取消选中再选中，强制触发 selection 事件，保证表格刷新
        environmentList.clearSelection();
        if (selectIdx >= 0) {
            environmentList.setSelectedIndex(selectIdx);
            environmentList.ensureIndexIsVisible(selectIdx);
        }
        // 强制刷新 PLM 参数表，防止 selection 事件未触发
        EnvironmentItem selectedItem = environmentList.getSelectedValue();
        if (selectedItem != null) {
            loadAuth(selectedItem.getEnvironment());
        } else {
            loadAuth(null);
        }
    }

    // 导出选中环境为Postman格式
    private void exportSelectedEnvironmentAsPostman() {
        EnvironmentItem item = environmentList.getSelectedValue();
        if (item == null) return;
        Environment env = item.getEnvironment();
        SystemFileChooser fileChooser = FileChooserUtil.createSaveFileChooser(
                "environments.export.postman",
                I18nUtil.getMessage(MessageKeys.ENV_DIALOG_EXPORT_POSTMAN_TITLE));
        fileChooser.setSelectedFile(new File(env.getName() + "-postman-env.json"));
        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == SystemFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            try {
                // 只导出当前环境为Postman格式
                String postmanEnvJson = PostmanEnvironmentParser.toPostmanEnvironmentJson(env);
                FileUtil.writeUtf8String(postmanEnvJson, fileToSave);
                NotificationCenter.showSuccess(I18nUtil.getMessage(MessageKeys.ENV_DIALOG_EXPORT_POSTMAN_SUCCESS));
            } catch (Exception ex) {
                log.error("导出Postman环境失败", ex);
                JOptionPane.showMessageDialog(this,
                        I18nUtil.getMessage(MessageKeys.ENV_DIALOG_EXPORT_POSTMAN_FAIL, ex.getMessage()),
                        I18nUtil.getMessage(MessageKeys.GENERAL_ERROR), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * 拖拽后持久化顺序
     */
    private void persistEnvironmentOrder() {
        List<String> idOrder = new ArrayList<>();
        for (int i = 0; i < environmentListModel.size(); i++) {
            idOrder.add(environmentListModel.get(i).getEnvironment().getId());
        }
        EnvironmentService.saveEnvironmentOrder(idOrder);
    }

    /**
     * 拖拽后同步顶部下拉框顺序
     */
    private void syncComboBoxOrder() {
        EnvironmentComboBox comboBox = UiSingletonFactory.getInstance(TopMenuBar.class).getEnvironmentComboBox();
        if (comboBox != null) {
            List<EnvironmentItem> items = new ArrayList<>();
            for (int i = 0; i < environmentListModel.size(); i++) {
                items.add(environmentListModel.get(i));
            }
            comboBox.setModel(new DefaultComboBoxModel<>(items.toArray(new EnvironmentItem[0])));
        }
    }

    /**
     * 切换到指定工作区的环境数据文件，并刷新UI
     */
    public void switchWorkspaceAndRefreshUI(String envFilePath) {
        EnvironmentService.setDataFilePath(envFilePath);
        this.refreshUI();
        // 同步刷新顶部环境下拉框
        UiSingletonFactory.getInstance(TopMenuBar.class).getEnvironmentComboBox().reload();
    }

    /**
     * 转移环境到其他工作区
     */
    private void moveEnvironmentToWorkspace() {
        EnvironmentItem selectedItem = environmentList.getSelectedValue();
        if (selectedItem == null) {
            return;
        }

        Environment environment = selectedItem.getEnvironment();

        // 使用工作区转移辅助类（显示成功消息）
        WorkspaceTransferCoordinator.transferToWorkspace(
                environment.getName(),
                (targetWorkspace, itemName) -> performEnvironmentMove(environment, targetWorkspace)
        );
    }


    /**
     * 执行环境转移操作
     */
    private void performEnvironmentMove(Environment environment, Workspace targetWorkspace) {
        // 1. 深拷贝环境对象
        Environment copiedEnvironment = new Environment(environment.getName());
        copiedEnvironment.setId(environment.getId()); // 保持相同的ID
        // 复制所有变量
        for (String key : environment.getVariables().keySet()) {
            copiedEnvironment.addVariable(key, environment.getVariable(key));
        }
        copiedEnvironment.setAuth(copyAuth(environment.getAuth()));

        // 2. 获取目标工作区的环境文件路径
        String targetEnvPath = SystemUtil.getEnvPathForWorkspace(targetWorkspace);

        // 3. 临时切换到目标工作区的环境服务
        String originalDataFilePath = EnvironmentService.getDataFilePath();
        try {
            // 切换到目标工作区
            EnvironmentService.setDataFilePath(targetEnvPath);

            // 4. 将环境保存到目标工作区
            EnvironmentService.saveEnvironment(copiedEnvironment);

            // 5. 切换回原工作区并删除原环境
            EnvironmentService.setDataFilePath(originalDataFilePath);
            EnvironmentService.deleteEnvironment(environment.getId());

            // 6. 刷新当前面板
            refreshUI();

            // 7. 刷新顶部环境下拉框
            UiSingletonFactory.getInstance(TopMenuBar.class).getEnvironmentComboBox().reload();

            log.info("Successfully moved environment '{}' to workspace '{}'",
                    environment.getName(), targetWorkspace.getName());

        } catch (Exception e) {
            // 如果出现异常，确保恢复原来的数据文件路径
            EnvironmentService.setDataFilePath(originalDataFilePath);
            throw new RuntimeException("转移环境失败: " + e.getMessage(), e);
        }
    }

    /**
     * 过滤表格行，根据搜索框内容筛选显示符合条件的行
     * 搜索范围：Name 列和 Value 列
     */
    private void filterTableRows() {
        String keyword = tableSearchField.getText();
        boolean caseSensitive = tableSearchField.isCaseSensitive();
        boolean wholeWord = tableSearchField.isWholeWord();

        if (keyword == null || keyword.trim().isEmpty()) {
            authTable.setRowSorter(null);
            tableSearchField.setNoResult(false);
            return;
        }

        // 使用 TableRowSorter 进行过滤
        TableRowSorter<TableModel> sorter =
                new TableRowSorter<>(authTableModel);

        // 转换关键字用于搜索
        final String searchKeyword = caseSensitive ? keyword : keyword.toLowerCase();

        // 创建过滤器
        RowFilter<TableModel, Object> rowFilter = new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends TableModel, ?> entry) {
                Object nameObj = entry.getValue(0);
                Object valueObj = entry.getValue(1);
                String name = nameObj != null ? nameObj.toString() : "";
                String value = valueObj != null ? valueObj.toString() : "";
                String searchName = caseSensitive ? name : name.toLowerCase();
                String searchValue = caseSensitive ? value : value.toLowerCase();
                if (wholeWord) {
                    return matchesWholeWord(searchName, searchKeyword) ||
                            matchesWholeWord(searchValue, searchKeyword);
                } else {
                    return searchName.contains(searchKeyword) ||
                            searchValue.contains(searchKeyword);
                }
            }
        };

        sorter.setRowFilter(rowFilter);
        authTable.setRowSorter(sorter);

        // 无可见行时搜索框变红
        boolean noResult = authTable.getRowCount() == 0;
        tableSearchField.setNoResult(noResult);
    }

    /**
     * 判断文本中是否包含整词匹配的关键字
     */
    private boolean matchesWholeWord(String text, String keyword) {
        if (text == null || keyword == null) {
            return false;
        }

        int index = 0;
        while ((index = text.indexOf(keyword, index)) != -1) {
            int start = index;
            int end = index + keyword.length();

            // 检查前一个字符
            if (start > 0) {
                char prevChar = text.charAt(start - 1);
                if (Character.isLetterOrDigit(prevChar) || prevChar == '_') {
                    index++;
                    continue;
                }
            }

            // 检查后一个字符
            if (end < text.length()) {
                char nextChar = text.charAt(end);
                if (Character.isLetterOrDigit(nextChar) || nextChar == '_') {
                    index++;
                    continue;
                }
            }

            return true;
        }

        return false;
    }
}
