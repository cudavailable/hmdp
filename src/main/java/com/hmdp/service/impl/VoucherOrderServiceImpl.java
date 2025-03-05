package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

import static com.hmdp.constant.MessageConstant.*;
import static com.hmdp.constant.RedisConstants.LOCK_KEY;
import static com.hmdp.constant.RedisConstants.ORDER_KEY;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherHandler());
    }

    private class VoucherHandler implements Runnable{

        @Override
        public void run() {
            while(true){

                try {
                    // 获取队列中的订单信息(阻塞地)
                    VoucherOrder voucherOrder = blockingQueue.take();
                    // 创建订单信息
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("获取队列中的订单，发生错误", e);
                }

            }
        }
    }

    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        RLock lock = redissonClient.getLock(LOCK_KEY + ORDER_KEY + userId);
        boolean success = lock.tryLock();
        if (!success) { // 如果上锁失败
            log.error(SECKILL_ALREADY_GET);
            return ;
        }
        // 如果上锁成功
        try{
            // 执行业务操作
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 判断是否已买过
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            log.error(SECKILL_ALREADY_GET);
            return;
        }

        // 6.减少库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // 避免超卖
                .update();

        if (!success) {
            // 减少库存失败
            log.error(SECKILL_STOCK_EMPTY);
            return;
        }

        save(voucherOrder);
    }

    public Result seckillOrder(Long voucherId) {
        // 1.根据代金券id查询代金券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2.判断是否存在
        if (voucher == null) {
            return Result.fail(SECKILL_NOT_EXIST);
        }

        // 3.判断活动是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail(SECKILL_NOT_BEGIN);
        }

        // 4.判断活动是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 已结束
            return Result.fail(SECKILL_ALREADY_END);
        }

        Long userId = UserHolder.getUser().getId();

        // 执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();

        // 判断执行是否成功
        if (r != 0) {
            // 执行失败
            return Result.fail(r==1? SECKILL_STOCK_EMPTY : SECKILL_ALREADY_GET);
        }

        // 将订单加入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);

        blockingQueue.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);
    }

//    public Result seckillOrder(Long voucherId) {
//        // 1.根据代金券id查询代金券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        // 2.判断是否存在
//        if (voucher == null) {
//            return Result.fail(SECKILL_NOT_EXIST);
//        }
//
//        // 3.判断活动是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail(SECKILL_NOT_BEGIN);
//        }
//
//        // 4.判断活动是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 已结束
//            return Result.fail(SECKILL_ALREADY_END);
//        }
//
//        // 5.判断是否还有库存
//        Integer stock = voucher.getStock();
//        if (stock < 1) {
//            return Result.fail(SECKILL_STOCK_EMPTY);
//        }
//
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) { // 通过用户id锁住(悲观锁)
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        // 实例化锁对象
////        SimpleRedisLock lock = new SimpleRedisLock(ORDER_KEY + userId, stringRedisTemplate);
//        // 尝试上锁
////        boolean success = lock.tryLock(10);
//        RLock lock = redissonClient.getLock(LOCK_KEY + ORDER_KEY + userId);
//        boolean success = lock.tryLock();
//        if (!success) { // 如果上锁失败
//            return Result.fail(SECKILL_ALREADY_GET);
//        }
//        // 如果上锁成功
//        try{
//            // 执行业务操作
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            // lock.unLock();
//            lock.unlock();
//        }
//    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 判断是否已买过
//        Long userId = UserHolder.getUser().getId();
//        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
//        if (count > 0) {
//            return Result.fail(SECKILL_ALREADY_GET);
//        }
//
//        // 6.减少库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0) // 避免超卖
//                .update();
//
//        if (!success) {
//            // 减少库存失败
//            return Result.fail(SECKILL_STOCK_EMPTY);
//        }
//
//        // 7.更新订单信息
//        VoucherOrder voucherOrder = new VoucherOrder();
//
//        // 7.1订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
//        // 7.2用户id
////        Long userId = UserHolder.getUser().getId();
//        voucherOrder.setUserId(userId);
//
//        // 7.3秒杀券id
//        voucherOrder.setVoucherId(voucherId);
//
//        save(voucherOrder);
//
//        // 8.返回成功
//        return Result.ok(orderId);
//    }
}
