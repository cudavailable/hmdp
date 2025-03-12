package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MqListener {

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "order.queue", durable = "true"),
            exchange = @Exchange(value = "order.direct", type = ExchangeTypes.DIRECT),
            key = ""
    ))
    public void getMsg(String jsonStr){
        VoucherOrder voucherOrder = JSONUtil.toBean(jsonStr, VoucherOrder.class);
        log.info("voucherOrder from MQ: {}", voucherOrder);
    }
}
