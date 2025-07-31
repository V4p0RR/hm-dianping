package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

import javax.servlet.http.HttpSession;

import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

  /**
   * 发送手机验证码
   * 
   * @param phone   手机号
   * @param session HttpSession对象，用于存储验证码
   */
  @Override
  public Result sendCode(String phone, HttpSession session) {
    // 校验手机号
    if (RegexUtils.isPhoneInvalid(phone)) {
      return Result.fail("手机号格式错误");
    }
    // 生成验证码
    String code = RandomUtil.randomNumbers(6);

    // 保存验证码到session
    session.setAttribute("code", code);

    // 发送验证码到手机
    log.debug("success sending code: {}", code);

    // 返回结果
    return Result.ok();
  }

  /**
   * 登录功能
   * 
   * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
   * @param session   HttpSession对象，用于存储用户信息
   */
  @Override
  public Result login(LoginFormDTO loginForm, HttpSession session) {
    // 先校验手机号
    if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
      return Result.fail("手机号格式错误");
    }
    // 判断验证码是否为空
    if (loginForm.getCode() != null) {
      // 如果验证码不为空 用验证码登录
      // 校验验证码
      Object cacheCode = session.getAttribute("code");
      if (cacheCode == null || !cacheCode.toString().equals(loginForm.getCode())) {
        return Result.fail("验证码错误");
      }
      // 检验用户是否存在数据库中
      User user = query().eq("phone", loginForm.getPhone()).one();
      if (user == null) {
        // 如果用户不存在，创建新用户
        user = new User();
        user.setPhone(loginForm.getPhone());
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10)); // 随机昵称
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        // 保存用户到数据库
        save(user);
        // 保存用户信息到session中
        session.setAttribute("user", user);
      }
      // 如果用户存在，直接保存用户信息到session中
      session.setAttribute("user", user);
      log.info("login success, user: {}", BeanUtil.copyProperties(user, UserDTO.class));
      return Result.ok();
    } else {
      // TODO 如果验证码为空，用密码登录

    }

    return Result.fail("功能未完成");
  }

}
