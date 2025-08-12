package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.LocalDateTime;

import javax.annotation.Resource;

import org.springframework.aop.framework.AopContext;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
    implements IVoucherOrderService {
  @Resource
  private ISeckillVoucherService seckillVoucherService;
  @Resource
  private RedisIdWorker redisIdWorker;
  @Resource
  private StringRedisTemplate stringRedisTemplate;

  /**
   * 秒杀优惠券
   */
  @Override
  @Transactional
  public Result seckillVoucher(Long voucherId) {
    // 1.查询优惠券
    SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
    // 2.判断秒杀是否开始或者结束
    if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
      return Result.fail("秒杀未开始");
    }
    if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
      return Result.fail("秒杀已结束");
    }
    // 3.判断库存是否充足
    if (seckillVoucher.getStock() < 1) {
      return Result.fail("库存不足");
    }

    // 创建锁对象 先获取用户id
    Long userId = UserHolder.getUser().getId();
    // 限定锁的范围是同一个用户
    SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
    // 尝试获取锁 过期时间10秒
    boolean success = lock.tryLock(Long.valueOf(10));
    // 如果获取锁失败 那么就是重复下单
    if (!success) {
      return Result.fail("同一用户只能下一单!");
    }

    // 创建订单并返回 加上redis分布式锁 防止集群模式下同一用户重复下单
    try {
      // 获取代理对象
      // 因为spring的事务只会管理代理对象，必须获取代理对象才能使用事务
      IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
      return proxy.createVoucherOrder(voucherId);
    } finally {
      // 释放锁
      lock.unlock();
    }
  }

  /**
   * 创建代金券订单
   * 一人一单
   * 
   * @return
   */
  @Transactional
  public Result createVoucherOrder(Long voucherId) {
    // 一人一单
    // 1.查询订单
    Long userId = UserHolder.getUser().getId();
    Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count().longValue();
    // 2.判断是否存在订单
    if (count > 0) {
      // 如果存在订单，说明用户已经下过单了
      return Result.fail("一人一单,请勿重复下单!");
    }

    // 4.扣减库存 加上乐观锁
    boolean success = seckillVoucherService.update()
        .setSql("stock = stock - 1")
        .eq("voucher_id", voucherId)
        .gt("stock", 0) // 乐观锁
        .update();
    if (!success) {
      return Result.fail("扣减库存失败,请重试");
    }
    // 5.创建订单并返回订单id
    VoucherOrder voucherOrder = new VoucherOrder();
    // 生成订单id
    long orderId = redisIdWorker.nextId("order");
    voucherOrder.setId(orderId);
    voucherOrder.setUserId(userId);
    // 设置优惠券id
    voucherOrder.setVoucherId(voucherId);
    // 保存订单
    save(voucherOrder);
    // 6.返回订单id
    return Result.ok(orderId);
  }
}
