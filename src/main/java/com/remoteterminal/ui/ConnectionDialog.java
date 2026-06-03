package com.remoteterminal.ui;

import com.remoteterminal.db.ConnectionDAO;
import com.remoteterminal.model.ConnectionInfo;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

/**
 * 新建/编辑连接对话框
 */
public class ConnectionDialog extends Dialog<ConnectionDialog.DialogResult> {

    private final TextField nameField = new TextField();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField("22");
    private final TextField usernameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final CheckBox saveConnection = new CheckBox("保存连接（下次打开可直接连接）");
    private final CheckBox autoConnect = new CheckBox("启动时自动连接");
    private final ButtonType connectBtnType = new ButtonType("保存并连接", ButtonBar.ButtonData.OK_DONE);
    private final ButtonType connectOnlyBtnType = new ButtonType("直接连接", ButtonBar.ButtonData.OK_DONE);
    private final ButtonType saveBtnType = new ButtonType("仅保存", ButtonBar.ButtonData.APPLY);
    private final ButtonType cancelBtnType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);

    private final ConnectionInfo editingInfo;
    private boolean connectNow = false;

    public ConnectionDialog(ConnectionInfo existing) {
        this.editingInfo = existing;

        setTitle(existing == null ? "新建连接" : "编辑连接");
        setHeaderText(existing == null ? "输入远程服务器连接信息" : "修改连接信息");

        // 预填现有数据
        if (existing != null) {
            nameField.setText(existing.name() != null ? existing.name() : "");
            hostField.setText(existing.host() != null ? existing.host() : "");
            portField.setText(String.valueOf(existing.port()));
            usernameField.setText(existing.username() != null ? existing.username() : "");
            passwordField.setText(existing.password() != null ? existing.password() : "");
            saveConnection.setSelected(true);
            autoConnect.setSelected(existing.autoConnect());
        }

        GridPane grid = createFormGrid();
        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(connectBtnType, connectOnlyBtnType, saveBtnType, cancelBtnType);

        // autoConnect 依赖 saveConnection
        autoConnect.disableProperty().bind(saveConnection.selectedProperty().not());
        saveConnection.selectedProperty().addListener((obs, old, val) -> {
            if (!val) autoConnect.setSelected(false);
        });

        // 结果转换 - 在此进行验证和保存
        setResultConverter(btnType -> {
            if (btnType == cancelBtnType) return null;
            connectNow = (btnType == connectBtnType || btnType == connectOnlyBtnType);
            boolean shouldSave = (btnType == connectBtnType || btnType == saveBtnType) && saveConnection.isSelected();

            // 验证
            String error = validateInput();
            if (error != null) {
                javafx.application.Platform.runLater(() -> showError(error));
                return null;
            }

            ConnectionInfo info = buildConnection();
            if (shouldSave) {
                // 保存到数据库
                try {
                    ConnectionDAO dao = new ConnectionDAO();
                    if (editingInfo != null && editingInfo.isPersisted()) {
                        dao.update(info);
                    } else {
                        info = dao.save(info);
                    }
                    return new DialogResult(info, connectNow);
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("保存失败");
                        alert.setContentText("保存连接信息失败: " + ex.getMessage());
                        alert.showAndWait();
                    });
                    return null;
                }
            } else {
                // 不保存，直接返回（id=0 表示临时连接）
                return new DialogResult(info, connectNow);
            }
        });

        // 初始聚焦
        nameField.requestFocus();
    }

    private GridPane createFormGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(20, 20, 10, 20));

        grid.add(new Label("连接名称:"), 0, 0);
        grid.add(nameField, 1, 0);
        nameField.setPromptText("例如: 我的服务器");
        nameField.setPrefWidth(260);

        grid.add(new Label("主机地址:"), 0, 1);
        grid.add(hostField, 1, 1);
        hostField.setPromptText("例如: 192.168.1.100");

        grid.add(new Label("端口:"), 0, 2);
        grid.add(portField, 1, 2);
        portField.setPrefWidth(80);

        grid.add(new Label("用户名:"), 0, 3);
        grid.add(usernameField, 1, 3);
        usernameField.setPromptText("例如: root");

        grid.add(new Label("密码:"), 0, 4);
        grid.add(passwordField, 1, 4);

        grid.add(saveConnection, 1, 5);
        grid.add(autoConnect, 1, 6);

        return grid;
    }

    private String validateInput() {
        if (nameField.getText().trim().isEmpty()) {
            nameField.requestFocus();
            return "请输入连接名称";
        }
        if (hostField.getText().trim().isEmpty()) {
            hostField.requestFocus();
            return "请输入主机地址";
        }
        try {
            int port = Integer.parseInt(portField.getText().trim());
            if (port <= 0 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            portField.requestFocus();
            return "端口号无效 (1-65535)";
        }
        if (usernameField.getText().trim().isEmpty()) {
            usernameField.requestFocus();
            return "请输入用户名";
        }
        return null;
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("输入错误");
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private ConnectionInfo buildConnection() {
        String name = nameField.getText().trim();
        String host = hostField.getText().trim();
        int port = Integer.parseInt(portField.getText().trim());
        String user = usernameField.getText().trim();
        String pass = passwordField.getText();
        boolean auto = saveConnection.isSelected() && autoConnect.isSelected();

        if (editingInfo != null && editingInfo.id() != null) {
            return new ConnectionInfo(editingInfo.id(), name, host, port, user, pass, auto, 0);
        } else {
            return new ConnectionInfo(name, host, port, user, pass, auto);
        }
    }

    /** 对话框返回结果 */
    public record DialogResult(ConnectionInfo connection, boolean connectNow) {}
}
