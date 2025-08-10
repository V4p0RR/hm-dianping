package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.LocalDateTime;

import javax.annotation.Resource;

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
    // 获取用户id
    Long userId = UserHolder.getUser().getId();
    voucherOrder.setUserId(userId);
    // 设置优惠券id
    voucherOrder.setVoucherId(voucherId);
    // 保存订单
    save(voucherOrder);
    // 6.返回订单id
    return Result.ok(orderId);
  }
}
