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
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;

import static com.hmdp.constant.MessageConstant.*;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

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

        // 5.判断是否还有库存
        Integer stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail(SECKILL_STOCK_EMPTY);
        }

        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) { // 通过用户id锁住(悲观锁)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 判断是否已买过
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            return Result.fail(SECKILL_ALREADY_GET);
        }

        // 6.减少库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // 避免超卖
                .update();

        if (!success) {
            // 减少库存失败
            return Result.fail(SECKILL_STOCK_EMPTY);
        }

        // 7.更新订单信息
        VoucherOrder voucherOrder = new VoucherOrder();

        // 7.1订单id
        long orderId = redisIdWorker.nextId("Order");
        voucherOrder.setId(orderId);

        // 7.2用户id
//        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);

        // 7.3秒杀券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);

        // 8.返回成功
        return Result.ok(orderId);
    }
}
