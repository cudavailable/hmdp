package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constant.MessageConstant.SHOP_TYPE_NOT_EXIST;
import static com.hmdp.constant.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.constant.RedisConstants.CACHE_SHOP_TYPE_TTL;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper jacksonObjectMapper;

    public ShopTypeServiceImpl(StringRedisTemplate stringRedisTemplate, ObjectMapper jacksonObjectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jacksonObjectMapper = jacksonObjectMapper;
    }

    /**
     * 查询店铺类型（缓存版）
     * @return
     */
    public Result queryType() {
        String key = CACHE_SHOP_TYPE_KEY;

        // 1.查询缓存中是否存在店铺类型列表
        String jsonList = stringRedisTemplate.opsForValue().get(key);

        // 2.存在，则直接返回
        if(StrUtil.isNotBlank(jsonList)){
            List<ShopType> list = JSONUtil.toList(jsonList, ShopType.class);
            return Result.ok(list);
        }

        // 3.不存在，则查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 4.数据库中不存在，则返回错误
        if(typeList == null || typeList.isEmpty()){
            return Result.fail(SHOP_TYPE_NOT_EXIST);
        }

        // 5.数据库中存在，写回缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        // 6.返回
        return Result.ok(typeList);
    }
}
