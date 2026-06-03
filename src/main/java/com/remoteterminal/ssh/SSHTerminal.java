package com.remoteterminal.ssh;

import com.jcraft.jsch.ChannelShell;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * SSH 终端会话 - 管理交互式Shell
 */
public class SSHTerminal implements AutoCloseable {

    private final SSHConnection sshConn;
    private ChannelShell channel;
    private InputStream in;
    private OutputStream out;
    private Thread readThread;
    private volatile boolean running = false;
    private Consumer<String> onDataReceived;
    private Consumer<String> onDisconnected;

    public SSHTerminal(SSHConnection sshConn) {
        this.sshConn = sshConn;
    }

    /** 启动交互式Shell会话 */
    public void start() throws Exception {
        channel = sshConn.openShellChannel();

        // 设置终端类型和环境变量
        channel.setEnv("TERM", "xterm-256color");
        channel.setEnv("LANG", "en_US.UTF-8");

        in = channel.getInputStream();
        out = channel.getOutputStream();

        channel.connect(15000);
        running = true;

        // 启动读取线程
        readThread = new Thread(this::readLoop, "SSH-Terminal-Reader");
        readThread.setDaemon(true);
        readThread.start();
    }

    /** 持续读取SSH输出 */
    private void readLoop() {
        byte[] buffer = new byte[8192];
        try {
            while (running) {
                int available = in.available();
                if (available > 0) {
                    int len = in.read(buffer, 0, Math.min(available, buffer.length));
                    if (len > 0) {
                        String data = new String(buffer, 0, len, StandardCharsets.UTF_8);
                        if (onDataReceived != null) {
                            onDataReceived.accept(data);
                        }
                    }
                } else {
                    int b = in.read();
                    if (b == -1) break;
                    String data = new String(new byte[]{(byte) b}, StandardCharsets.UTF_8);
                    if (onDataReceived != null) {
                        onDataReceived.accept(data);
                    }
                }
            }
        } catch (IOException e) {
            // 连接关闭时忽略
        } finally {
            running = false;
            if (onDisconnected != null) {
                onDisconnected.accept("SSH连接已断开");
            }
        }
    }

    /** 发送数据到远程终端 */
    public void send(String data) {
        if (!running || out == null) return;
        try {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            running = false;
        }
    }

    /** 发送单字符（用于特殊按键） */
    public void sendChar(char c) {
        send(String.valueOf(c));
    }

    /** 调整终端大小 */
    public void resize(int cols, int rows) {
        if (channel != null && channel.isConnected()) {
            channel.setPtySize(cols, rows, cols * 8, rows * 16);
        }
    }

    /** 设置数据接收回调 */
    public void setOnDataReceived(Consumer<String> callback) {
        this.onDataReceived = callback;
    }

    /** 设置断开连接回调 */
    public void setOnDisconnected(Consumer<String> callback) {
        this.onDisconnected = callback;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        if (readThread != null) {
            readThread.interrupt();
        }
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
    }
}
