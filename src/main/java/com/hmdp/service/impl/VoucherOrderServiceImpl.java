package com.hmdp.service.impl;

import com.hmdp.dto.Result;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;

import cn.hutool.core.bean.BeanUtil;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.Duration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
    implements IVoucherOrderService {
  @Resource
  private ISeckillVoucherService seckillVoucherService;
  @Resource
  private RedisIdWorker redisIdWorker;
  @Resource
  private StringRedisTemplate stringRedisTemplate;
  @Resource
  private RedissonClient redissonClient;

  // 加载lua脚本
  private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
  static {
    SECKILL_SCRIPT = new DefaultRedisScript<>();
    SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    SECKILL_SCRIPT.setResultType(Long.class);
  }
  // 创建一个单线程的线程池 用于处理秒杀订单
  private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

  /**
   * 初始化方法
   * 在应用启动时，提交一个任务到线程池中，创建秒杀订单
   */
  @PostConstruct
  private void init() {
    SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
  }

  private class VoucherOrderHandler implements Runnable {

    @Override
    public void run() {
      while (true) {
        try {
          // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
          @SuppressWarnings("unchecked")
          List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
              Consumer.from("g1", "c1"),
              StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
              StreamOffset.create("stream.orders", ReadOffset.lastConsumed()));
          // 2.判断订单信息是否为空
          if (list == null || list.isEmpty()) {
            // 如果为null，说明没有消息，继续下一次循环
            continue;
          }
          // 解析数据
          MapRecord<String, Object, Object> record = list.get(0);
          Map<Object, Object> value = record.getValue();
          VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
          // 3.创建订单
          createVoucherOrder(voucherOrder);
          // 4.确认消息 XACK
          stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
        } catch (Exception e) {
          log.error("处理订单异常", e);
          handlePendingList();
        }
      }
    }

    @SuppressWarnings("unchecked")
    private void handlePendingList() {
      while (true) {
        try {
          // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
          List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
              Consumer.from("g1", "c1"),
              StreamReadOptions.empty().count(1),
              StreamOffset.create("stream.orders", ReadOffset.from("0")));
          // 2.判断订单信息是否为空
          if (list == null || list.isEmpty()) {
            // 如果为null，说明没有异常消息，结束循环
            break;
          }
          // 解析数据
          MapRecord<String, Object, Object> record = list.get(0);
          Map<Object, Object> value = record.getValue();
          VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
          // 3.创建订单
          createVoucherOrder(voucherOrder);
          // 4.确认消息 XACK
          stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
        } catch (Exception e) {
          log.error("处理订单异常", e);
        }
      }
    }
  }

  /**
   * 秒杀优惠券 优化版
   */
  @Override
  public Result seckillVoucher(Long voucherId) {
    // 先获取userId和orderId
    Long userId = UserHolder.getUser().getId();
    long orderId = redisIdWorker.nextId("order");
    // 1.执行lua脚本 判断购买资格
    Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
        Collections.emptyList(),
        voucherId.toString(),
        userId.toString(),
        String.valueOf(orderId));
    // 返回非0，没有购买资格
    int r = result.intValue();
    if (r != 0) {
      // 如果返回值不为0，说明没有购买资格
      return Result.fail(r == 1 ? "库存不足" : "一人一单，请勿重复下单！");
    }
    // 返回值为0,可购买 lua脚本会把下单信息传入消息队列

    // 返回订单id
    return Result.ok(orderId);
  }

  /**
   * 创建代金券订单
   * 
   * @param voucherOrder
   */
  public void createVoucherOrder(VoucherOrder voucherOrder) {
    Long userId = voucherOrder.getUserId();
    Long voucherId = voucherOrder.getVoucherId();
    // 创建锁对象
    RLock redisLock = redissonClient.getLock("lock:order:" + userId);
    // 尝试获取锁
    boolean isLock = redisLock.tryLock();
    // 判断
    if (!isLock) {
      // 获取锁失败，直接返回失败或者重试
      log.error("不允许重复下单！");
      return;
    }

    try {
      // 5.1.查询订单
      int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
      // 5.2.判断是否存在
      if (count > 0) {
        // 用户已经购买过了
        log.error("不允许重复下单！");
        return;
      }

      // 6.扣减库存
      boolean success = seckillVoucherService.update()
          .setSql("stock = stock - 1") // set stock = stock - 1
          .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
          .update();
      if (!success) {
        // 扣减失败
        log.error("库存不足！");
        return;
      }

      // 7.创建订单
      save(voucherOrder);
    } finally {
      // 释放锁
      redisLock.unlock();
    }
  }

  /**
   * 秒杀优惠券 老版
   */
  /*
   * @Override
   * public Result seckillVoucher(Long voucherId) {
   * // 1.查询优惠券
   * SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
   * // 2.判断秒杀是否开始或者结束
   * if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
   * return Result.fail("秒杀未开始");
   * }
   * if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
   * return Result.fail("秒杀已结束");
   * }
   * // 3.判断库存是否充足
   * if (seckillVoucher.getStock() < 1) {
   * return Result.fail("库存不足");
   * }
   * 
   * // 先获取用户id
   * Long userId = UserHolder.getUser().getId();
   * // 创建锁对象 限定锁的范围是同一个用户
   * RLock lock = redissonClient.getLock("lock:order:" + userId);
   * // 尝试获取锁
   * boolean success = lock.tryLock();
   * // 如果获取锁失败 那么就是重复下单
   * if (!success) {
   * return Result.fail("同一用户只能下一单!");
   * }
   * 
   * // 创建订单并返回 加上redis分布式锁 防止集群模式下同一用户重复下单
   * try {
   * // 获取代理对象
   * // 因为spring的事务只会管理代理对象，必须获取代理对象才能使用事务
   * IVoucherOrderService proxy = (IVoucherOrderService)
   * AopContext.currentProxy();
   * return proxy.createVoucherOrder(voucherId);
   * } finally {
   * // 释放锁
   * lock.unlock();
   * }
   * }
   */
  /**
   * 创建代金券订单
   * 一人一单
   *
   * @return
   */
  /*
   * @Transactional
   * public Result createVoucherOrder(Long voucherId) {
   * // 一人一单
   * // 1.查询订单
   * Long userId = UserHolder.getUser().getId();
   * Long count = query().eq("user_id", userId).eq("voucher_id",
   * voucherId).count().longValue();
   * // 2.判断是否存在订单
   * if (count > 0) {
   * // 如果存在订单，说明用户已经下过单了
   * return Result.fail("一人一单,请勿重复下单!");
   * }
   * 
   * // 4.扣减库存 加上乐观锁
   * boolean success = seckillVoucherService.update()
   * .setSql("stock = stock - 1")
   * .eq("voucher_id", voucherId)
   * .gt("stock", 0) // 乐观锁
   * .update();
   * if (!success) {
   * return Result.fail("扣减库存失败,请重试");
   * }
   * // 5.创建订单并返回订单id
   * VoucherOrder voucherOrder = new VoucherOrder();
   * // 生成订单id
   * long orderId = redisIdWorker.nextId("order");
   * voucherOrder.setId(orderId);
   * voucherOrder.setUserId(userId);
   * // 设置优惠券id
   * voucherOrder.setVoucherId(voucherId);
   * // 保存订单
   * save(voucherOrder);
   * // 6.返回订单id
   * return Result.ok(orderId);
   * }
   */

}
