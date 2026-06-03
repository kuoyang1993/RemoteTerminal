package com.remoteterminal;

import com.remoteterminal.db.ConnectionDAO;
import com.remoteterminal.db.DatabaseManager;
import com.remoteterminal.model.ConnectionInfo;
import com.remoteterminal.ui.MainLayout;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.List;

/**
 * JavaFX 应用程序主类
 */
public class App extends Application {

    private MainLayout mainLayout;

    @Override
    public void start(Stage primaryStage) {
        // 初始化数据库
        DatabaseManager.getInstance();

        // 创建主界面
        mainLayout = new MainLayout();

        Scene scene = new Scene(mainLayout, 1280, 800);
        scene.getStylesheets().add(
                getClass().getResource("/com/remoteterminal/styles.css").toExternalForm()
        );

        primaryStage.setTitle("RemoteTerminal - 远程终端工具");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);

        // 窗口关闭时清理资源
        primaryStage.setOnCloseRequest(e -> {
            Platform.exit();
        });

        primaryStage.show();

        // 自动连接已保存的连接
        autoConnect();
    }

    private void autoConnect() {
        new Thread(() -> {
            try {
                ConnectionDAO dao = new ConnectionDAO();
                List<ConnectionInfo> autoList = dao.findAutoConnect();
                for (ConnectionInfo info : autoList) {
                    Platform.runLater(() -> mainLayout.connectTo(info));
                    Thread.sleep(500); // 错开连接
                }
            } catch (Exception e) {
                // 自动连接失败不影响主界面
                e.printStackTrace();
            }
        }, "AutoConnect").start();
    }

    @Override
    public void stop() {
        if (mainLayout != null) {
            mainLayout.shutdown();
        }
        DatabaseManager.getInstance().shutdown();
    }
}
