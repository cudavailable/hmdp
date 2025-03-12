package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import static com.hmdp.constant.MessageConstant.*;
import static com.hmdp.constant.RedisConstants.*;

@Component
@Slf4j
public class OrderHandlerListener {

//    @Autowired
    private static IVoucherOrderService proxy; // 确保该服务是线程安全的

    public static void setProxy(IVoucherOrderService proxy) {
        OrderHandlerListener.proxy = proxy;
    }

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 从消息队列中取出一个订单
     * @param jsonStr
     * @return
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "order.queue", durable = "true"),
            exchange = @Exchange(value = "order.direct", type = ExchangeTypes.DIRECT),
            key = ""
    ))
    public void getMsg(String jsonStr){
        VoucherOrder voucherOrder = JSONUtil.toBean(jsonStr, VoucherOrder.class);
        log.debug("voucherOrder from MQ: {}", voucherOrder);
        try {
            handleVoucherOrder(voucherOrder);
        }catch (RuntimeException e){
            log.error("handleVoucherOrder error: {}", e.getMessage());
        }
    }

    /**
     * 处理订单，上锁，执行业务操作
     * @param voucherOrder
     */
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

}
