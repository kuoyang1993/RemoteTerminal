package com.remoteterminal.model;

/**
 * 连接信息实体 - Java 17 Record
 * savePassword: true=已加密存密码+双击自动登录，false=不存密码+双击弹密码框
 */
public record ConnectionInfo(
        Long id,
        String name,
        String host,
        int port,
        String username,
        String password,
        boolean savePassword,
        int sortOrder
) {
    public ConnectionInfo {
        if (port <= 0 || port > 65535) port = 22;
        if (id == null) id = 0L;
    }

    /** 新建连接（无ID） */
    public ConnectionInfo(String name, String host, int port, String username,
                          String password, boolean savePassword) {
        this(0L, name, host, port, username, password, savePassword, 0);
    }

    @Override
    public String toString() {
        return username + "@" + host + ":" + port;
    }

    /** 判断是否有有效ID（已持久化） */
    public boolean isPersisted() {
        return id != null && id > 0;
    }

    /** 是否已保存密码（加密存储，双击可自动登录） */
    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }
}
