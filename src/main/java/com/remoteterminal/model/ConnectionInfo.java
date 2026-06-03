package com.remoteterminal.model;

/**
 * 连接信息实体 - Java 17 Record
 */
public record ConnectionInfo(
        Long id,
        String name,
        String host,
        int port,
        String username,
        String password,
        boolean autoConnect,
        int sortOrder
) {
    /** 新建连接的紧凑构造器 */
    public ConnectionInfo {
        if (port <= 0 || port > 65535) {
            port = 22;
        }
        if (id == null) {
            id = 0L;
        }
    }

    /** 新建连接（无ID） */
    public ConnectionInfo(String name, String host, int port, String username,
                          String password, boolean autoConnect) {
        this(0L, name, host, port, username, password, autoConnect, 0);
    }

    @Override
    public String toString() {
        return username + "@" + host + ":" + port;
    }

    /** 判断是否有有效ID（已持久化） */
    public boolean isPersisted() {
        return id != null && id > 0;
    }
}
