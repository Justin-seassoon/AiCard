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
        return "sim".equals(role) ? "redirect:/simulator.html" : "redirect:/index.html";
    }
}
