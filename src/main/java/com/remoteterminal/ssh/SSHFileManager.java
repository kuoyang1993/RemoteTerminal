package com.remoteterminal.ssh;

import com.jcraft.jsch.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * SSH 文件管理器 - 基于SFTP的远程文件操作
 */
public class SSHFileManager {

    private final SSHConnection sshConn;

    public SSHFileManager(SSHConnection sshConn) {
        this.sshConn = sshConn;
    }

    /** JSch 进度监听器包装，转为百分比回调 */
    private static class TransferProgress implements SftpProgressMonitor {
        private final Consumer<Double> callback;
        private long max;
        private long lastReportedAt;

        TransferProgress(Consumer<Double> callback) {
            this.callback = callback;
        }

        @Override
        public void init(int op, String src, String dest, long max) {
            this.max = max;
            if (callback != null && max <= 0) callback.accept(-1.0); // 未知大小
        }

        @Override
        public boolean count(long count) {
            if (callback != null && max > 0) {
                double pct = (double) count / max * 100.0;
                long now = System.currentTimeMillis();
                if (now - lastReportedAt > 150 || count >= max) {
                    callback.accept(pct);
                    lastReportedAt = now;
                }
            }
            return true;
        }

        @Override
        public void end() {
            if (callback != null) callback.accept(100.0);
        }
    }

