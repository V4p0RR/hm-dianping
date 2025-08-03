package com.hmdp.config;

import javax.annotation.Resource;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;

/**
 * MVC配置类
 * 注册拦截器
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {
  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(
        "/user/login",
        "/user/code",
        "/blog/hot",
        "/shop/**",
        "/shop-type/**",
        "/voucher/**",
        "/upload/**").order(1);
    registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);// 默认拦截所有请求 调节顺序，第一个执行
  }

}
