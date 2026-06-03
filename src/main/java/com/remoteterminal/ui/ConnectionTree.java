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

        // 双击连接节点 → 连接
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
                                dao.findById(connId).ifPresent(mainLayout::connectTo);
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

    /** 完全刷新连接列表 */
    public void refresh() {
        Platform.runLater(() -> {
            // 保留现有的 connectionNodes，只重建UI
            rootItem.getChildren().clear();
            nodeConnectionMap.clear();
            // 重新加载所有连接
            try {
                List<ConnectionInfo> connections = dao.findAll();
                // 保留现有 connectionNodes 中的节点信息
                for (ConnectionInfo info : connections) {
                    // 检查是否已有节点在 connectionNodes 中
                    TreeItem<String> existing = connectionNodes.get(info.id());
                    if (existing != null) {
                        // 重建：更新显示文本（可能有重命名）
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
            } catch (Exception e) {
                Platform.runLater(() -> mainLayout.setStatus("刷新失败: " + e.getMessage()));
            }
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
