package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商铺（缓存版）
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 根据传入的店铺更新店铺信息（先操作数据库，再删缓存）
     * @param shop
     * @return
     */
    Result updateByShop(Shop shop);
}
