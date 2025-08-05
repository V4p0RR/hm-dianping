package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
  @Resource
  private StringRedisTemplate stringRedisTemplate;

  /**
   * 根据id查询商铺信息
   */
  public Result queryShopById(Long id) {
    // 先定义shop的id模版
    String key = RedisConstants.CACHE_SHOP_KEY + id;
    // 先从redis中查询shop 以String存储
    String shopJson = stringRedisTemplate.opsForValue().get(key);

    // 如果是空值，直接返回不存在信息
    if ("".equals(shopJson)) {
      return Result.fail("店铺不存在");
    }
    // 如果存在且不为空值，直接返回shop对象
    if (StrUtil.isNotBlank(shopJson)) {
      Shop shop = JSONUtil.toBean(shopJson, Shop.class);
      return Result.ok(shop);
    }

    // 如果不存在，在数据库中查询
    Shop shop = getById(id);
    // 从数据库中查到了，直接返回 并存入redis
    if (shop != null) {
      // 将shop对象转换为JSON字符串
      String shopStr = JSONUtil.toJsonStr(shop);
      // 将shop存入redis 设置过期时间30分钟
      stringRedisTemplate.opsForValue().set(key, shopStr, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
      return Result.ok(shop);
    }
    // 如果数据库没查到，向redis写入空值，防止缓存穿透
    stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    // 返回不存在信息
    return Result.fail("店铺不存在");
  }

  /**
   * 更新商铺信息
   */
  @Transactional
  public Result update(Shop shop) {
    Long id = shop.getId();
    // 判断id是不是为空
    if (id == null) {
      return Result.fail("店铺id不能为空");
    }
    // 先更新数据库
    updateById(shop);
    // 再删除缓存
    stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
    return Result.ok();
  }

}
