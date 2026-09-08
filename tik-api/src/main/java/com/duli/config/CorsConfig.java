package com.duli.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 允许所有接口路由
                .allowedOriginPatterns("*") // 允许所有前端域名/端口访问（Spring Boot 2.4以上用这个）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的请求方式
                .allowCredentials(true) // 允许前端携带凭证（比如 Cookie 或 Session）
                .allowedHeaders("*") // 允许前端带所有的请求头
                .maxAge(3600); // 跨域允许时间（1小时内不再发起预检请求）
    }
}