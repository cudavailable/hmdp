package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherOrderService extends IService<VoucherOrder> {

    public Result seckillOrder(Long voucherId);

//    public Result createVoucherOrder(VoucherOrder voucherId);

    public void createVoucherOrder(VoucherOrder voucherOrder);
}
