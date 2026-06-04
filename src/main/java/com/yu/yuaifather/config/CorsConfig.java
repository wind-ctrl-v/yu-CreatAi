package com.yu.yuaifather.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许跨域访问的来源白名单（多个用逗号分隔）。
     * 通过配置项 cors.allowed-origins 注入，默认仅放行本地开发端口。
     * 注意：开启 allowCredentials 时禁止使用 "*"，必须是明确的来源。
     */
    @Value("${cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private List<String> allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 覆盖所有请求
        registry.addMapping("/**")
                // 允许发送 Cookie
                .allowCredentials(true)
                // 仅放行白名单内的明确来源（不能用 *，否则与 allowCredentials 冲突且不安全）
                .allowedOrigins(allowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*")
                // 预检请求缓存时间（秒）
                .maxAge(3600);
    }
}
