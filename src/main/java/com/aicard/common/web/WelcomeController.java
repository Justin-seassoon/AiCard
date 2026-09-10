package com.aicard.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 欢迎页路由：按环境变量 APP_ROLE 决定根路径 / 跳转到哪个页面。
 * 用于「3 端口 3 页面」——gateway(联调)/kb(知识库) 跳 index.html，sim(模拟器) 跳 simulator.html。
 */
@Controller
public class WelcomeController {

    private final String role;

    public WelcomeController(@Value("${app.role:kb}") String role) {
        this.role = role;
    }

    @GetMapping("/")
    public String index() {
        // 用 forward 而非 redirect：避免反向代理下 302 生成错误的 scheme/host/端口
        return switch (role) {
            case "sim" -> "forward:/simulator.html";
            case "gateway" -> "forward:/gateway.html";
            default -> "forward:/index.html";
        };
    }
}
