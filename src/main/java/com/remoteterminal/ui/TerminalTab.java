package com.remoteterminal.ui;

import com.remoteterminal.model.ConnectionInfo;
import com.remoteterminal.ssh.SSHConnection;
import com.remoteterminal.ssh.SSHTerminal;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 终端标签页 - 内嵌WebView运行xterm.js
 */
public class TerminalTab extends Tab {

    private final SSHTerminal terminal;
    private final String connectionName;
    private final ConnectionInfo connectionInfo;
    private final TerminalBridge bridge;
    private WebView webView;
    private WebEngine webEngine;
    private Runnable onClose;

    // 终端右键菜单引用（用于外部点击关闭）
    private ContextMenu terminalCtxMenu;

    // 标签页右键菜单回调
    private Runnable onReconnect;
    private Runnable onDisconnectAction;
    private Runnable onDeleteConnection;
    private Runnable onEditConnection;

    // 输入监听（用于同步输入广播）
    private java.util.function.Consumer<String> inputListener;

    // 终端搜索相关
    private String lastSearchText = "";

    // 锁屏
    private boolean locked = false;
    private String lockPassword = "";
    private StackPane contentStack;
    private javafx.scene.Node terminalContent;

    public TerminalTab(String name, SSHTerminal terminal, SSHConnection ssh, ConnectionInfo info) {
        super(name);
        this.connectionName = name;
        this.terminal = terminal;
        this.connectionInfo = info;
        this.bridge = new TerminalBridge(this);

        // 使用 StackPane 作为容器，方便叠加锁屏层
        contentStack = new StackPane();
        contentStack.setStyle("-fx-background-color: #1e1e1e;");

        // 初始加载中
        Label loadingLabel = new Label("正在初始化终端...");
        loadingLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 16px;");
        terminalContent = loadingLabel;
        contentStack.getChildren().add(terminalContent);
        setContent(contentStack);

        // 异步初始化WebView
        initWebView();

        // SSH 数据回调
        terminal.setOnDataReceived(data -> {
            Platform.runLater(() -> writeToTerminal(data));
        });

        terminal.setOnDisconnected(msg -> {
            Platform.runLater(() -> {
                setText(connectionName + " [已断开]");
                writeToTerminal("\r\n\u001b[31m" + msg + "\u001b[0m\r\n");
            });
        });

        setOnCloseRequest(e -> {
            closeTerminal();
        });

        // 标签页头部右键菜单
        setupTabContextMenu();
    }

    /** 设置标签页右键菜单 */
    private void setupTabContextMenu() {
        ContextMenu tabMenu = new ContextMenu();

        MenuItem reconnectItem = new MenuItem("重新连接");
        reconnectItem.setOnAction(e -> {
            if (onReconnect != null) onReconnect.run();
        });

        MenuItem disconnectItem = new MenuItem("断开连接");
        disconnectItem.setOnAction(e -> {
            if (onDisconnectAction != null) onDisconnectAction.run();
        });

        MenuItem editItem = new MenuItem("编辑连接");
        editItem.setOnAction(e -> {
            if (onEditConnection != null) onEditConnection.run();
        });

        MenuItem deleteItem = new MenuItem("删除连接");
        deleteItem.setOnAction(e -> {
            if (onDeleteConnection != null) onDeleteConnection.run();
        });

        tabMenu.getItems().addAll(
                reconnectItem, disconnectItem,
                new SeparatorMenuItem(),
                editItem, deleteItem
        );
        setContextMenu(tabMenu);
    }

    // ========== 回调设置 ==========

    public void setOnReconnect(Runnable r) { this.onReconnect = r; }
    public void setOnDisconnectAction(Runnable r) { this.onDisconnectAction = r; }
    public void setOnDeleteConnection(Runnable r) { this.onDeleteConnection = r; }
    public void setOnEditConnection(Runnable r) { this.onEditConnection = r; }

