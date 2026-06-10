package com.remoteterminal.ui;

import com.remoteterminal.db.ConnectionDAO;
import com.remoteterminal.model.ConnectionInfo;
import com.remoteterminal.ssh.SSHConnection;
import com.remoteterminal.ssh.SSHFileManager;
import com.remoteterminal.ssh.SSHTerminal;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import org.controlsfx.control.StatusBar;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主界面布局 - 菜单栏 + 左侧(会话列表+文件浏览器) + 右侧终端标签页
 */
public class MainLayout extends BorderPane {

    private final ConnectionTree connectionTree;
    private final FileBrowserView fileBrowser;
    private final TabPane terminalTabPane;
    private final StatusBar statusBar;
    private final Label statusLabel;
    private final ConnectionDAO dao = new ConnectionDAO();

    // 当前活动连接
    private final Map<Long, ActiveSession> sessions = new ConcurrentHashMap<>();

    // 标签页 -> 连接信息 映射
    private final Map<TerminalTab, ConnectionInfo> tabToInfo = new HashMap<>();

    // 标签页 -> 有效连接ID 映射（用于文件浏览器绑定）
    private final Map<TerminalTab, Long> tabToConnId = new HashMap<>();

    // 当前活动的 TerminalTab 列表
    private final Set<TerminalTab> activeTerminals = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 同步输入模式
    private boolean syncInputMode = false;
    private CheckMenuItem syncInputMenuItem;

    // 临时连接ID生成器（未保存的连接使用负数ID）
    private static final java.util.concurrent.atomic.AtomicLong tempIdSeq =
            new java.util.concurrent.atomic.AtomicLong(-1);

