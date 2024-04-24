package com.lyh.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lyh.dto.Result;
import com.lyh.entity.VoucherOrder;
import com.lyh.mapper.VoucherOrderMapper;
import com.lyh.service.ISeckillVoucherService;
import com.lyh.service.IVoucherOrderService;
import com.lyh.utils.RedisIdWorker;
import com.lyh.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务实现类
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    // TODO 秒杀,分布式锁实现，异步实现

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 数据库部分处理订单
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> readList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断消息获取是否成功
                    if (readList == null || readList.isEmpty()) {
                        // 获取失败，说明没有消息继续下次循环
                        continue;
                    }
                    // 如果获取成功，可以下单
                    // 取出订单,String为消息的id，一串随机数
                    MapRecord<String, Object, Object> record = readList.get(0);
                    // 拿到流中的k-v
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 处理订单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            // 确保异常导致的订单一定能够进行处理
            while (true) {
                try {
                    // 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1  STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> readList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断消息获取是否成功
                    if (readList == null || readList.isEmpty()) {
                        // 获取失败，说明pendinglist没有异常消息，结束
                        break;
                    }
                    // 如果获取成功，可以下单
                    // 取出订单,String为消息的id，一串随机数
                    MapRecord<String, Object, Object> record = readList.get(0);
                    // 拿到流中的k-v
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pendinglist订单异常", e);
                }
            }
        }

    }

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    log.error("处理订单异常" + e);
//                }
//            }
//        }
//
//    }

    // 异步处理数据库
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            // 操作数据库
            proxy.createVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("处理订单异常" + e);
        }
    }

    private IVoucherOrderService proxy;

    // redis部分处理秒杀订单
    // 业务接口
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        // 执行lua脚本
        Long ret = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId));
        // 判断结果是否为0，即是否可以操作数据库
        int r = ret.intValue();
        if (r != 0) {
            // 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);
    }

    /**
     * 数据库部分创建秒杀订单
     * 异步调用
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();
        // 这里锁的对象的字符串常量池里的，也就是说给相同用户id的字符串上锁了

        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经参与过一次活动了！");
            return;
        }

        // 扣减库存
        // 加乐观锁,看库存是不是之前那个库存，是才修改
//        seckillVoucherService.lambdaUpdate().set(SeckillVoucher::getStock, stock).eq(SeckillVoucher::getVoucherId, voucherId);
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            // 秒杀失败
            log.error("秒杀失败!");
            return;

        }
        // 保存订单
        save(voucherOrder);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 执行lua脚本
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        Long ret = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString());
        // 判断结果是否为0，即是否可以操作数据库
        int r = ret.intValue();
        if (r != 0) {
            // 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 为0，有购买资格，把下单信息保存到阻塞队列
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 保存到阻塞队列中
        orderTasks.add(voucherOrder);
        // 返回订单id
        return Result.ok(orderId);
    }*/


    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 开始时间在当前时间之后
            return Result.fail("秒杀尚未开始");
        }
        // 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 结束时间在当前时间之前
            return Result.fail("秒杀已经结束");
        }
        // 判断库存
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 锁用户id
        // 这里锁应该加载这里，而不是下面那个方法内部
        // 如果锁在下面那个方法内部，就还是会导致并发问题的出现
        // 因为事物还没提交的时候就释放锁了，会让其他线程有机可乘

        // 创建分布式锁对象
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("order:" + userId);
        // 尝试获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，返回错误信息或重试
            return Result.fail("不允许重复下单");
        }

        // 这里必须拿到这个类的代理对象才行，这个类的对象本身是没有事物处理功能的
        // 获取事物代理对象
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }

    }*/

}
