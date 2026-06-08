package com.remoteterminal.ui;

import com.remoteterminal.db.ConnectionDAO;
import com.remoteterminal.model.ConnectionInfo;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 左侧连接列表 - 仅管理 SSH 连接会话
 * 文件浏览功能已移到 FileBrowserView
 */
public class ConnectionTree extends TreeView<String> {

    private final MainLayout mainLayout;
    private final ConnectionDAO dao = new ConnectionDAO();
    private final TreeItem<String> rootItem;

    // 连接节点映射: id -> TreeItem
    private final Map<Long, TreeItem<String>> connectionNodes = new ConcurrentHashMap<>();

    // 节点元数据: TreeItem -> 元数据
    private final Map<TreeItem<String>, Long> nodeConnectionMap = new ConcurrentHashMap<>();

    // 图标
    private static final String ICON_DISCONNECTED = "\u26AA";
    private static final String ICON_CONNECTED = "\uD83D\uDFE2";
    private static final String ICON_FOLDER = "\uD83D\uDCC1";

    // 当前正在显示的右键菜单
    private ContextMenu currentContextMenu;

    public ConnectionTree(MainLayout mainLayout) {
        this.mainLayout = mainLayout;
        setFixedCellSize(26);
        setCellFactory(tv -> new ConnectionTreeCell());

        rootItem = new TreeItem<>(ICON_FOLDER + " 会话列表");
        rootItem.setExpanded(true);
        setRoot(rootItem);
        setShowRoot(true);

        // 双击连接节点 → 有密码则直连，无密码则弹框输入
        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                TreeItem<String> selected = getSelectionModel().getSelectedItem();
                if (selected == null) {
                    hideCurrentContextMenu();
                }
                if (e.getClickCount() == 2) {
                    TreeItem<String> item = getSelectionModel().getSelectedItem();
                    if (item != null) {
                        Long connId = findConnectionId(item);
                        if (connId != null) {
                            try {
                                dao.findById(connId).ifPresent(info -> {
                                    if (info.hasPassword()) {
                                        // 已存密码，直接登录
                                        mainLayout.connectTo(info);
                                    } else {
                                        // 未存密码，弹出密码输入框
                                        showPasswordPrompt(info);
                                    }
                                });
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        });

        // 右键菜单空白区域自动隐藏
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                javafx.scene.Node target = e.getPickResult().getIntersectedNode();
                boolean clickedOnCell = false;
                javafx.scene.Node current = target;
                while (current != null) {
                    if (current instanceof TreeCell) {
                        clickedOnCell = true;
                        break;
                    }
                    current = current.getParent();
                }
                if (!clickedOnCell) {
                    hideCurrentContextMenu();
                }
            }
        });

