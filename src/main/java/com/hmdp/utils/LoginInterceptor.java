package com.hmdp.utils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

import cn.hutool.core.bean.BeanUtil;

/**
 * 登录拦截器，用于检查用户是否登录
 * 实现HandlerInterceptor接口
 */
public class LoginInterceptor implements HandlerInterceptor {

  /**
   * 拦截请求，检查用户是否登录
   */
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    // 从session中获取用户信息
    HttpSession session = request.getSession();
    UserDTO user = new UserDTO();
    User sessionUser = (User) session.getAttribute("user");
    BeanUtil.copyProperties(sessionUser, user);
    // 如果存在 放行 并往threadlocal中存入用户信息
    if (sessionUser != null) {
      UserHolder.saveUser(user);// 用工具类UserHolder
      return true; // 放行请求
    }
    // 如果不存在 拦截
    else {
      response.setStatus(401); // 设置状态码为401 未授权
      return false; // 拦截请求
    }
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
      @Nullable Exception ex) throws Exception {
    // 清除用户信息，防止内存泄漏
    UserHolder.removeUser(); // 清除ThreadLocal中的用户信息
  }

}
