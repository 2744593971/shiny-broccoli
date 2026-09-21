package com.duli.config;

import com.duli.interceptor.JwtInterceptor;
import com.duli.interceptor.SmsInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Bean
    public SmsInterceptor smsInterceptor() {
        return new SmsInterceptor();
    }

    // 注入 JWT 拦截器
    @Bean
    public JwtInterceptor jwtInterceptor() {
        return new JwtInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // 1. 注册防刷拦截器
        registry.addInterceptor(smsInterceptor())
                .addPathPatterns("/passport/getSMSCode");

        // 2. 注册 JWT 拦截器
        registry.addInterceptor(jwtInterceptor())
                // 拦截所有请求（你可以根据以后的业务需求改写拦截范围，比如 /userInfo/**）
                .addPathPatterns("/**")
                // 但是要排除掉登录、注册、获取验证码等不需要权限的接口！
                .excludePathPatterns("/passport/getSMSCode")
                .excludePathPatterns("/passport/login")
                // 排除掉 Knife4j 的文档接口，否则你看不到接口文档了！
                .excludePathPatterns("/doc.html")
                .excludePathPatterns("/webjars/**")
                .excludePathPatterns("/swagger-resources/**")
                .excludePathPatterns("/v2/**");
        // 公开内容也经过 JWT 拦截器，可匿名访问，但个性化身份必须验证 Token。
    }
}