    /** 列出目录内容（线程安全，防御性处理 JSch 内部解析异常；遇 EOF 自动重建通道重试） */
    public List<SftpFileItem> listFiles(String path) throws Exception {
        Exception lastError;
        List<SftpFileItem> result;
        synchronized (sshConn.getSftpLock()) {
            try {
                result = doListFiles(path);
                lastError = null;
            } catch (Exception e) {
                result = null;
                lastError = e;
            }
        }
        // 如果是通道已死（End of file），强制丢弃通道后重试一次
        if (lastError != null && isEofError(lastError)) {
            sshConn.invalidateSftpChannel();
            synchronized (sshConn.getSftpLock()) {
                return doListFiles(path);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return result;
    }

    /** 判断是否为 SFTP 通道断开类异常 */
    private static boolean isEofError(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("End of file") || msg.contains("EOF"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** 实际执行 SFTP ls */
    private List<SftpFileItem> doListFiles(String path) throws Exception {
        ChannelSftp sftp = sshConn.openSftpChannel();
        List<SftpFileItem> items = new ArrayList<>();

        try {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(path);

            if (entries == null || entries.isEmpty()) {
                return items;
            }

            for (ChannelSftp.LsEntry entry : entries) {
                String name = entry.getFilename();
                if (name == null) continue;
                if (".".equals(name)) continue;
                if ("..".equals(name)) {
                    try {
                        items.add(new SftpFileItem(name, true, entry.getAttrs(), path));
                    } catch (Exception ignored) {
                        items.add(new SftpFileItem(name, true, null, path));
                    }
                    continue;
                }
                try {
                    boolean isDir = entry.getAttrs().isDir();
                    items.add(new SftpFileItem(name, isDir, entry.getAttrs(), path));
                } catch (Exception e) {
                    // JSch 内部解析属性时可能抛出 ArrayIndexOutOfBoundsException
                    // 回退处理：通过 stat 单独获取
                    try {
                        String fullPath = path + (path.endsWith("/") ? "" : "/") + name;
                        SftpATTRS attrs = sftp.stat(fullPath);
                        items.add(new SftpFileItem(name, attrs.isDir(), attrs, path));
                    } catch (Exception ex) {
                        items.add(new SftpFileItem(name, false, null, path));
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new Exception("SFTP ls 解析失败: " + e.getMessage(), e);
        }

        items.sort((a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.name().compareToIgnoreCase(b.name());
        });

        return items;
    }

    /** 获取绝对路径 */
    private String fullPath(String parent, String name) {
        if ("..".equals(name)) {
            String p = parent.endsWith("/") ? parent.substring(0, parent.length() - 1) : parent;
            int idx = p.lastIndexOf('/');
            return idx <= 0 ? "/" : p.substring(0, idx);
        }
        return parent + (parent.endsWith("/") ? "" : "/") + name;
    }

    /** 下载文件到本地（无进度回调） */
    public void downloadFile(String remotePath, Path localPath) throws Exception {
        downloadFile(remotePath, localPath, null);
    }

    /** 下载文件到本地（带进度回调，0-100%） */
    public void downloadFile(String remotePath, Path localPath, Consumer<Double> progress) throws Exception {
        synchronized (sshConn.getSftpLock()) {
            ChannelSftp sftp = sshConn.openSftpChannel();
            if (progress != null) {
                sftp.get(remotePath, localPath.toString(), new TransferProgress(progress));
            } else {
                sftp.get(remotePath, localPath.toString());
            }
        }
    }

    /** 递归下载远程目录到本地（无进度回调） */
    public void downloadDirectory(String remoteDir, Path localParentDir) throws Exception {
        downloadDirectory(remoteDir, localParentDir, null);
    }

    /** 递归下载远程目录到本地（带进度回调，0-100%） */
    public void downloadDirectory(String remoteDir, Path localParentDir, Consumer<Double> progress) throws Exception {
        synchronized (sshConn.getSftpLock()) {
            ChannelSftp sftp = sshConn.openSftpChannel();
            String dirName = remoteDir.substring(remoteDir.lastIndexOf('/') + 1);
            Path targetDir = localParentDir.resolve(dirName);
            Files.createDirectories(targetDir);
            downloadDirRecursive(sftp, remoteDir, targetDir, progress);
        }
    }

    private void downloadDirRecursive(ChannelSftp sftp, String remoteDir, Path localDir,
                                       Consumer<Double> progress) throws Exception {
        @SuppressWarnings("unchecked")
        Vector<ChannelSftp.LsEntry> entries = sftp.ls(remoteDir);
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            String remotePath = remoteDir + "/" + name;
            Path localPath = localDir.resolve(name);
            if (entry.getAttrs().isDir()) {
                Files.createDirectories(localPath);
                downloadDirRecursive(sftp, remotePath, localPath, progress);
            } else {
                if (progress != null) {
                    sftp.get(remotePath, localPath.toString(), new TransferProgress(progress));
                } else {
                    sftp.get(remotePath, localPath.toString());
                }
            }
        }
    }

    /** 上传文件到远程（无进度回调） */
    public void uploadFile(Path localPath, String remoteDir) throws Exception {
        uploadFile(localPath, remoteDir, null);
    }

    /** 上传文件到远程（带进度回调，0-100%） */
    public void uploadFile(Path localPath, String remoteDir, Consumer<Double> progress) throws Exception {
        synchronized (sshConn.getSftpLock()) {
            ChannelSftp sftp = sshConn.openSftpChannel();
            sftp.cd(remoteDir);
            String destName = localPath.getFileName().toString();
            if (progress != null) {
                sftp.put(localPath.toString(), destName, new TransferProgress(progress));
            } else {
                sftp.put(localPath.toString(), destName);
            }
        }
    }

    /** 上传文件夹到远程（无进度回调） */
    public void uploadDirectory(Path localDir, String remoteDir) throws Exception {
        uploadDirectory(localDir, remoteDir, null);
    }

    /** 上传文件夹到远程（带进度回调，0-100%） */
    public void uploadDirectory(Path localDir, String remoteDir, Consumer<Double> progress) throws Exception {
        synchronized (sshConn.getSftpLock()) {
            ChannelSftp sftp = sshConn.openSftpChannel();
            String targetDir = remoteDir + "/" + localDir.getFileName().toString();
            try { sftp.mkdir(targetDir); } catch (Exception ignored) {}
            uploadDirRecursive(sftp, localDir, targetDir, progress);
        }
    }

    private void uploadDirRecursive(ChannelSftp sftp, Path localDir, String remoteDir,
                                    Consumer<Double> progress) throws Exception {
        try (var stream = Files.newDirectoryStream(localDir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    String subDir = remoteDir + "/" + child.getFileName().toString();
                    try { sftp.mkdir(subDir); } catch (Exception ignored) {}
                    uploadDirRecursive(sftp, child, subDir, progress);
                } else {
                    String childName = child.getFileName().toString();
                    sftp.cd(remoteDir);
                    if (progress != null) {
                        sftp.put(child.toString(), childName, new TransferProgress(progress));
                    } else {
                        sftp.put(child.toString(), childName);
                    }
                }
            }
        }
    }

    /** 创建远程目录 */
    public void createDirectory(String parentPath, String dirName) throws Exception {
        synchronized (sshConn.getSftpLock()) {
            ChannelSftp sftp = sshConn.openSftpChannel();
            String path = fullPath(parentPath, dirName);
            sftp.mkdir(path);
        }
    }

    /** 创建远程空文件 */
    public void createFile(String parentPath, String fileName) throws Exception {
        synchronized (sshConn.getSftpLock()) {
            ChannelSftp sftp = sshConn.openSftpChannel();
            String path = fullPath(parentPath, fileName);
            try (InputStream empty = new ByteArrayInputStream(new byte[0])) {
                sftp.put(empty, path);
            }
        }
    }

    /** 删除远程文件 */
    public void deleteFile(String path) throws Exception {
        synchronized (sshConn.getSftpLock()) {
            ChannelSftp sftp = sshConn.openSftpChannel();
            sftp.rm(path);
        }
    }

    /** 删除远程目录（递归） */
    public void deleteDirectory(String path) throws Exception {
        synchronized (sshConn.getSftpLock()) {
            ChannelSftp sftp = sshConn.openSftpChannel();
            deleteDirRecursive(sftp, path);
        }
    }

    private void deleteDirRecursive(ChannelSftp sftp, String path) throws Exception {
        @SuppressWarnings("unchecked")
        Vector<ChannelSftp.LsEntry> entries = sftp.ls(path);
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            String full = path + "/" + name;
            if (entry.getAttrs().isDir()) {
                deleteDirRecursive(sftp, full);
            } else {
                sftp.rm(full);
            }
        }
        sftp.rmdir(path);
    }

    /** 重命名/移动文件 */
    public void rename(String oldPath, String newPath) throws Exception {
        synchronized (sshConn.getSftpLock()) {
            ChannelSftp sftp = sshConn.openSftpChannel();
            sftp.rename(oldPath, newPath);
        }
    }

    /** 获取文件属性信息 */
    public String getFileProperties(String path) throws Exception {
        synchronized (sshConn.getSftpLock()) {
            ChannelSftp sftp = sshConn.openSftpChannel();
            SftpATTRS attrs = sftp.stat(path);
            StringBuilder sb = new StringBuilder();
            sb.append("路径: ").append(path).append("\n");
            sb.append("大小: ").append(formatSize(attrs.getSize())).append("\n");
            sb.append("权限: ").append(attrs.getPermissionsString()).append("\n");
            sb.append("UID: ").append(attrs.getUId()).append("\n");
            sb.append("GID: ").append(attrs.getGId()).append("\n");
            sb.append("修改时间: ").append(new Date((long)attrs.getMTime() * 1000)).append("\n");
            sb.append("类型: ").append(attrs.isDir() ? "目录" : (attrs.isLink() ? "链接" : "文件"));
            return sb.toString();
        }
    }

    /** 执行远程命令（用于复制等操作） */
    public String exec(String command) throws Exception {
        return sshConn.executeCommand(command);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * SFTP文件项
     */
    public record SftpFileItem(String name, boolean isDirectory, SftpATTRS attrs, String parentPath) {
        public String getAbsolutePath() {
            return parentPath + (parentPath.endsWith("/") ? "" : "/") + name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
