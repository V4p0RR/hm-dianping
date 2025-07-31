package com.hmdp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.hmdp.utils.LoginInterceptor;

/**
 * MVC配置类
 * 注册拦截器
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(
        "/user/login",
        "/user/code",
        "/blog/hot",
        "/shop/**",
        "/shop-type/**",
        "/voucher/**",
        "/upload/**");
  }

}
