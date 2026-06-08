package com.remoteterminal;

import com.remoteterminal.db.DatabaseManager;
import com.remoteterminal.ui.MainLayout;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.stage.Stage;

/**
 * JavaFX 应用程序主类 — 标准窗口，禁用抗锯齿，固定尺寸
 */
public class App extends Application {

    private MainLayout mainLayout;

    @Override
    public void start(Stage primaryStage) {
        // 初始化数据库
        DatabaseManager.getInstance();

        // 创建主界面
        mainLayout = new MainLayout();

        // 禁用抗锯齿 + 固定 1280x800（无透明、无特效、稳如磐石）
        Scene scene = new Scene(mainLayout, 1280, 800, false, SceneAntialiasing.DISABLED);
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
    }

    @Override
    public void stop() {
        if (mainLayout != null) {
            mainLayout.shutdown();
        }
        DatabaseManager.getInstance().shutdown();
    }
}