        loadConnections();
    }

    // ==================== 连接管理 ====================

    private void loadConnections() {
        new Thread(() -> {
            try {
                List<ConnectionInfo> connections = dao.findAll();
                Platform.runLater(() -> {
                    for (ConnectionInfo info : connections) {
                        addConnectionNode(info);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> mainLayout.setStatus("加载连接列表失败: " + e.getMessage()));
            }
        }, "LoadConnections").start();
    }

    public void addConnection(ConnectionInfo info) {
        addConnectionNode(info);
        TreeItem<String> node = connectionNodes.get(info.id());
        if (node != null) {
            getSelectionModel().select(node);
        }
    }

    private void addConnectionNode(ConnectionInfo info) {
        String label = ICON_DISCONNECTED + " " + info.name();
        TreeItem<String> node = new TreeItem<>(label);
        nodeConnectionMap.put(node, info.id());
        node.setExpanded(false);
        rootItem.getChildren().add(node);
        connectionNodes.put(info.id(), node);
    }

    public void updateConnection(ConnectionInfo info) {
        TreeItem<String> node = connectionNodes.get(info.id());
        if (node != null) {
            node.setValue(ICON_DISCONNECTED + " " + info.name());
        }
    }

    /** 标记为已连接 */
    public void setConnected(ConnectionInfo info) {
        TreeItem<String> node = connectionNodes.get(info.id());
        if (node == null) return;

        Platform.runLater(() -> {
            String label = ICON_CONNECTED + " " + info.name()
                    + " [" + info.username() + "@" + info.host() + "]";
            node.setValue(label);
        });
    }

    /** 标记为已断开 - 修复：保留连接节点元数据，不删除 */
    public void setDisconnected(long connId) {
        TreeItem<String> node = connectionNodes.get(connId);
        if (node != null) {
            Platform.runLater(() -> {
                String currentVal = node.getValue();
                if (currentVal != null && currentVal.startsWith(ICON_CONNECTED)) {
                    node.setValue(currentVal.replace(ICON_CONNECTED, ICON_DISCONNECTED));
                }
                // 仅清除文件子节点，保留连接节点本身的元数据
                node.getChildren().clear();
            });
        }
    }

    // ==================== 查找 / 访问器 ====================

    public Long findConnectionId(TreeItem<String> item) {
        if (item == null) return null;
        TreeItem<String> current = item;
        while (current != null && current != rootItem) {
            Long cid = nodeConnectionMap.get(current);
            if (cid != null) return cid;
            current = current.getParent();
        }
        return null;
    }

    public Long getSelectedConnectionId() {
        return findConnectionId(getSelectionModel().getSelectedItem());
    }

    // ==================== 刷新 ====================

    /** 完全刷新连接列表 — DB 查询在子线程执行，UI 更新回到主线程 */
    public void refresh() {
        new Thread(() -> {
            List<ConnectionInfo> connections;
            try {
                connections = dao.findAll();
            } catch (Exception e) {
                Platform.runLater(() -> mainLayout.setStatus("刷新失败: " + e.getMessage()));
                return;
            }
            // 所有 UI 操作回到主线程
            Platform.runLater(() -> {
                rootItem.getChildren().clear();
                nodeConnectionMap.clear();
                for (ConnectionInfo info : connections) {
                    TreeItem<String> existing = connectionNodes.get(info.id());
                    if (existing != null) {
                        String currentLabel = existing.getValue();
                        if (currentLabel != null) {
                            String prefix = currentLabel.startsWith(ICON_CONNECTED) ? ICON_CONNECTED : ICON_DISCONNECTED;
                            existing.setValue(prefix + " " + info.name());
                        }
                        rootItem.getChildren().add(existing);
                        nodeConnectionMap.put(existing, info.id());
                    } else {
                        addConnectionNode(info);
                    }
                }
            });
        }, "RefreshConnections").start();
    }

    // ==================== 密码提示 ====================

    /** 未存密码的连接双击时弹出密码输入框 */
    private void showPasswordPrompt(ConnectionInfo info) {
        javafx.scene.control.PasswordField pwdField = new javafx.scene.control.PasswordField();
        javafx.scene.control.Dialog<String> pwdDialog = new javafx.scene.control.Dialog<>();
        pwdDialog.setTitle("输入密码");
        pwdDialog.setHeaderText("连接 " + info.name() + " (" + info.username() + "@" + info.host() + ")");
        pwdDialog.getDialogPane().setContent(new javafx.scene.layout.VBox(8,
                new javafx.scene.control.Label("请输入密码:"), pwdField));
        pwdDialog.getDialogPane().getButtonTypes().addAll(
                new javafx.scene.control.ButtonType("连接", javafx.scene.control.ButtonBar.ButtonData.OK_DONE),
                javafx.scene.control.ButtonType.CANCEL);
        pwdDialog.setResultConverter(bt -> {
            if (bt.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE) return null;
            String pwd = pwdField.getText();
            return (pwd != null && !pwd.isEmpty()) ? pwd : null;
        });
        pwdDialog.showAndWait().ifPresent(password -> {
            ConnectionInfo withPwd = new ConnectionInfo(
                    info.id(), info.name(), info.host(), info.port(),
                    info.username(), password, info.savePassword(), info.sortOrder());
            mainLayout.connectTo(withPwd);
        });
    }

    // ==================== 右键菜单 ====================

    private void hideCurrentContextMenu() {
        if (currentContextMenu != null && currentContextMenu.isShowing()) {
            currentContextMenu.hide();
            currentContextMenu = null;
        }
    }

    private class ConnectionTreeCell extends TreeCell<String> {

        private ContextMenu cachedMenu;

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                cachedMenu = null;
            } else {
                setText(item);
                if (cachedMenu != null) {
                    setContextMenu(cachedMenu);
                } else {
                    ContextMenu menu = buildMenu();
                    if (menu != null) {
                        cachedMenu = menu;
                        setContextMenu(menu);
                    }
                }
            }
        }

        private ContextMenu buildMenu() {
            TreeItem<String> treeItem = getTreeItem();
            if (treeItem == null || treeItem == rootItem) return null;

            Long connId = nodeConnectionMap.get(treeItem);
            if (connId == null) return null;

            ContextMenu menu = new ContextMenu();
            menu.setOnShowing(e -> currentContextMenu = menu);
            menu.setOnHidden(e -> {
                if (currentContextMenu == menu) currentContextMenu = null;
            });

            MenuItem connectItem = new MenuItem("连接");
            connectItem.setOnAction(e -> {
                try { dao.findById(connId).ifPresent(mainLayout::connectTo); }
                catch (Exception ex) { mainLayout.setStatus("获取连接信息失败"); }
            });

            MenuItem disconnectItem = new MenuItem("断开");
            disconnectItem.setOnAction(e -> mainLayout.disconnectById(connId));

            MenuItem editItem = new MenuItem("编辑");
            editItem.setOnAction(e -> {
                try { dao.findById(connId).ifPresent(mainLayout::showEditConnectionDialog); }
                catch (Exception ex) {}
            });

            MenuItem deleteItem = new MenuItem("删除");
            deleteItem.setOnAction(e -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("确认删除");
                confirm.setHeaderText("删除此连接");
                confirm.setContentText("此操作不可撤销，确定删除吗？");
                confirm.showAndWait().ifPresent(r -> {
                    if (r == ButtonType.OK) {
                        try {
                            dao.delete(connId);
                            mainLayout.disconnectById(connId);
                            rootItem.getChildren().remove(treeItem);
                            connectionNodes.remove(connId);
                            nodeConnectionMap.remove(treeItem);
                        } catch (Exception ex) {
                            mainLayout.setStatus("删除失败: " + ex.getMessage());
                        }
                    }
                });
            });

            menu.getItems().addAll(connectItem, disconnectItem, new SeparatorMenuItem(),
                    editItem, deleteItem);

            return menu;
        }
    }
}
