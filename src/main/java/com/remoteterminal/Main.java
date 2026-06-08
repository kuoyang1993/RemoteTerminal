package com.remoteterminal;

import javafx.application.Application;

/**
 * 主入口 - 绕过模块系统限制启动JavaFX
 * 开启稳定模式：禁用LCD亚像素渲染、强制垂直同步 — 确保界面稳如磐石
 */
public class Main {
    public static void main(String[] args) {
        // 省电/稳定模式 — 禁用 LCD 亚像素抗锯齿，消除字体抖动
        System.setProperty("prism.lcdtext", "false");
        // 强制垂直同步，防止撕裂/晃动感
        System.setProperty("prism.vsync", "true");

        Application.launch(App.class, args);
    }
}