    public ConnectionInfo getConnectionInfo() { return connectionInfo; }

    // ========== 锁屏 ==========

    public boolean isLocked() { return locked; }

    public void lock(String password) {
        this.lockPassword = password;
        this.locked = true;
        Platform.runLater(() -> {
            // 移除旧锁屏
            contentStack.getChildren().removeIf(n ->
                    n != terminalContent && n.getStyleClass().contains("lock-overlay"));

            VBox lockOverlay = new VBox(12);
            lockOverlay.setAlignment(Pos.CENTER);
            lockOverlay.setStyle("-fx-background-color: rgba(30,30,30,0.95);");
            lockOverlay.getStyleClass().add("lock-overlay");

            Label lockIcon = new Label("\uD83D\uDD12");
            lockIcon.setStyle("-fx-font-size: 48px;");

            Label lockText = new Label("会话已锁定");
            lockText.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 18px;");

            Label hintText = new Label("点击[会话]菜单 -> 锁定会话 以解锁");
            hintText.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");

            lockOverlay.getChildren().addAll(lockIcon, lockText, hintText);

            contentStack.getChildren().add(lockOverlay);
        });
    }

    public boolean unlock(String password) {
        if (lockPassword.equals(password)) {
            this.locked = false;
            this.lockPassword = "";
            Platform.runLater(() -> {
                contentStack.getChildren().removeIf(n ->
                        n.getStyleClass().contains("lock-overlay"));
            });
            return true;
        }
        return false;
    }

    private void initWebView() {
        Platform.runLater(() -> {
            webView = new WebView();
            webEngine = webView.getEngine();

            // 加载终端HTML
            String htmlUrl = getClass().getResource("/com/remoteterminal/terminal.html").toExternalForm();

            webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED) {
                    // 页面加载完成，设置Java-JS桥接
                    JSObject window = (JSObject) webEngine.executeScript("window");
                    window.setMember("terminalBridge", bridge);
                    Platform.runLater(() -> {
                        terminalContent = webView;
                        contentStack.getChildren().clear();
                        contentStack.getChildren().add(terminalContent);
                    });
                } else if (newState == Worker.State.FAILED) {
                    Platform.runLater(() -> {
                        Label errorLabel = new Label("终端初始化失败。请检查网络连接（xterm.js需要从CDN加载）");
                        errorLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 14px; -fx-padding: 20px;");
                        terminalContent = new BorderPane(errorLabel);
                        contentStack.getChildren().clear();
                        contentStack.getChildren().add(terminalContent);
                    });
                }
            });

            webEngine.load(htmlUrl);

