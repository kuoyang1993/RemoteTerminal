package com.remoteterminal.db;

import com.remoteterminal.model.ConnectionInfo;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 连接信息数据访问对象
 */
public class ConnectionDAO {

    private final DatabaseManager db;

    public ConnectionDAO() {
        this.db = DatabaseManager.getInstance();
    }

    /** 保存新连接 */
    public ConnectionInfo save(ConnectionInfo info) throws Exception {
        String sql = """
                INSERT INTO connections (name, host, port, username, password, auto_connect, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, info.name());
            ps.setString(2, info.host());
            ps.setInt(3, info.port());
            ps.setString(4, info.username());
            ps.setString(5, info.password());
            ps.setBoolean(6, info.autoConnect());
            ps.setInt(7, info.sortOrder());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    return new ConnectionInfo(id, info.name(), info.host(), info.port(),
                            info.username(), info.password(), info.autoConnect(), info.sortOrder());
                }
            }
        }
        throw new RuntimeException("保存连接失败");
    }

    /** 更新连接 */
    public void update(ConnectionInfo info) throws Exception {
        String sql = """
                UPDATE connections SET name=?, host=?, port=?, username=?,
                       password=?, auto_connect=?, sort_order=?
                WHERE id=?
                """;

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, info.name());
            ps.setString(2, info.host());
            ps.setInt(3, info.port());
            ps.setString(4, info.username());
            ps.setString(5, info.password());
            ps.setBoolean(6, info.autoConnect());
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

    /** 查询所有连接 */
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

    /** 查询所有自动连接的连接 */
    public List<ConnectionInfo> findAutoConnect() throws Exception {
        List<ConnectionInfo> list = new ArrayList<>();
        String sql = "SELECT * FROM connections WHERE auto_connect=TRUE ORDER BY sort_order, id";

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
        return new ConnectionInfo(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("host"),
                rs.getInt("port"),
                rs.getString("username"),
                rs.getString("password"),
                rs.getBoolean("auto_connect"),
                rs.getInt("sort_order")
        );
    }
}
