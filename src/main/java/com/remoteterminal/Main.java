package com.remoteterminal;

import javafx.application.Application;

/**
 * 主入口 - 绕过模块系统限制启动JavaFX
 */
public class Main {
    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
