package com.remoteterminal.ui;

import com.remoteterminal.ssh.SSHFileManager;
import com.remoteterminal.ssh.SSHFileManager.SftpFileItem;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * 文件浏览器 - 纯列表视图，点击目录导航，无折叠箭头，无限深度
 * 展示当前目录内容，缩进体现层级
 * 支持拖拽上传和进度展示
 */
public class FileBrowserView extends VBox {

    private final MainLayout mainLayout;
    private final Label pathLabel;
    private final ListView<FileEntry> fileListView;

    private Long currentConnId;
    private String currentPath = "/";
    private SSHFileManager currentFM;

    // 记录进入过的目录路径，用于导航
    private final Deque<String> pathHistory = new ArrayDeque<>();

    // 导航锁，防止并发重复导航
    private volatile boolean navigating = false;

    // 传输中标志
    private volatile boolean transferring = false;

    // 右键菜单引用（用于手动关闭）
    private ContextMenu fileBrowserCtxMenu;

    // 远程剪贴板（仅在同一个 SSH 会话内有效）
    private String remoteClipPath;       // 远程绝对路径
    private String remoteClipName;       // 文件名
    private boolean remoteClipIsDir;     // 是否为目录
    private Long remoteClipConnId;       // 来源连接ID

    // 回调和元数据
    private Consumer<String> onStatusChange;

    // 进度 UI
    private final HBox progressPane;
    private final ProgressBar progressBar;
    private final Label progressLabel;
    private final Label progressFileLabel;

    // 图标
    private static final String ICON_FOLDER = "\uD83D\uDCC1";
    private static final String ICON_FILE   = "\uD83D\uDCC4";