    public MainLayout() {
        // --- 菜单栏 ---
        setTop(createMenuBar());

        // --- 左侧：会话列表 ---
        connectionTree = new ConnectionTree(this);

        // 会话面板
        VBox sessionPanel = new VBox();
        sessionPanel.getChildren().add(createSessionToolbar());
        Label sessionLabel = new Label("  会话列表");
        sessionLabel.setStyle("-fx-text-fill: #999999; -fx-font-size: 11px; -fx-padding: 4 0 2 0; -fx-background-color: #333333;");
        sessionLabel.setMaxWidth(Double.MAX_VALUE);
        sessionPanel.getChildren().add(sessionLabel);
        sessionPanel.getChildren().add(connectionTree);
        VBox.setVgrow(connectionTree, Priority.ALWAYS);

        // --- 左侧：文件浏览器 ---
        fileBrowser = new FileBrowserView(this);
        fileBrowser.setOnStatusChange(this::setStatus);

        // 文件操作工具栏
        ToolBar fileToolbar = createFileToolbar();

        VBox filePanel = new VBox();
        Label fileLabel = new Label("  文件管理");
        fileLabel.setStyle("-fx-text-fill: #999999; -fx-font-size: 11px; -fx-padding: 4 0 2 0; -fx-background-color: #333333;");
        fileLabel.setMaxWidth(Double.MAX_VALUE);
        filePanel.getChildren().add(fileLabel);
        filePanel.getChildren().add(fileBrowser);
        filePanel.getChildren().add(fileToolbar);
        VBox.setVgrow(fileBrowser, Priority.ALWAYS);

        // 左侧面板：上下分割
        SplitPane leftSplit = new SplitPane();
        leftSplit.setOrientation(Orientation.VERTICAL);
        leftSplit.getItems().addAll(sessionPanel, filePanel);
        leftSplit.setDividerPositions(0.40);

        // --- 右侧面板 ---
        terminalTabPane = new TabPane();
        terminalTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        terminalTabPane.getStyleClass().add("tab-pane");

        Tab welcomeTab = createWelcomeTab();
        terminalTabPane.getTabs().add(welcomeTab);

        // --- 主分割面板 ---
        SplitPane mainSplit = new SplitPane();
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.getItems().addAll(leftSplit, terminalTabPane);
        mainSplit.setDividerPositions(0.28);
        setCenter(mainSplit);

        // --- 状态栏 ---
        statusLabel = new Label("就绪");
        statusBar = new StatusBar();
        statusBar.getLeftItems().add(statusLabel);
        setBottom(statusBar);

        // 监听右侧标签页切换，自动更新文件浏览器绑定
        terminalTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab instanceof TerminalTab tt) {
                Long connId = tabToConnId.get(tt);
                if (connId != null) {
                    fileBrowser.bindConnection(connId);
                }
            }
        });
    }

    // ==================== 菜单栏 ====================

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();
        menuBar.getStyleClass().add("menu-bar");

        menuBar.getMenus().addAll(
                createSessionMenu(),
                createModeMenu(),
                createToolsMenu(),
                createHelpMenu()
        );

        return menuBar;
    }

    private Menu createSessionMenu() {
        Menu sessionMenu = new Menu("会话");

        MenuItem newSession = new MenuItem("新建会话");
        newSession.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN));
        newSession.setOnAction(e -> showNewConnectionDialog());

        MenuItem editSession = new MenuItem("编辑会话");
        editSession.setOnAction(e -> editCurrentSession());

        MenuItem disconnectItem = new MenuItem("断开连接");
        disconnectItem.setOnAction(e -> disconnectCurrentSession());

        MenuItem reconnectItem = new MenuItem("重新连接");
        reconnectItem.setOnAction(e -> reconnectCurrentSession());

        SeparatorMenuItem sep1 = new SeparatorMenuItem();

        MenuItem lockItem = new MenuItem("锁定会话");
        lockItem.setOnAction(e -> toggleLockCurrentSession());

        SeparatorMenuItem sep2 = new SeparatorMenuItem();

        MenuItem closeSession = new MenuItem("关闭会话");
        closeSession.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.CONTROL_DOWN));
        closeSession.setOnAction(e -> closeActiveTab());

        sessionMenu.getItems().addAll(newSession, editSession, disconnectItem, reconnectItem,
                sep1, lockItem, sep2, closeSession);
        return sessionMenu;
    }

    private Menu createModeMenu() {
        Menu modeMenu = new Menu("模式");

        CheckMenuItem remoteModeItem = new CheckMenuItem("远程模式");
        remoteModeItem.setSelected(true);
        remoteModeItem.setOnAction(e -> {
            if (!remoteModeItem.isSelected()) {
                remoteModeItem.setSelected(true);
                setStatus("当前仅支持远程模式");
            }
        });

        syncInputMenuItem = new CheckMenuItem("同步输入");
        syncInputMenuItem.setOnAction(e -> {
            syncInputMode = syncInputMenuItem.isSelected();
            setStatus(syncInputMode ? "同步输入已开启 - 输入将广播到所有会话"
                    : "同步输入已关闭");
        });

        modeMenu.getItems().addAll(remoteModeItem, new SeparatorMenuItem(), syncInputMenuItem);
        return modeMenu;
    }

    private Menu createToolsMenu() {
        Menu toolsMenu = new Menu("工具");

        MenuItem runScript = new MenuItem("脚本运行");
        runScript.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("选择要执行的Shell脚本");
            chooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("Shell脚本", "*.sh", "*.bash", "*.txt")
            );
            java.io.File file = chooser.showOpenDialog(getScene().getWindow());
            if (file == null) return;

            TerminalTab activeTab = getActiveTerminalTab();
            if (activeTab == null || activeTab.getTerminal() == null) {
                showAlert(Alert.AlertType.WARNING, "提示", "请先打开一个会话连接");
                return;
            }
            new Thread(() -> {
                try {
                    String content = java.nio.file.Files.readString(file.toPath());
                    activeTab.getTerminal().send(content + "\n");
                    Platform.runLater(() -> setStatus("脚本已发送: " + file.getName()));
                } catch (Exception ex) {
                    Platform.runLater(() -> setStatus("脚本执行失败: " + ex.getMessage()));
                }
            }, "RunScript").start();
        });

        MenuItem fileTransferMgr = new MenuItem("文件传输管理器");
        fileTransferMgr.setOnAction(e -> {
            showAlert(Alert.AlertType.INFORMATION, "文件传输管理器",
                    "文件传输功能已独立到左侧「文件管理」面板：\n"
                    + "• 连接服务器后自动加载文件列表\n"
                    + "• 双击目录进入，点击导航栏按钮返回上级\n"
                    + "• 右键文件/空白区域查看所有操作\n"
                    + "• 底部工具栏提供常用文件操作");
        });

        toolsMenu.getItems().addAll(runScript, fileTransferMgr);
        return toolsMenu;
    }

    private Menu createHelpMenu() {
        Menu helpMenu = new Menu("帮助");

        MenuItem checkUpdate = new MenuItem("检查更新");
        checkUpdate.setOnAction(e -> {
            showAlert(Alert.AlertType.INFORMATION, "检查更新",
                    "当前版本: v1.1.0\n\n暂无可用更新。");
        });

        MenuItem feedback = new MenuItem("问题反馈");
        feedback.setOnAction(e -> {
            showAlert(Alert.AlertType.INFORMATION, "问题反馈",
                    "如有问题或建议，请通过以下方式反馈：\n\n"
                    + "GitHub Issues: https://github.com/your-repo/issues\n"
                    + "邮件: support@remoteterminal.com");
        });

        SeparatorMenuItem sep = new SeparatorMenuItem();

        MenuItem aboutItem = new MenuItem("关于");
        aboutItem.setOnAction(e -> showAboutDialog());

        helpMenu.getItems().addAll(checkUpdate, feedback, sep, aboutItem);
        return helpMenu;
    }

    // ==================== 当前会话操作 ====================

    private TerminalTab getActiveTerminalTab() {
        Tab selected = terminalTabPane.getSelectionModel().getSelectedItem();
        if (selected instanceof TerminalTab tt) return tt;
        return null;
    }

    private void editCurrentSession() {
        TerminalTab tab = getActiveTerminalTab();
        if (tab == null) {
            setStatus("请先打开一个会话");
            return;
        }
        ConnectionInfo info = tabToInfo.get(tab);
        if (info != null) {
            showEditConnectionDialog(info);
        }
    }

    private void disconnectCurrentSession() {
        TerminalTab tab = getActiveTerminalTab();
        if (tab == null) return;
        ConnectionInfo info = tabToInfo.get(tab);
        if (info != null) {
            disconnectById(info.id());
            removeTerminalTab(tab);
        }
    }

    private void reconnectCurrentSession() {
        TerminalTab tab = getActiveTerminalTab();
        if (tab == null) return;
        ConnectionInfo info = tabToInfo.get(tab);
        if (info != null) {
            disconnectById(info.id());
            removeTerminalTab(tab);
            connectTo(info);
        }
    }

    private void toggleLockCurrentSession() {
        TerminalTab tab = getActiveTerminalTab();
        if (tab == null) {
            setStatus("请先打开一个会话");
            return;
        }

        if (tab.isLocked()) {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("解锁会话");
            dialog.setHeaderText("输入密码以解锁");
            dialog.setContentText("密码:");
            dialog.showAndWait().ifPresent(password -> {
                if (tab.unlock(password)) {
                    setStatus("会话已解锁");
                } else {
                    showAlert(Alert.AlertType.ERROR, "解锁失败", "密码错误！");
                }
            });
        } else {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("锁定会话");
            dialog.setHeaderText("设置锁屏密码");
            dialog.setContentText("密码:");
            dialog.showAndWait().ifPresent(password -> {
                if (!password.isBlank()) {
                    tab.lock(password);
                    setStatus("会话已锁定");
                }
            });
        }
    }

    private void closeActiveTab() {
        Tab tab = terminalTabPane.getSelectionModel().getSelectedItem();
        if (tab != null && tab.isClosable()) {
            terminalTabPane.getTabs().remove(tab);
        }
    }

    void removeTerminalTab(TerminalTab tab) {
        Platform.runLater(() -> {
            activeTerminals.remove(tab);
            tabToInfo.remove(tab);
            tabToConnId.remove(tab);
            terminalTabPane.getTabs().remove(tab);
        });
    }

    // ==================== 同步输入 ====================

    public void broadcastInput(String data) {
        if (!syncInputMode) return;
        for (TerminalTab tt : activeTerminals) {
            if (tt.getTerminal() != null && tt.getTerminal().isRunning() && !tt.isLocked()) {
                tt.getTerminal().send(data);
            }
        }
    }

    // ==================== 工具栏 ====================

    private ToolBar createSessionToolbar() {
        ToolBar toolbar = new ToolBar();
        toolbar.getStyleClass().add("toolbar");

        Button newConnBtn = new Button("＋ 新建连接");
        newConnBtn.setOnAction(e -> showNewConnectionDialog());

        Button refreshBtn = new Button("↻ 刷新列表");
        refreshBtn.setOnAction(e -> connectionTree.refresh());

        toolbar.getItems().addAll(newConnBtn, refreshBtn);
        return toolbar;
    }

    private ToolBar createFileToolbar() {
        ToolBar toolbar = new ToolBar();
        toolbar.getStyleClass().add("toolbar");

        Label fileOpsLabel = new Label("操作: ");
        fileOpsLabel.setStyle("-fx-text-fill: #999999; -fx-font-size: 11px;");

        Button newFolderBtn = new Button("📁+");
        newFolderBtn.setTooltip(new Tooltip("新建文件夹"));
        newFolderBtn.setOnAction(e -> {
            if (fileBrowser.getCurrentConnId() == null) {
                setStatus("请先连接服务器");
                return;
            }
            TextInputDialog dialog = new TextInputDialog("新建文件夹");
            dialog.setTitle("新建文件夹");
            dialog.setHeaderText("在 " + fileBrowser.getCurrentPath() + " 中创建文件夹");
            dialog.setContentText("文件夹名称:");
            dialog.showAndWait().ifPresent(name -> {
                if (!name.isBlank()) {
                    new Thread(() -> {
                        try {
                            SSHFileManager fm = getFileManager(fileBrowser.getCurrentConnId());
                            if (fm == null) return;
                            fm.createDirectory(fileBrowser.getCurrentPath(), name);
                            Platform.runLater(() -> {
                                fileBrowser.refresh();
                                setStatus("文件夹已创建: " + name);
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> setStatus("创建失败: " + ex.getMessage()));
                        }
                    }, "NewFolder").start();
                }
            });
        });

        Button newFileBtn = new Button("📄+");
        newFileBtn.setTooltip(new Tooltip("新建文件"));
        newFileBtn.setOnAction(e -> {
            if (fileBrowser.getCurrentConnId() == null) {
                setStatus("请先连接服务器");
                return;
            }
            TextInputDialog dialog = new TextInputDialog("新建文件.txt");
            dialog.setTitle("新建文件");
            dialog.setHeaderText("在 " + fileBrowser.getCurrentPath() + " 中创建文件");
            dialog.setContentText("文件名称:");
            dialog.showAndWait().ifPresent(name -> {
                if (!name.isBlank()) {
                    new Thread(() -> {
                        try {
                            SSHFileManager fm = getFileManager(fileBrowser.getCurrentConnId());
                            if (fm == null) return;
                            fm.createFile(fileBrowser.getCurrentPath(), name);
                            Platform.runLater(() -> {
                                fileBrowser.refresh();
                                setStatus("文件已创建: " + name);
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> setStatus("创建失败: " + ex.getMessage()));
                        }
                    }, "NewFile").start();
                }
            });
        });

        toolbar.getItems().addAll(fileOpsLabel, newFolderBtn, newFileBtn);
        return toolbar;
    }

    private Tab createWelcomeTab() {
        Tab welcomeTab = new Tab();
        welcomeTab.setClosable(false);

        // 标签头：欢迎 + 关闭按钮
        Label tabTitle = new Label("欢迎");
        tabTitle.setStyle("-fx-text-fill: #cccccc;");
        Button closeBtn = new Button("✕");
        String closeBtnBase = "-fx-background-color: transparent; -fx-text-fill: #999999; -fx-font-size: 13px; -fx-padding: 0 4;";
        String closeBtnHover = "-fx-background-color: #484848; -fx-text-fill: #ffffff; -fx-font-size: 13px; -fx-padding: 0 4;";
        closeBtn.setStyle(closeBtnBase);
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(closeBtnHover));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(closeBtnBase));
        closeBtn.setOnAction(e -> terminalTabPane.getTabs().remove(welcomeTab));
        HBox tabHeader = new HBox(4, tabTitle, closeBtn);
        tabHeader.setAlignment(Pos.CENTER_LEFT);
        welcomeTab.setGraphic(tabHeader);

        Label welcomeLabel = new Label("""
                欢迎使用 RemoteTerminal 远程终端工具
                                
                🚀 快速开始:
                1. 点击左侧 [+ 新建连接] 创建SSH连接
                2. 双击会话列表中的服务器即可建立连接
                3. 连接后左侧「文件管理」自动加载远程文件系统
                                
                ⌨️ 终端快捷键:
                - Ctrl+Shift+C / Ctrl+Insert: 复制
                - Ctrl+Shift+V / Shift+Insert: 粘贴
                - Ctrl+L: 清屏
                                
                📁 文件管理:
                - 双击目录进入，使用导航按钮返回上级
                - 右键文件/空白区域查看所有操作
                - 不限目录深度，可浏览任意层级
                """);
        welcomeLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 13px; -fx-padding: 30px; -fx-line-spacing: 6px;");
        welcomeTab.setContent(welcomeLabel);
        return welcomeTab;
    }

    // ==================== 连接管理 ====================

    public void showNewConnectionDialog() {
        ConnectionDialog dialog = new ConnectionDialog(null);
        dialog.showAndWait().ifPresent(result -> {
            if (result.connection() != null) {
                // 仅已保存的连接到左侧会话列表
                if (result.connection().isPersisted()) {
                    connectionTree.addConnection(result.connection());
                }
                if (result.connectNow()) {
                    connectTo(result.connection());
                }
            }
        });
    }

    public void showEditConnectionDialog(ConnectionInfo info) {
        ConnectionDialog dialog = new ConnectionDialog(info);
        dialog.showAndWait().ifPresent(result -> {
            if (result.connection() != null) {
                connectionTree.updateConnection(result.connection());
                if (result.connectNow()) {
                    connectTo(result.connection());
                }
            }
        });
    }

    public void connectTo(ConnectionInfo info) {
        setStatus("正在连接 " + info.name() + " ...");

        // 未保存的连接使用临时 ID
        final long effectiveId = info.isPersisted() ? info.id() : tempIdSeq.getAndDecrement();

        // 硬超时标记：防止 SSH 底层忽略超时导致界面永远无反馈
        final java.util.concurrent.atomic.AtomicBoolean completed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean timeoutFired =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        new Thread(() -> {
            try {
                SSHConnection ssh = new SSHConnection(info);
                ssh.connect();

                // 连接成功，取消超时
                if (!completed.compareAndSet(false, true)) return;

                SSHTerminal terminal = new SSHTerminal(ssh);
                SSHFileManager fileManager = new SSHFileManager(ssh);

                terminal.setOnDisconnected(msg -> Platform.runLater(() ->
                        setStatus(info.name() + " - 已断开")));

                ActiveSession session = new ActiveSession(ssh, terminal, fileManager);
                sessions.put(effectiveId, session);

                Platform.runLater(() -> {
                    TerminalTab terminalTab = new TerminalTab(info.name(), terminal, ssh, info);

                    // 同步输入
                    terminalTab.setOnInput(data -> {
                        if (syncInputMode) broadcastInput(data);
                    });

                    // 关闭标签页时
                    terminalTab.setOnClose(() -> {
                        session.close();
                        sessions.remove(effectiveId);
                        connectionTree.setDisconnected(effectiveId);
                        activeTerminals.remove(terminalTab);
                        tabToInfo.remove(terminalTab);
                        tabToConnId.remove(terminalTab);
                        // 如果这个连接是对应文件管理器的连接，就解绑
                        if (fileBrowser.getCurrentConnId() != null
                                && fileBrowser.getCurrentConnId().equals(effectiveId)) {
                            fileBrowser.unbindConnection();
                            // 尝试绑定到另一个活跃会话
                            for (Map.Entry<Long, ActiveSession> entry : sessions.entrySet()) {
                                fileBrowser.bindConnection(entry.getKey());
                                break;
                            }
                        }
                    });

                    // 标签页右键菜单回调
                    terminalTab.setOnReconnect(() -> {
                        disconnectById(effectiveId);
                        removeTerminalTab(terminalTab);
                        connectTo(info);
                    });
                    terminalTab.setOnDisconnectAction(() -> {
                        disconnectById(effectiveId);
                        removeTerminalTab(terminalTab);
                    });
                    terminalTab.setOnEditConnection(() -> {
                        ConnectionInfo current = tabToInfo.get(terminalTab);
                        if (current != null) showEditConnectionDialog(current);
                    });
                    terminalTab.setOnDeleteConnection(() -> {
                        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                        confirm.setTitle("确认删除");
                        confirm.setHeaderText("删除连接: " + info.name());
                        confirm.setContentText("这将断开连接并从列表中删除，确定吗？");
                        confirm.showAndWait().ifPresent(r -> {
                            if (r == ButtonType.OK) {
                                disconnectById(effectiveId);
                                removeTerminalTab(terminalTab);
                                if (info.isPersisted()) {
                                    try { dao.delete(info.id()); } catch (Exception ex) {}
                                }
                                connectionTree.refresh();
                            }
                        });
                    });

                    tabToInfo.put(terminalTab, info);
                    tabToConnId.put(terminalTab, effectiveId);
                    activeTerminals.add(terminalTab);

                    terminalTabPane.getTabs().add(terminalTab);
                    terminalTabPane.getSelectionModel().select(terminalTab);

                    try {
                        terminal.start();
                    } catch (Exception e) {
                        terminalTab.appendError("启动Shell失败: " + e.getMessage());
                    }

                    // 仅已保存的连接到左侧列表标记为已连接
                    if (info.isPersisted()) {
                        connectionTree.setConnected(info);
                    }

                    // 自动绑定文件浏览器到新连接
                    fileBrowser.bindConnection(effectiveId);

                    setStatus(info.name() + " - 已连接");
                });

            } catch (Exception e) {
                if (!completed.compareAndSet(false, true)) return;
                Platform.runLater(() -> {
                    setStatus("连接失败: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText("无法连接到 " + info.name());
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "Connect-" + info.name()).start();

        // 看门狗：6 秒内无论底层什么情况，必定给用户反馈
        new Thread(() -> {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                return;
            }
            if (timeoutFired.compareAndSet(false, true) && !completed.get()) {
                Platform.runLater(() -> {
                    setStatus("连接超时: " + info.name());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接超时");
                    alert.setHeaderText("无法连接到 " + info.name());
                    alert.setContentText("连接超过6秒未响应，请检查网络或服务器状态。");
                    alert.showAndWait();
                });
            }
        }, "Watchdog-" + info.name()).start();
    }

    public void disconnectById(long connId) {
        ActiveSession session = sessions.remove(connId);
        if (session != null) {
            session.close();
        }
        connectionTree.setDisconnected(connId);
        // 如果文件浏览器正在浏览这个连接的文件，解绑
        if (fileBrowser.getCurrentConnId() != null
                && fileBrowser.getCurrentConnId().equals(connId)) {
            fileBrowser.unbindConnection();
        }
        setStatus("连接已断开");
    }

    // ==================== 公共访问方法 ====================

    public SSHFileManager getFileManager(long connId) {
        ActiveSession session = sessions.get(connId);
        return session != null ? session.fileManager() : null;
    }

    public SSHConnection getSSHConnection(long connId) {
        ActiveSession session = sessions.get(connId);
        return session != null ? session.ssh() : null;
    }

    public ConnectionTree getConnectionTree() {
        return connectionTree;
    }

    public void setStatus(String text) {
        Platform.runLater(() -> statusLabel.setText(text));
    }

    // ==================== 对话框 ====================

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("关于 RemoteTerminal");
        alert.setHeaderText("RemoteTerminal v1.1.0");
        alert.setContentText("""
                基于 JavaFX + JSch + xterm.js 的桌面远程终端工具
                                
                功能特性:
                - SSH 远程终端 (基于 xterm.js 模拟)
                - SFTP 文件浏览、上传、下载（独立文件管理面板）
                - 多标签页同时管理多个会话
                - 连接信息持久化存储 (H2 数据库)
                - 自动重连已保存的连接
                                
                技术栈:
                - Java 17+ / JavaFX
                - ControlsFX (增强UI组件)
                - JSch (SSH/SFTP协议)
                - HikariCP + H2 Database (连接池与持久化)
                - xterm.js (终端模拟)
                """);
        alert.showAndWait();
    }

    // ==================== 生命周期 ====================

    public void shutdown() {
        for (ActiveSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        activeTerminals.clear();
        tabToInfo.clear();
        tabToConnId.clear();
    }

    // ==================== 内部类 ====================

    private record ActiveSession(
            SSHConnection ssh,
            SSHTerminal terminal,
            SSHFileManager fileManager
    ) {
        void close() {
            try { terminal.close(); } catch (Exception ignored) {}
            try { ssh.close(); } catch (Exception ignored) {}
        }
    }
}
