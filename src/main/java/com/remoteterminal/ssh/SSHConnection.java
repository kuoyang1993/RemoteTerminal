package com.remoteterminal.ssh;

import com.jcraft.jsch.*;
import com.remoteterminal.model.ConnectionInfo;

import java.io.*;
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

        String password = info.password();
        if (password != null && !password.isEmpty()) {
            session.setPassword(password);
        }

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password,keyboard-interactive");
        session.setConfig(config);
        session.setTimeout(15000);
        session.connect(10000);

        connected = true;
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
