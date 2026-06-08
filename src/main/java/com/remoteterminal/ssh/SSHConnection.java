package com.remoteterminal.ssh;

import com.jcraft.jsch.*;
import com.remoteterminal.model.ConnectionInfo;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Properties;

/**
 * SSH 连接管理器 - 封装 JSch 会话
 */
public class SSHConnection implements AutoCloseable {

    private final ConnectionInfo info;
    private Session session;
    private ChannelSftp sftpChannel;
    private ChannelShell shellChannel;
    private volatile boolean connected = false;
    private final Object sftpLock = new Object();

    public SSHConnection(ConnectionInfo info) {
        this.info = info;
    }

    /** 建立SSH连接 */
    public void connect() throws Exception {
        JSch jsch = new JSch();
        session = jsch.getSession(info.username(), info.host(), info.port());

        final String password = info.password();
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }

        // 关键：同时实现 UserInfo 和 UIKeyboardInteractive，
        // keyboard-interactive 认证的每次提问都用密码立即应答，绝不挂起
        session.setUserInfo(new AuthHandler(password));

        // 自定义 SocketFactory：强制 TCP connect 3 秒超时，避免 DNS/TCP 阶段卡死
        session.setSocketFactory(new QuickSocketFactory());

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password,keyboard-interactive");
        session.setConfig(config);
        session.setTimeout(3000);
        session.connect(3000);

        connected = true;
    }

    /** 同时实现 UserInfo + UIKeyboardInteractive，让键盘交互认证即时响应 */
    private static class AuthHandler implements UserInfo, UIKeyboardInteractive {
        private final String password;

        AuthHandler(String password) {
            this.password = password == null ? "" : password;
        }

        @Override public String getPassword() { return password; }
        @Override public boolean promptYesNo(String str) { return true; }
        @Override public String getPassphrase() { return password.isEmpty() ? null : password; }
        @Override public boolean promptPassword(String message) { return true; }
        @Override public boolean promptPassphrase(String message) { return true; }
        @Override public void showMessage(String message) {}

        @Override
        public String[] promptKeyboardInteractive(String destination, String name,
                String instruction, String[] prompt, boolean[] echo) {
            // 所有交互提示用密码立即应答，不阻塞等待
            String[] response = new String[prompt.length];
            Arrays.fill(response, password.isEmpty() ? "" : password);
            return response;
        }
    }

    /** 自定义 SocketFactory：强制 TCP 连接 3 秒超时 */
    private static class QuickSocketFactory implements SocketFactory {
        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 3000);
            return socket;
        }
        @Override public InputStream getInputStream(Socket socket) throws IOException {
            return socket.getInputStream();
        }
        @Override public OutputStream getOutputStream(Socket socket) throws IOException {
            return socket.getOutputStream();
        }
    }

    /** 断开SSH连接 */
    @Override
    public void close() {
        connected = false;

        synchronized (sftpLock) {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                sftpChannel.disconnect();
            }
            sftpChannel = null;
        }
        if (shellChannel != null && shellChannel.isConnected()) {
            shellChannel.disconnect();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    /** 打开SFTP通道（线程安全） */
    public ChannelSftp openSftpChannel() throws Exception {
        if (!connected || session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH连接未建立");
        }
        synchronized (sftpLock) {
            if (sftpChannel == null || !sftpChannel.isConnected()) {
                sftpChannel = (ChannelSftp) session.openChannel("sftp");
                sftpChannel.connect(10000);
            }
            return sftpChannel;
        }
    }

    /** 打开Shell通道（用于交互式终端） */
    public ChannelShell openShellChannel() throws Exception {
        if (!connected || session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH连接未建立");
        }
        if (shellChannel != null && shellChannel.isConnected()) {
            return shellChannel;
        }
        shellChannel = (ChannelShell) session.openChannel("shell");
        shellChannel.setPtyType("xterm-256color");
        shellChannel.setPtySize(160, 40, 1024, 768);
        return shellChannel;
    }

    /** 执行单条命令 */
    public String executeCommand(String command) throws Exception {
        if (!connected) throw new IllegalStateException("SSH连接未建立");

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ByteArrayOutputStream err = new ByteArrayOutputStream()) {

            channel.setOutputStream(out);
            channel.setErrStream(err);
            channel.connect(10000);

            // 等待命令执行完成
            while (!channel.isClosed()) {
                Thread.sleep(100);
            }

            String error = err.toString();
            if (!error.isEmpty()) {
                return "[ERROR] " + error;
            }
            return out.toString();
        } finally {
            channel.disconnect();
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public ConnectionInfo getInfo() {
        return info;
    }

    public Session getSession() {
        return session;
    }

    /** 获取 SFTP 锁对象，用于文件管理器线程安全操作 */
    public Object getSftpLock() {
        return sftpLock;
    }
}