    public FileBrowserView(MainLayout mainLayout) {
        this.mainLayout = mainLayout;

        setSpacing(0);
        setStyle("-fx-background-color: #252526;");

        // ---- 路径导航栏 ----
        HBox pathBar = new HBox(6);
        pathBar.setAlignment(Pos.CENTER_LEFT);
        pathBar.setPadding(new Insets(4, 8, 4, 8));
        pathBar.setStyle("-fx-background-color: #333333; -fx-border-color: #444444; -fx-border-width: 0 0 1 0;");

        Button upBtn = new Button("\u2B06");
        upBtn.setTooltip(new Tooltip("上级目录"));
        upBtn.setOnAction(e -> goUp());

        Button refreshBtn = new Button("\u21BB");
        refreshBtn.setTooltip(new Tooltip("刷新"));
        refreshBtn.setOnAction(e -> forceRefresh());

        // 上传按钮（刷新按钮右侧）
        MenuButton uploadBtn = new MenuButton("\u2B06\uFE0F");
        uploadBtn.setTooltip(new Tooltip("上传到当前目录"));
        uploadBtn.setStyle("-fx-background-color: #0e639c; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 2 8;");

        MenuItem uploadFileItem = new MenuItem("上传文件");
        uploadFileItem.setOnAction(e -> handleUpload(false));

        MenuItem uploadDirItem = new MenuItem("上传文件夹");
        uploadDirItem.setOnAction(e -> handleUpload(true));

        uploadBtn.getItems().addAll(uploadFileItem, uploadDirItem);

        pathLabel = new Label("/");
        pathLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12px;");
        pathLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(pathLabel, Priority.ALWAYS);

        pathBar.getChildren().addAll(upBtn, refreshBtn, uploadBtn, new Separator(), pathLabel);
        pathBar.getStyleClass().add("toolbar");

        // ---- 文件列表 ----
        fileListView = new ListView<>();
        fileListView.setStyle("-fx-background-color: #252526;");
        fileListView.setCellFactory(lv -> new FileListCell());
        VBox.setVgrow(fileListView, Priority.ALWAYS);

        // 双击打开
        fileListView.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                FileEntry entry = fileListView.getSelectionModel().getSelectedItem();
                if (entry != null) {
                    navigateTo(entry.getFullPath());
                }
            }
        });

        // 右键菜单
        fileListView.setOnContextMenuRequested(e -> {
            // 关闭上次残留的菜单
            if (fileBrowserCtxMenu != null) {
                fileBrowserCtxMenu.hide();
            }
            FileEntry selected = fileListView.getSelectionModel().getSelectedItem();
            fileBrowserCtxMenu = buildFileBrowserContextMenu(selected);
            if (fileBrowserCtxMenu != null) {
                fileBrowserCtxMenu.setAutoHide(true);
                // 菜单被隐藏时清除引用
                fileBrowserCtxMenu.setOnHidden(ev -> fileBrowserCtxMenu = null);
                fileBrowserCtxMenu.show(fileListView, e.getScreenX(), e.getScreenY());
            }
        });

        // 点击菜单外任意位置 → 关闭菜单
        this.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (fileBrowserCtxMenu != null && fileBrowserCtxMenu.isShowing()) {
                javafx.scene.Node target = event.getTarget() instanceof javafx.scene.Node
                        ? (javafx.scene.Node) event.getTarget() : null;
                if (target != null) {
                    // 检查点击目标是否在菜单或菜单项内部
                    javafx.scene.Node menuNode = fileBrowserCtxMenu.getSkin() != null
                            ? fileBrowserCtxMenu.getSkin().getNode() : null;
                    if (menuNode != null && isInsideNode(target, menuNode)) {
                        return; // 点击在菜单内部，不关闭
                    }
                }
                // 点击在菜单外部，关闭
                fileBrowserCtxMenu.hide();
                fileBrowserCtxMenu = null;
            }
        });

        // 键盘快捷键
        fileListView.setOnKeyPressed(e -> {
            // ESC 键关闭菜单
            if (e.getCode() == KeyCode.ESCAPE && fileBrowserCtxMenu != null) {
                fileBrowserCtxMenu.hide();
                fileBrowserCtxMenu = null;
                e.consume();
                return;
            }
            // Ctrl+C：复制当前选中项到远程剪贴板
            if (e.isControlDown() && e.getCode() == KeyCode.C) {
                FileEntry sel = fileListView.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    copyToRemoteClipboard(sel.getFullPath(), sel.getName(), sel.isDirectory());
                }
                e.consume();
                return;
            }
            // Ctrl+V：从远程剪贴板粘贴到当前目录
            if (e.isControlDown() && e.getCode() == KeyCode.V) {
                pasteFromRemoteClipboard(currentPath);
                e.consume();
            }
        });

        // ---- 拖拽支持 ----
        setupDragAndDrop();

        // ---- 进度面板（默认隐藏） ----
        progressPane = new HBox(8);
        progressPane.setAlignment(Pos.CENTER_LEFT);
        progressPane.setPadding(new Insets(6, 10, 6, 10));
        progressPane.setStyle("-fx-background-color: #2d2d2d; -fx-border-color: #444444; -fx-border-width: 1 0 0 0;");
        progressPane.setVisible(false);
        progressPane.setManaged(false);

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(200);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        progressLabel = new Label("0%");
        progressLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px; -fx-min-width: 40px;");

        progressFileLabel = new Label("");
        progressFileLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");

        progressPane.getChildren().addAll(progressBar, progressLabel, progressFileLabel);

        getChildren().addAll(pathBar, fileListView, progressPane);
        setDisable(true);
    }

    // ==================== 拖拽支持 ====================

    private void setupDragAndDrop() {
        // --- 拖拽外部文件到列表 → 上传到 Linux ---
        fileListView.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && currentFM != null && !transferring) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });

        fileListView.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles() && currentFM != null) {
                List<File> files = db.getFiles();
                if (files != null && !files.isEmpty()) {
                    uploadFiles(files);
                }
            }
            e.setDropCompleted(true);
            e.consume();
        });
    }

    /** 上传按钮处理 */
    private void handleUpload(boolean isDir) {
        if (currentFM == null) return;
        if (isDir) {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("选择要上传的文件夹");
            File dir = chooser.showDialog(getScene().getWindow());
            if (dir != null) uploadDirectory(dir.toPath());
        } else {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("选择要上传的文件");
            List<File> files = chooser.showOpenMultipleDialog(getScene().getWindow());
            if (files != null && !files.isEmpty()) uploadFiles(files);
        }
    }

    /** 拖拽批量上传文件 */
    private void uploadFiles(List<File> files) {
        if (files == null || files.isEmpty() || currentFM == null) return;
        if (transferring) {
            if (onStatusChange != null) onStatusChange.accept("正在传输中，请稍后...");
            return;
        }
        transferring = true;
        showProgress("准备上传 " + files.size() + " 个项目...");

        new Thread(() -> {
            int total = files.size();
            int[] completed = {0};
            try {
                for (File f : files) {
                    Path p = f.toPath();
                    if (Files.isDirectory(p)) {
                        Platform.runLater(() -> setProgressFile("上传文件夹: " + p.getFileName()));
                        currentFM.uploadDirectory(p, currentPath, pct ->
                            Platform.runLater(() -> setProgress(pct, "上传文件夹 " + p.getFileName()))
                        );
                    } else {
                        Platform.runLater(() -> setProgressFile("上传: " + p.getFileName()));
                        currentFM.uploadFile(p, currentPath, pct ->
                            Platform.runLater(() -> setProgress(pct, "上传 " + p.getFileName()))
                        );
                    }
                    completed[0]++;
                }
                Platform.runLater(() -> {
                    hideProgress();
                    transferring = false;
                    refresh();
                    if (onStatusChange != null)
                        onStatusChange.accept("上传完成: " + completed[0] + "/" + total);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hideProgress();
                    transferring = false;
                    showError("上传失败: " + e.getMessage());
                });
            }
        }, "DnD-Upload").start();
    }

    /** 从按钮上传单个文件夹 */
    private void uploadDirectory(Path localDir) {
        if (currentFM == null) return;
        if (transferring) return;
        transferring = true;
        String name = localDir.getFileName().toString();
        showProgress("上传文件夹: " + name);

        new Thread(() -> {
            try {
                currentFM.uploadDirectory(localDir, currentPath, pct ->
                    Platform.runLater(() -> setProgress(pct, "上传 " + name))
                );
                Platform.runLater(() -> {
                    hideProgress();
                    transferring = false;
                    refresh();
                    if (onStatusChange != null) onStatusChange.accept("上传完成: " + name);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hideProgress();
                    transferring = false;
                    showError("上传失败: " + e.getMessage());
                });
            }
        }, "Upload-Dir").start();
    }

    // ==================== 导航 ====================

    /** 绑定连接到文件浏览器 */
    public void bindConnection(long connId) {
        this.currentConnId = connId;
        this.currentFM = mainLayout.getFileManager(connId);
        if (currentFM != null) {
            setDisable(false);
            navigateTo("/");
        } else {
            setDisable(true);
        }
    }

    /** 解除连接绑定 */
    public void unbindConnection() {
        this.currentConnId = null;
        this.currentFM = null;
        this.currentPath = "/";
        this.pathHistory.clear();
        clearRemoteClipboard();
        Platform.runLater(() -> {
            fileListView.getItems().clear();
            pathLabel.setText("/");
            setDisable(true);
        });
    }

    /** 导航到指定目录（无限深度 - 可导航到任意深的目录） */
    public void navigateTo(String path) {
        if (currentFM == null || currentConnId == null) return;

        // 防止并发重复导航
        if (navigating) return;

        // 保存历史（排除相同路径和 ".." 父目录）
        if (!path.equals(currentPath) && !path.endsWith("/..")) {
            pathHistory.push(currentPath);
        }
        currentPath = path;
        pathLabel.setText(path);

        navigating = true;
        new Thread(() -> {
            try {
                List<SftpFileItem> items = currentFM.listFiles(path);
                if (items == null) items = Collections.emptyList();
                List<FileEntry> entries = new ArrayList<>();
                for (SftpFileItem item : items) {
                    if (".".equals(item.name())) continue;
                    entries.add(new FileEntry(
                            item.name(),
                            item.isDirectory(),
                            item.getAbsolutePath(),
                            item.isDirectory() ? ICON_FOLDER : ICON_FILE,
                            0
                    ));
                }
                Platform.runLater(() -> {
                    fileListView.getItems().setAll(entries);
                    setDisable(false);
                    navigating = false;
                });
                if (onStatusChange != null) {
                    Platform.runLater(() -> onStatusChange.accept("已加载: " + path));
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    fileListView.getItems().clear();
                    String errMsg = e.getMessage();
                    if (errMsg != null && (errMsg.contains("End of file") || errMsg.contains("EOF"))) {
                        errMsg = "SSH连接已断开，请点击刷新按钮或重新连接";
                    }
                    fileListView.getItems().add(new FileEntry("(加载失败: " + errMsg + ")", false, "", "", 0));
                    navigating = false;
                });
            }
        }, "Browse-" + path.hashCode()).start();
    }

    private void goUp() {
        if ("/".equals(currentPath)) return;
        int idx = currentPath.lastIndexOf('/');
        String parent = idx <= 0 ? "/" : currentPath.substring(0, idx);
        navigateTo(parent);
    }

    public void refresh() {
        if (currentPath != null) {
            navigateTo(currentPath);
        }
    }

    /** 强制刷新 — 绕过导航锁，确保刷新按钮始终可点击 */
    private void forceRefresh() {
        if (currentPath == null || currentFM == null || currentConnId == null) return;
        navigating = false; // 清除可能卡住的锁
        refresh();
    }

    public Long getCurrentConnId() { return currentConnId; }
    public String getCurrentPath() { return currentPath; }
    public String getSelectedPath() {
        FileEntry entry = fileListView.getSelectionModel().getSelectedItem();
        return entry != null ? entry.getFullPath() : currentPath;
    }
    public String getSelectedParentPath() {
        FileEntry entry = fileListView.getSelectionModel().getSelectedItem();
        if (entry != null) {
            return entry.isDirectory() ? entry.getFullPath() : currentPath;
        }
        return currentPath;
    }

    public void setOnStatusChange(Consumer<String> handler) { this.onStatusChange = handler; }

    // ==================== 右键菜单 ====================

    private ContextMenu buildFileBrowserContextMenu(FileEntry selected) {
        if (currentFM == null || currentConnId == null) return null;
        ContextMenu menu = new ContextMenu();

        // 空白区域菜单
        if (selected == null) {
            // 粘贴（仅当远程剪贴板有内容且属于同一会话时可用）
            MenuItem pasteItem = new MenuItem("粘贴");
            pasteItem.setDisable(!isRemoteClipValid());
            pasteItem.setOnAction(e -> pasteFromRemoteClipboard(currentPath));

            MenuItem refreshItem = new MenuItem("刷新");
            refreshItem.setOnAction(e -> refresh());

            MenuItem newFolderItem = new MenuItem("新建文件夹");
            newFolderItem.setOnAction(e -> createNewFolder());

            MenuItem newFileItem = new MenuItem("新建文件");
            newFileItem.setOnAction(e -> createNewFile());

            MenuItem uploadFileItem = new MenuItem("上传文件");
            uploadFileItem.setOnAction(e -> uploadFile(false));

            MenuItem uploadDirItem = new MenuItem("上传文件夹");
            uploadDirItem.setOnAction(e -> uploadFile(true));

            MenuItem copyPathItem = new MenuItem("复制当前路径");
            copyPathItem.setOnAction(e -> {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(currentPath);
                Clipboard.getSystemClipboard().setContent(cc);
                if (onStatusChange != null) onStatusChange.accept("路径已复制: " + currentPath);
            });

            menu.getItems().addAll(pasteItem, new SeparatorMenuItem(),
                    newFolderItem, newFileItem, new SeparatorMenuItem(),
                    uploadFileItem, uploadDirItem, new SeparatorMenuItem(),
                    copyPathItem, refreshItem);
            return menu;
        }

        String path = selected.getFullPath();
        boolean isDir = selected.isDirectory();

        // 普通进入目录
        if (isDir) {
            MenuItem enterItem = new MenuItem("进入目录");
            enterItem.setOnAction(e -> navigateTo(path));
            menu.getItems().add(enterItem);

            menu.getItems().add(new SeparatorMenuItem());

            // 复制文件夹
            MenuItem copyItem = new MenuItem("复制");
            copyItem.setOnAction(e -> copyToRemoteClipboard(path, selected.getName(), true));
            menu.getItems().add(copyItem);

            // 粘贴到该文件夹
            MenuItem pasteItem = new MenuItem("粘贴");
            pasteItem.setDisable(!isRemoteClipValid());
            pasteItem.setOnAction(e -> pasteFromRemoteClipboard(path));
            menu.getItems().add(pasteItem);
        } else {
            MenuItem openItem = new MenuItem("打开/查看");
            openItem.setOnAction(e -> openRemoteFile(path));
            menu.getItems().add(openItem);

            MenuItem editItem = new MenuItem("用编辑器打开");
            editItem.setOnAction(e -> openWithEditor(path));
            menu.getItems().add(editItem);

            menu.getItems().add(new SeparatorMenuItem());

            // 复制文件
            MenuItem copyItem = new MenuItem("复制");
            copyItem.setOnAction(e -> copyToRemoteClipboard(path, selected.getName(), false));
            menu.getItems().add(copyItem);
        }

        menu.getItems().add(new SeparatorMenuItem());

        // 删除/重命名
        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> deleteItem(path, isDir));
        menu.getItems().add(deleteItem);

        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> renameItem(path));
        menu.getItems().add(renameItem);

        menu.getItems().add(new SeparatorMenuItem());

        // 下载
        MenuItem dlItem = new MenuItem("下载");
        dlItem.setOnAction(e -> downloadItem(path, isDir));
        menu.getItems().add(dlItem);

        // 上传（目标为选中项的父目录或选中项目录）
        String uploadTarget = isDir ? path : getParentFromPath(path);
        MenuItem upFileItem = new MenuItem("上传文件到此处");
        upFileItem.setOnAction(e -> uploadToTarget(uploadTarget, false));
        menu.getItems().add(upFileItem);

        MenuItem upDirItem = new MenuItem("上传文件夹到此处");
        upDirItem.setOnAction(e -> uploadToTarget(uploadTarget, true));
        menu.getItems().add(upDirItem);

        menu.getItems().add(new SeparatorMenuItem());

        // 复制路径
        MenuItem copyPathItem = new MenuItem("复制路径");
        copyPathItem.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(path);
            Clipboard.getSystemClipboard().setContent(cc);
            if (onStatusChange != null) onStatusChange.accept("路径已复制: " + path);
        });
        menu.getItems().add(copyPathItem);

        // 属性
        MenuItem propsItem = new MenuItem("属性");
        propsItem.setOnAction(e -> showProperties(path));
        menu.getItems().add(propsItem);

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refresh());
        menu.getItems().add(refreshItem);

        return menu;
    }

    // ==================== 文件操作 ====================

    private void createNewFolder() {
        TextInputDialog dialog = new TextInputDialog("新建文件夹");
        dialog.setTitle("新建文件夹");
        dialog.setHeaderText("在 " + currentPath + " 中创建文件夹");
        dialog.setContentText("文件夹名称:");
        dialog.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) {
                runAsync(() -> {
                    try {
                        currentFM.createDirectory(currentPath, name);
                        Platform.runLater(() -> refresh());
                    } catch (Exception e) {
                        Platform.runLater(() -> showError("创建文件夹失败: " + e.getMessage()));
                    }
                });
            }
        });
    }

    private void createNewFile() {
        TextInputDialog dialog = new TextInputDialog("新建文件.txt");
        dialog.setTitle("新建文件");
        dialog.setHeaderText("在 " + currentPath + " 中创建文件");
        dialog.setContentText("文件名称:");
        dialog.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) {
                runAsync(() -> {
                    try {
                        currentFM.createFile(currentPath, name);
                        Platform.runLater(() -> refresh());
                    } catch (Exception e) {
                        Platform.runLater(() -> showError("创建文件失败: " + e.getMessage()));
                    }
                });
            }
        });
    }

    private void uploadFile(boolean isDir) {
        uploadToTarget(currentPath, isDir);
    }

    private void uploadToTarget(String remoteDir, boolean isDir) {
        if (currentFM == null || transferring) return;
        transferring = true;

        if (isDir) {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("选择要上传的文件夹");
            File dir = chooser.showDialog(getScene().getWindow());
            if (dir == null) { transferring = false; return; }
            String name = dir.getName();
            showProgress("上传文件夹: " + name);
            runAsync(() -> {
                try {
                    currentFM.uploadDirectory(dir.toPath(), remoteDir, pct ->
                        Platform.runLater(() -> setProgress(pct, "上传 " + name))
                    );
                    Platform.runLater(() -> { hideProgress(); transferring = false; refresh(); });
                } catch (Exception e) {
                    Platform.runLater(() -> { hideProgress(); transferring = false; showError("上传失败: " + e.getMessage()); });
                }
            });
        } else {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("选择要上传的文件");
            File file = chooser.showOpenDialog(getScene().getWindow());
            if (file == null) { transferring = false; return; }
            String name = file.getName();
            showProgress("上传: " + name);
            runAsync(() -> {
                try {
                    currentFM.uploadFile(file.toPath(), remoteDir, pct ->
                        Platform.runLater(() -> setProgress(pct, "上传 " + name))
                    );
                    Platform.runLater(() -> { hideProgress(); transferring = false; refresh(); });
                } catch (Exception e) {
                    Platform.runLater(() -> { hideProgress(); transferring = false; showError("上传失败: " + e.getMessage()); });
                }
            });
        }
    }

    private void openRemoteFile(String path) {
        runAsync(() -> {
            try {
                Path tempDir = Files.createTempDirectory("rt_");
                Path tempFile = tempDir.resolve(new File(path).getName());
                currentFM.downloadFile(path, tempFile);
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(tempFile.toFile());
                }
                Platform.runLater(() -> {
                    if (onStatusChange != null) onStatusChange.accept("已打开: " + path);
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("打开失败: " + e.getMessage()));
            }
        });
    }

    private void openWithEditor(String path) {
        runAsync(() -> {
            try {
                Path tempDir = Files.createTempDirectory("rt_");
                Path tempFile = tempDir.resolve(new File(path).getName());
                currentFM.downloadFile(path, tempFile);
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().edit(tempFile.toFile());
                }
                Platform.runLater(() -> {
                    if (onStatusChange != null) onStatusChange.accept("已用编辑器打开: " + path);
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("打开失败: " + e.getMessage()));
            }
        });
    }

    private void deleteItem(String path, boolean isDir) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除: " + path);
        confirm.setContentText(isDir ? "确定要删除此目录及其所有内容吗？" : "确定要删除此文件吗？");
        confirm.showAndWait().ifPresent(r -> {
            if (r == ButtonType.OK) {
                runAsync(() -> {
                    try {
                        if (isDir) currentFM.deleteDirectory(path);
                        else currentFM.deleteFile(path);
                        Platform.runLater(() -> refresh());
                    } catch (Exception e) {
                        Platform.runLater(() -> showError("删除失败: " + e.getMessage()));
                    }
                });
            }
        });
    }

    private void renameItem(String path) {
        String oldName = new File(path).getName();
        TextInputDialog dialog = new TextInputDialog(oldName);
        dialog.setTitle("重命名");
        dialog.setHeaderText("重命名: " + oldName);
        dialog.setContentText("新名称:");
        dialog.showAndWait().ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(oldName)) {
                runAsync(() -> {
                    try {
                        String parent = getParentFromPath(path);
                        String target = parent + (parent.endsWith("/") ? "" : "/") + newName;
                        currentFM.rename(path, target);
                        Platform.runLater(() -> refresh());
                    } catch (Exception e) {
                        Platform.runLater(() -> showError("重命名失败: " + e.getMessage()));
                    }
                });
            }
        });
    }

    private void downloadItem(String path, boolean isDir) {
        downloadWithProgress(path, isDir);
    }

    /** 带进度条的下载（支持文件和目录） */
    private void downloadWithProgress(String remotePath, boolean isDir) {
        if (currentFM == null || transferring) return;
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("选择下载位置");
        File dir = chooser.showDialog(getScene().getWindow());
        if (dir == null) return;

        String name = new File(remotePath).getName();
        transferring = true;
        showProgress("下载: " + name);

        runAsync(() -> {
            try {
                Path localTarget;
                if (isDir) {
                    currentFM.downloadDirectory(remotePath, dir.toPath(), pct ->
                        Platform.runLater(() -> setProgress(pct, "下载 " + name))
                    );
                    localTarget = dir.toPath().resolve(name);
                } else {
                    Path localPath = dir.toPath().resolve(name);
                    currentFM.downloadFile(remotePath, localPath, pct ->
                        Platform.runLater(() -> setProgress(pct, "下载 " + name))
                    );
                    localTarget = localPath;
                }
                final Path target = localTarget;
                Platform.runLater(() -> {
                    hideProgress();
                    transferring = false;
                    if (onStatusChange != null) onStatusChange.accept("已下载到: " + target);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hideProgress();
                    transferring = false;
                    showError("下载失败: " + e.getMessage());
                });
            }
        });
    }

    private void showProperties(String path) {
        runAsync(() -> {
            try {
                String props = currentFM.getFileProperties(path);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("文件属性");
                    alert.setHeaderText(new File(path).getName());
                    alert.setContentText(props);
                    alert.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("获取属性失败: " + e.getMessage()));
            }
        });
    }

    // ==================== 工具方法 ====================

    /** 递归检查 target 节点是否在 ancestor 的子树中 */
    private static boolean isInsideNode(javafx.scene.Node target, javafx.scene.Node ancestor) {
        javafx.scene.Node current = target;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParent();
        }
        return false;
    }

    private String getParentFromPath(String path) {
        if ("/".equals(path)) return "/";
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? "/" : path.substring(0, idx);
    }

    private void runAsync(Runnable task) {
        new Thread(task, "FileOp").start();
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("操作失败");
        alert.setContentText(msg);
        alert.showAndWait();
    }

    // ==================== 进度 UI ====================

    private void showProgress(String desc) {
        progressPane.setVisible(true);
        progressPane.setManaged(true);
        progressBar.setProgress(0);
        progressLabel.setText("0%");
        progressFileLabel.setText(desc);
    }

    private void setProgress(double pct, String desc) {
        progressBar.setProgress(pct / 100.0);
        progressLabel.setText(String.format("%.0f%%", pct));
        progressFileLabel.setText(desc);
    }

    private void setProgressFile(String desc) {
        progressFileLabel.setText(desc);
    }

    private void hideProgress() {
        progressPane.setVisible(false);
        progressPane.setManaged(false);
        progressBar.setProgress(0);
        progressLabel.setText("0%");
        progressFileLabel.setText("");
    }

    // ==================== 远程剪贴板（复制/粘贴） ====================

    /** 检查远程剪贴板是否有效（同一会话,有内容） */
    private boolean isRemoteClipValid() {
        return remoteClipPath != null
                && remoteClipConnId != null
                && remoteClipConnId.equals(currentConnId)
                && currentFM != null;
    }

    /** 清空远程剪贴板 */
    private void clearRemoteClipboard() {
        remoteClipPath = null;
        remoteClipName = null;
        remoteClipConnId = null;
        remoteClipIsDir = false;
    }

    /** 复制选中项到远程剪贴板 */
    private void copyToRemoteClipboard(String path, String name, boolean isDir) {
        this.remoteClipPath = path;
        this.remoteClipName = name;
        this.remoteClipIsDir = isDir;
        this.remoteClipConnId = currentConnId;
        if (onStatusChange != null) {
            onStatusChange.accept("已复制: " + path);
        }
    }

    /** 从远程剪贴板粘贴到目标目录 */
    private void pasteFromRemoteClipboard(String targetDir) {
        if (!isRemoteClipValid()) {
            if (onStatusChange != null) onStatusChange.accept("剪贴板为空或不属于当前会话");
            return;
        }
        doRemoteCopy(remoteClipPath, remoteClipName, targetDir, remoteClipIsDir);
    }

    /** 执行远程复制（带冲突检测） */
    private void doRemoteCopy(String sourcePath, String sourceName, String targetDir, boolean isSourceDir) {
        if (currentFM == null) return;
        String targetPath = targetDir + (targetDir.endsWith("/") ? "" : "/") + sourceName;

        runAsync(() -> {
            try {
                boolean exists = remotePathExists(targetPath);
                if (exists) {
                    Platform.runLater(() -> handlePasteConflict(sourcePath, sourceName, targetDir, targetPath));
                } else {
                    executeAndRefresh(sourcePath, targetDir);
                }
            } catch (Exception e) {
                Platform.runLater(() -> showError("粘贴失败: " + e.getMessage()));
            }
        });
    }

    /** 检测远程路径是否存在 */
    private boolean remotePathExists(String path) {
        if (currentFM == null) return false;
        try {
            String result = currentFM.exec("test -e " + shellQuote(path) + " && echo y || echo n");
            return result != null && result.trim().startsWith("y");
        } catch (Exception e) {
            return false;
        }
    }

    /** 对 shell 参数加单引号转义 */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 处理粘贴冲突对话框 */
    private void handlePasteConflict(String sourcePath, String sourceName, String targetDir, String targetPath) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("粘贴冲突");
        alert.setHeaderText("目标位置已存在同名项: " + sourceName);
        alert.setContentText("请选择处理方式:");

        ButtonType overwriteBtn = new ButtonType("覆盖");
        ButtonType skipBtn = new ButtonType("跳过");
        ButtonType renameBtn = new ButtonType("自动重命名");
        ButtonType cancelBtn = ButtonType.CANCEL;

        alert.getButtonTypes().setAll(overwriteBtn, skipBtn, renameBtn, cancelBtn);

        alert.showAndWait().ifPresent(response -> {
            if (response == overwriteBtn) {
                // 先删除已存在的,再复制
                runAsync(() -> {
                    try {
                        if (currentFM != null) {
                            currentFM.exec("rm -rf " + shellQuote(targetPath));
                            executeAndRefresh(sourcePath, targetDir);
                        }
                    } catch (Exception e) {
                        Platform.runLater(() -> showError("覆盖失败: " + e.getMessage()));
                    }
                });
            } else if (response == skipBtn) {
                if (onStatusChange != null) onStatusChange.accept("已跳过: " + sourceName);
            } else if (response == renameBtn) {
                runAsync(() -> {
                    try {
                        String newName = generateNonConflictName(targetDir, sourceName);
                        String newTarget = targetDir + (targetDir.endsWith("/") ? "" : "/") + newName;
                        if (currentFM != null) {
                            currentFM.exec("cp -a " + shellQuote(sourcePath) + " " + shellQuote(newTarget));
                        }
                        Platform.runLater(() -> {
                            refresh();
                            if (onStatusChange != null) onStatusChange.accept("已粘贴为: " + newName);
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showError("粘贴失败: " + e.getMessage()));
                    }
                });
            }
            // 取消 → 什么也不做
        });
    }

    /** 生成不冲突的文件名,如 "file (1).txt" */
    private String generateNonConflictName(String targetDir, String sourceName) {
        int dotIdx = sourceName.lastIndexOf('.');
        String base = dotIdx > 0 ? sourceName.substring(0, dotIdx) : sourceName;
        String ext = dotIdx > 0 ? sourceName.substring(dotIdx) : "";

        for (int i = 1; i <= 999; i++) {
            String candidate = base + " (" + i + ")" + ext;
            try {
                if (!sourceName.equals(candidate) && remotePathExists(
                        targetDir + (targetDir.endsWith("/") ? "" : "/") + candidate)) {
                    continue;
                }
            } catch (Exception ignored) {}
            return candidate;
        }
        // 兜底: 加时间戳
        return base + "_" + System.currentTimeMillis() + ext;
    }

    /** 执行 cp -a 并刷新 */
    private void executeAndRefresh(String sourcePath, String targetDir) {
        if (currentFM == null) return;
        try {
            // cp -a: 递归复制目录 + 保留属性
            currentFM.exec("cp -a " + shellQuote(sourcePath) + " " + shellQuote(targetDir) + "/");
            Platform.runLater(() -> {
                refresh();
                if (onStatusChange != null) onStatusChange.accept("粘贴完成: " + remoteClipName);
            });
        } catch (Exception e) {
            Platform.runLater(() -> showError("粘贴失败: " + e.getMessage()));
        }
    }

    // ==================== 内部类 ====================

    /**
     * 文件列表条目
     */
    public static class FileEntry {
        private final String name;
        private final boolean directory;
        private final String fullPath;
        private final String icon;
        private final int depth;

        public FileEntry(String name, boolean directory, String fullPath, String icon, int depth) {
            this.name = name;
            this.directory = directory;
            this.fullPath = fullPath;
            this.icon = icon;
            this.depth = depth;
        }

        public String getName() { return name; }
        public boolean isDirectory() { return directory; }
        public String getFullPath() { return fullPath; }
        public String getIcon() { return icon; }
        public int getDepth() { return depth; }
    }

    /**
     * 自定义列表单元格 - 缩进体现层级
     */
    private static class FileListCell extends ListCell<FileEntry> {
        @Override
        protected void updateItem(FileEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setStyle("");
            } else {
                // 缩进空格体现层级
                String indent = "  ".repeat(Math.max(0, item.getDepth()));
                String display = indent + item.getIcon() + " " + item.getName();
                setText(display);
                setStyle("-fx-text-fill: #cccccc; -fx-padding: 1 6 1 6;");
            }
        }
    }
}
