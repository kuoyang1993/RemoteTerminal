package com.remoteterminal.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 数据库管理器 - 使用H2嵌入式数据库 + HikariCP连接池
 */
public final class DatabaseManager {

    private static final String DB_URL = "jdbc:h2:file:./data/remoteterminal;AUTO_SERVER=TRUE";
    private static final String DB_USER = "sa";
    private static final String DB_PASS = "";

    private static volatile DatabaseManager instance;
    private final HikariDataSource dataSource;

    private DatabaseManager() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DB_URL);
        config.setUsername(DB_USER);
        config.setPassword(DB_PASS);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setIdleTimeout(30000);
        config.setConnectionTimeout(10000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(config);
        initDatabase();
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager();
                }
            }
        }
        return instance;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public Connection getConnection() throws Exception {
        return dataSource.getConnection();
    }

    private void initDatabase() {
        // 先创建表（如果是全新数据库）
        String createSql = """
                CREATE TABLE IF NOT EXISTS connections (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    host VARCHAR(255) NOT NULL,
                    port INT DEFAULT 22,
                    username VARCHAR(255) NOT NULL,
                    password VARCHAR(1024),
                    save_password BOOLEAN DEFAULT FALSE,
                    sort_order INT DEFAULT 0
                )
                """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);

            // 迁移：旧字段 auto_connect → save_password
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "CONNECTIONS", "AUTO_CONNECT")) {
                if (rs.next()) {
                    // 旧字段存在，执行迁移
                    stmt.execute("ALTER TABLE connections ADD COLUMN IF NOT EXISTS save_password BOOLEAN DEFAULT FALSE");
                    stmt.execute("UPDATE connections SET save_password = auto_connect WHERE save_password IS NULL OR save_password = FALSE");
                    stmt.execute("ALTER TABLE connections ALTER COLUMN password VARCHAR(1024)");
                }
            } catch (Exception ignored) {
                // 迁移失败不影响启动
            }
        } catch (Exception e) {
            throw new RuntimeException("数据库初始化失败", e);
        }
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
