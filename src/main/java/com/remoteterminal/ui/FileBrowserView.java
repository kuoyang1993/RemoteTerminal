package com.remoteterminal.ui;

import com.remoteterminal.ssh.SSHFileManager;
import com.remoteterminal.ssh.SSHFileManager.SftpFileItem;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
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

    // 回调和元数据
    private Consumer<String> onStatusChange;

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

        Button homeBtn = new Button("\uD83C\uDFE0");
        homeBtn.setTooltip(new Tooltip("根目录"));
        homeBtn.setOnAction(e -> navigateTo("/"));

        Button upBtn = new Button("\u2B06");
        upBtn.setTooltip(new Tooltip("上级目录"));
        upBtn.setOnAction(e -> goUp());

        Button refreshBtn = new Button("\u21BB");
        refreshBtn.setTooltip(new Tooltip("刷新"));
        refreshBtn.setOnAction(e -> refresh());

        pathLabel = new Label("/");
        pathLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12px;");
        pathLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(pathLabel, Priority.ALWAYS);

        pathBar.getChildren().addAll(homeBtn, upBtn, refreshBtn, new Separator(), pathLabel);
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

        // 右键空白时也弹出菜单
        fileListView.setOnContextMenuRequested(e -> {
            FileEntry selected = fileListView.getSelectionModel().getSelectedItem();
            ContextMenu menu = buildFileBrowserContextMenu(selected);
            if (menu != null) {
                menu.show(fileListView, e.getScreenX(), e.getScreenY());
            }
        });

        getChildren().addAll(pathBar, fileListView);
        setDisable(true);
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
                    fileListView.getItems().add(new FileEntry("(加载失败: " + e.getMessage() + ")", false, "", "", 0));
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

            menu.getItems().addAll(newFolderItem, newFileItem, new SeparatorMenuItem(),
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
        } else {
            MenuItem openItem = new MenuItem("打开/查看");
            openItem.setOnAction(e -> openRemoteFile(path));
            menu.getItems().add(openItem);

            MenuItem editItem = new MenuItem("用编辑器打开");
            editItem.setOnAction(e -> openWithEditor(path));
            menu.getItems().add(editItem);
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
        dlItem.setOnAction(e -> downloadItem(path));
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
        if (isDir) {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("选择要上传的文件夹");
            File dir = chooser.showDialog(null);
            if (dir == null) return;
            runAsync(() -> {
                try {
                    currentFM.uploadDirectory(dir.toPath(), remoteDir);
                    Platform.runLater(() -> refresh());
                } catch (Exception e) {
                    Platform.runLater(() -> showError("上传失败: " + e.getMessage()));
                }
            });
        } else {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("选择要上传的文件");
            File file = chooser.showOpenDialog(null);
            if (file == null) return;
            runAsync(() -> {
                try {
                    currentFM.uploadFile(file.toPath(), remoteDir);
                    Platform.runLater(() -> refresh());
                } catch (Exception e) {
                    Platform.runLater(() -> showError("上传失败: " + e.getMessage()));
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

    private void downloadItem(String path) {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("选择下载位置");
        File dir = chooser.showDialog(null);
        if (dir == null) return;
        runAsync(() -> {
            try {
                Path localPath = dir.toPath().resolve(new File(path).getName());
                currentFM.downloadFile(path, localPath);
                Platform.runLater(() -> {
                    if (onStatusChange != null) onStatusChange.accept("已下载到: " + localPath);
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("下载失败: " + e.getMessage()));
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
