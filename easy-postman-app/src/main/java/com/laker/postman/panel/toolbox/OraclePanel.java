package com.laker.postman.panel.toolbox;

import com.formdev.flatlaf.FlatClientProperties;
import com.laker.postman.common.component.FallbackAwareRSyntaxTextArea;
import com.laker.postman.common.component.SearchableTextArea;
import com.laker.postman.common.constants.ConfigPathConstants;
import com.laker.postman.util.EditorThemeUtil;
import com.laker.postman.util.I18nUtil;
import com.laker.postman.util.MessageKeys;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** A small Oracle JDBC client for testing a connection and viewing bounded query results. */
public class OraclePanel extends JPanel {
    private static final int MAX_ROWS = 500;
    private static final int MAX_CELL_CHARS = 2_000;
    private static final Path SETTINGS_PATH = Path.of(ConfigPathConstants.ORACLE_CONNECTION);

    private final JTextField urlField = new JTextField();
    private final JTextField userField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final RSyntaxTextArea sqlArea = new FallbackAwareRSyntaxTextArea(12, 40);
    private final JTable resultTable = new JTable();
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton testButton = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_TEST));
    private final JButton runButton = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_RUN));

    /** Builds the Oracle page and restores the one locally saved connection. */
    public OraclePanel() {
        ToolboxWorkbench.applyRoot(this);
        add(buildConnectionForm(), BorderLayout.NORTH);

        sqlArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        sqlArea.setText("SELECT * FROM DUAL");
        EditorThemeUtil.loadTheme(sqlArea);
        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultTable.setModel(new DefaultTableModel());
        add(ToolboxWorkbench.editorSplit(
                ToolboxWorkbench.editorSection(
                        I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_SQL),
                        new SearchableTextArea(sqlArea)),
                ToolboxWorkbench.editorSection(
                        I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_RESULT),
                        new JScrollPane(resultTable)),
                300), BorderLayout.CENTER);
        add(ToolboxWorkbench.statusBar(statusLabel), BorderLayout.SOUTH);

        loadConnection();
        testButton.addActionListener(e -> execute(null));
        runButton.addActionListener(e -> execute(sqlArea.getText()));
    }

    /** Creates the compact form; the JDBC URL accepts service-name and SID forms. */
    private JPanel buildConnectionForm() {
        JPanel form = new JPanel(new MigLayout("insets 0 0 8 0,fillx,novisualpadding", "[][grow,fill][][grow,fill]", "[]8[]8[]"));
        form.setOpaque(false);
        urlField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                "jdbc:oracle:thin:@//host:1521/serviceName");
        form.add(new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_URL)));
        form.add(urlField, "span 3,growx,wrap");
        form.add(new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_USER)));
        form.add(userField, "growx");
        form.add(new JLabel(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_PASSWORD)));
        form.add(passwordField, "growx,wrap");
        JButton saveButton = new JButton(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_SAVE));
        saveButton.addActionListener(e -> saveConnection());
        form.add(saveButton);
        form.add(testButton);
        form.add(runButton, "wrap");
        return form;
    }

    /** Loads this user's saved Oracle connection; the password is stored in plain text locally. */
    private void loadConnection() {
        if (!Files.isRegularFile(SETTINGS_PATH)) {
            return;
        }
        Properties settings = new Properties();
        try (Reader reader = Files.newBufferedReader(SETTINGS_PATH, StandardCharsets.UTF_8)) {
            settings.load(reader);
            urlField.setText(settings.getProperty("url", ""));
            userField.setText(settings.getProperty("user", ""));
            passwordField.setText(settings.getProperty("password", ""));
        } catch (IOException e) {
            statusLabel.setText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_FAILED, e.getMessage()));
        }
    }

    /** Saves the single connection so reopening the app does not require re-entry. */
    private void saveConnection() {
        if (!hasConnectionDetails()) {
            return;
        }
        Properties settings = new Properties();
        settings.setProperty("url", urlField.getText().trim());
        settings.setProperty("user", userField.getText().trim());
        settings.setProperty("password", new String(passwordField.getPassword()));
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(SETTINGS_PATH, StandardCharsets.UTF_8)) {
                settings.store(writer, "EasyPostman Oracle connection");
            }
            statusLabel.setText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_SAVED));
        } catch (IOException e) {
            statusLabel.setText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_FAILED, e.getMessage()));
        }
    }

    /** Checks fields needed by both save and connect without changing saved settings. */
    private boolean hasConnectionDetails() {
        if (!urlField.getText().trim().startsWith("jdbc:oracle:thin:@")
                || userField.getText().trim().isEmpty()) {
            statusLabel.setText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_REQUIRED));
            return false;
        }
        return true;
    }

    /** Runs a connection check or one SELECT/WITH statement off the Swing event thread. */
    private void execute(String enteredSql) {
        if (!hasConnectionDetails()) {
            return;
        }
        String sql = enteredSql == null ? null : enteredSql.trim();
        if (sql != null) {
            String firstWord = sql.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
            if (!"SELECT".equals(firstWord) && !"WITH".equals(firstWord)) {
                statusLabel.setText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_SELECT_ONLY));
                return;
            }
            if (sql.endsWith(";")) {
                sql = sql.substring(0, sql.length() - 1).trim();
            }
        }

        String url = urlField.getText().trim();
        String user = userField.getText().trim();
        String password = new String(passwordField.getPassword());
        String query = sql;
        testButton.setEnabled(false);
        runButton.setEnabled(false);
        new SwingWorker<QueryResult, Void>() {
            @Override
            protected QueryResult doInBackground() throws Exception {
                Properties credentials = new Properties();
                credentials.setProperty("user", user);
                credentials.setProperty("password", password);
                credentials.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
                credentials.setProperty("oracle.jdbc.ReadTimeout", "30000");
                try (Connection connection = DriverManager.getConnection(url, credentials)) {
                    if (query == null) {
                        if (!connection.isValid(10)) {
                            throw new SQLException("Connection validation failed");
                        }
                        return new QueryResult(List.of(), List.of());
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(30);
                        statement.setMaxRows(MAX_ROWS);
                        try (ResultSet result = statement.executeQuery(query)) {
                            return readRows(result);
                        }
                    }
                }
            }

            @Override
            protected void done() {
                testButton.setEnabled(true);
                runButton.setEnabled(true);
                try {
                    QueryResult result = get();
                    if (query == null) {
                        statusLabel.setText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_CONNECTED));
                    } else {
                        showRows(result);
                        statusLabel.setText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_ROWS,
                                String.valueOf(result.rows().size())));
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String message = String.valueOf(cause.getMessage());
                    statusLabel.setText(I18nUtil.getMessage(MessageKeys.TOOLBOX_ORACLE_FAILED,
                            password.isEmpty() ? message : message.replace(password, "***")));
                }
            }
        }.execute();
    }

    /** Reads at most 500 rows and truncates oversized cells before they reach the table. */
    private QueryResult readRows(ResultSet result) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            columns.add(metadata.getColumnLabel(i));
        }
        List<Object[]> rows = new ArrayList<>();
        while (rows.size() < MAX_ROWS && result.next()) {
            Object[] row = new Object[columns.size()];
            for (int i = 0; i < row.length; i++) {
                String value = result.getString(i + 1);
                row[i] = value == null || value.length() <= MAX_CELL_CHARS
                        ? value : value.substring(0, MAX_CELL_CHARS) + "…";
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows);
    }

    /** Replaces the result table only after the background query has completed. */
    private void showRows(QueryResult result) {
        DefaultTableModel model = new DefaultTableModel(result.columns().toArray(), 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (Object[] row : result.rows()) {
            model.addRow(row);
        }
        resultTable.setModel(model);
    }

    private record QueryResult(List<String> columns, List<Object[]> rows) {
    }
}