            // 键盘事件处理（WebView吞掉的部分组合键）
            webView.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyEvent);

            // ESC 关闭右键菜单
            webView.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ESCAPE && terminalCtxMenu != null && terminalCtxMenu.isShowing()) {
                    terminalCtxMenu.hide();
                    terminalCtxMenu = null;
                    e.consume();
                }
            });

            // 右键菜单
            webView.setContextMenuEnabled(false);
            webView.setOnContextMenuRequested(e -> showTerminalContextMenu(e.getScreenX(), e.getScreenY()));
        });
    }

    /** 键盘快捷键处理 */
    private void handleKeyEvent(KeyEvent e) {
        if (e.isControlDown() && e.isShiftDown()) {
            switch (e.getCode()) {
                case C -> {
                    // Ctrl+Shift+C: 复制
                    e.consume();
                    copySelection();
                }
                case V -> {
                    // Ctrl+Shift+V: 粘贴
                    e.consume();
                    pasteFromClipboard();
                }
            }
        }
        if (e.isControlDown() && !e.isShiftDown()) {
            switch (e.getCode()) {
                case INSERT -> {
                    // Ctrl+Insert: 复制
                    e.consume();
                    copySelection();
                }
                case L -> {
                    // Ctrl+L: 清屏
                    e.consume();
                    clearScreen();
                }
            }
        }
        if (e.isShiftDown() && e.getCode() == KeyCode.INSERT) {
            // Shift+Insert: 粘贴
            e.consume();
            pasteFromClipboard();
        }
    }

    // ========== 终端操作 ==========

    /** 向终端写入数据 */
    public void writeToTerminal(String data) {
        if (webEngine != null && data != null) {
            String escaped = escapeForJS(data);
            webEngine.executeScript("if(window.writeToTerminal) window.writeToTerminal('" + escaped + "')");
        }
    }

    /** 追加错误信息 */
    public void appendError(String msg) {
        writeToTerminal("\r\n\u001b[1;31m[错误] " + msg + "\u001b[0m\r\n");
    }

    /** 发送输入到SSH — 子线程执行，避免网络拥塞阻塞 UI 渲染 */
    void sendToSSH(String data) {
        if (locked) return;
        if (terminal == null || !terminal.isRunning()) return;
        // SSH 写入移出主线程，保证界面流畅
        new Thread(() -> {
            terminal.send(data);
            // 同步输入广播（同样在子线程中通知）
            if (inputListener != null) {
                inputListener.accept(data);
            }
        }, "SSH-Send").start();
    }

    public void setOnInput(java.util.function.Consumer<String> listener) {
        this.inputListener = listener;
    }

    /** 清屏 */
    private void clearScreen() {
        if (webEngine != null) {
            webEngine.executeScript("if(window.clearTerminal) window.clearTerminal()");
        }
    }

    /** 清除回滚缓冲区 */
    private void clearScrollback() {
        if (webEngine != null) {
            webEngine.executeScript("if(window.clearScrollback) window.clearScrollback()");
        }
    }

    /** 复制选中文本 */
    private void copySelection() {
        if (webEngine != null) {
            Object result = webEngine.executeScript("if(window.copySelection) window.copySelection()");
            if (result instanceof String text && !text.isEmpty()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
            }
        }
    }

    /** 从剪贴板粘贴 — 读剪贴板必须在主线程，写 SSH 通过 sendToSSH 走子线程 */
    private void pasteFromClipboard() {
        String text = Clipboard.getSystemClipboard().getString();
        if (text != null && !text.isEmpty()) {
            sendToSSH(text);
        }
    }

    /** 全选 */
    private void selectAll() {
        if (webEngine != null) {
            webEngine.executeScript("if(window.selectAllTerminal) window.selectAllTerminal()");
        }
    }

    /** 查找 */
    private void find() {
        TextInputDialog dialog = new TextInputDialog(lastSearchText);
        dialog.setTitle("查找");
        dialog.setHeaderText("在终端中搜索");
        dialog.setContentText("搜索内容:");

        dialog.showAndWait().ifPresent(searchText -> {
            lastSearchText = searchText;
            if (webEngine != null && !searchText.isEmpty()) {
                // xterm.js 搜索功能
                String escaped = escapeForJS(searchText);
                webEngine.executeScript(
                        "if(window.findInTerminal) window.findInTerminal('" + escaped + "')"
                );
            }
        });
    }

    // ========== 右键菜单 ==========

    private void showTerminalContextMenu(double x, double y) {
        // 关闭上次残留的菜单
        if (terminalCtxMenu != null) {
            terminalCtxMenu.hide();
            terminalCtxMenu = null;
        }

        ContextMenu menu = new ContextMenu();

        MenuItem copyItem = new MenuItem("复制");
        copyItem.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        copyItem.setOnAction(e -> copySelection());

        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        pasteItem.setOnAction(e -> pasteFromClipboard());

        MenuItem findItem = new MenuItem("查找");
        findItem.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN));
        findItem.setOnAction(e -> find());

        MenuItem clearScrollbackItem = new MenuItem("清除回滚");
        clearScrollbackItem.setOnAction(e -> clearScrollback());

        MenuItem clearScreenItem = new MenuItem("清屏");
        clearScreenItem.setOnAction(e -> clearScreen());

        MenuItem selectAllItem = new MenuItem("全选");
        selectAllItem.setOnAction(e -> selectAll());

        menu.getItems().addAll(
                copyItem, pasteItem, findItem,
                new SeparatorMenuItem(),
                clearScrollbackItem, clearScreenItem, selectAllItem
        );

        menu.setAutoHide(true);
        menu.setOnHidden(ev -> {
            terminalCtxMenu = null;
            // 菜单关闭时清理场景事件过滤器
            javafx.scene.Scene s = webView.getScene();
            if (s != null) {
                s.removeEventFilter(MouseEvent.MOUSE_PRESSED, closeHandler);
            }
        });

        terminalCtxMenu = menu;
        menu.show(webView, x, y);

        // WebView 会吞噬鼠标事件导致 autoHide 失效，添加场景级监听兜底
        javafx.scene.Scene scene = webView.getScene();
        if (scene != null) {
            // 监听场景任意位置点击 → 若不在菜单内则关闭
            scene.addEventFilter(MouseEvent.MOUSE_PRESSED, closeHandler);
        }
    }

    /** 点击外部关闭菜单的事件处理器 */
    private final javafx.event.EventHandler<MouseEvent> closeHandler = this::handleMenuOutsideClick;

    private void handleMenuOutsideClick(MouseEvent event) {
        if (terminalCtxMenu != null && terminalCtxMenu.isShowing()) {
            javafx.scene.Node target = event.getTarget() instanceof javafx.scene.Node
                    ? (javafx.scene.Node) event.getTarget() : null;
            if (target != null) {
                javafx.scene.Node menuNode = terminalCtxMenu.getSkin() != null
                        ? terminalCtxMenu.getSkin().getNode() : null;
                if (menuNode != null && isInsideNode(target, menuNode)) {
                    return; // 点击在菜单内部，不关闭
                }
            }
            // 点击在菜单外部，关闭
            terminalCtxMenu.hide();
            terminalCtxMenu = null;
        }
    }

    /** 递归检查 target 节点是否在 ancestor 的子树中 */
    private static boolean isInsideNode(javafx.scene.Node target, javafx.scene.Node ancestor) {
        javafx.scene.Node current = target;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParent();
        }
        return false;
    }

    // ========== 生命周期 ==========

    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    private void closeTerminal() {
        try { terminal.close(); } catch (Exception ignored) {}
        if (onClose != null) {
            Platform.runLater(onClose);
        }
    }

    public SSHTerminal getTerminal() {
        return terminal;
    }

    // ========== 工具方法 ==========

    /** 转义JS字符串 */
    private String escapeForJS(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r\n", "\\r\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }

    // ========== JavaScript 桥接类 ==========

    /**
     * 暴露给JavaScript的桥接对象
     * xterm.js 通过此对象与Java通信
     */
    public static class TerminalBridge {

        private final TerminalTab tab;
        private final AtomicInteger resizeCounter = new AtomicInteger(0);

        public TerminalBridge(TerminalTab tab) {
            this.tab = tab;
        }

        /** JS调用: 终端输入事件 */
        public void onTerminalInput(String data) {
            tab.sendToSSH(data);
        }

        /** JS调用: 终端标题变更 */
        public void onTitleChange(String title) {
            Platform.runLater(() -> {
                if (title != null && !title.isEmpty()) {
                    tab.setText(tab.connectionName + " - " + title);
                }
            });
        }

        /** JS调用: 终端尺寸变更 */
        public void onTerminalResize(int cols, int rows) {
            // 防抖：避免频繁调整
            int count = resizeCounter.incrementAndGet();
            new Thread(() -> {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                if (resizeCounter.get() == count && tab.terminal != null) {
                    tab.terminal.resize(cols, rows);
                }
            }).start();
        }
    }
}
