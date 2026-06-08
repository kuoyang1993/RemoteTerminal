package com.remoteterminal.db;

import com.remoteterminal.model.ConnectionInfo;
import com.remoteterminal.util.EncryptionUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 连接信息数据访问对象 — 密码加密存储，save_password 控制是否存密码
 */
public class ConnectionDAO {

    private final DatabaseManager db;

    public ConnectionDAO() {
        this.db = DatabaseManager.getInstance();
    }

    /** 保存新连接 — 密码加密存储 */
    public ConnectionInfo save(ConnectionInfo info) throws Exception {
        String sql = """
                INSERT INTO connections (name, host, port, username, password, save_password, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, info.name());
            ps.setString(2, info.host());
            ps.setInt(3, info.port());
            ps.setString(4, info.username());
            ps.setString(5, info.savePassword() ? EncryptionUtil.encrypt(info.password()) : "");
            ps.setBoolean(6, info.savePassword());
            ps.setInt(7, info.sortOrder());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new ConnectionInfo(id, info.name(), info.host(), info.port(),
                            info.username(), info.password(), info.savePassword(), info.sortOrder());
                }
            }
        }
        throw new RuntimeException("保存连接失败");
    }

    /** 更新连接 */
    public void update(ConnectionInfo info) throws Exception {
        String sql = """
                UPDATE connections SET name=?, host=?, port=?, username=?,
                       password=?, save_password=?, sort_order=?
                WHERE id=?
                """;

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, info.name());
            ps.setString(2, info.host());
            ps.setInt(3, info.port());
            ps.setString(4, info.username());
            ps.setString(5, info.savePassword() ? EncryptionUtil.encrypt(info.password()) : "");
            ps.setBoolean(6, info.savePassword());
            ps.setInt(7, info.sortOrder());
            ps.setLong(8, info.id());
            ps.executeUpdate();
        }
    }

    /** 删除连接 */
    public void delete(long id) throws Exception {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM connections WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** 查询所有连接 — 仅已保存密码的返回密码明文，其余密码为空 */
    public List<ConnectionInfo> findAll() throws Exception {
        List<ConnectionInfo> list = new ArrayList<>();
        String sql = "SELECT * FROM connections ORDER BY sort_order, id";

        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    /** 根据ID查询 */
    public Optional<ConnectionInfo> findById(long id) throws Exception {
        String sql = "SELECT * FROM connections WHERE id=?";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    /** 查询所有已保存密码的连接（原 auto_connect 逻辑） */
    public List<ConnectionInfo> findSavedPassword() throws Exception {
        List<ConnectionInfo> list = new ArrayList<>();
        String sql = "SELECT * FROM connections WHERE save_password=TRUE ORDER BY sort_order, id";

        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private ConnectionInfo mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String name = rs.getString("name");
        String host = rs.getString("host");
        int port = rs.getInt("port");
        String username = rs.getString("username");
        String encryptedPwd = rs.getString("password");
        boolean savePassword = rs.getBoolean("save_password");
        int sortOrder = rs.getInt("sort_order");

        // 解密：仅当 save_password=true 且加密文本非空时解密
        String plainPwd = "";
        if (savePassword && encryptedPwd != null && !encryptedPwd.isBlank()) {
            plainPwd = EncryptionUtil.decrypt(encryptedPwd);
        }

        return new ConnectionInfo(id, name, host, port, username, plainPwd, savePassword, sortOrder);
    }
}
