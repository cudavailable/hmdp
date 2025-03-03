package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.constant.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // TTL过期
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    // 逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 解决缓存穿透的查询
    public <R, ID> R queryWithPassThrough(String prefixKey, ID id, Class<R> clazz, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = prefixKey + id;
        String json = stringRedisTemplate.opsForValue().get(key); // 返回Redis中查询的Json结果

        if(StrUtil.isNotBlank(json)){ // 结果已缓存，且不为空串
            return JSONUtil.toBean(json, clazz);
        }

        if(json != null){ // 缓存命中空串
            return null;
        }

        // 查询数据库
        R r = dbFallback.apply(id);
        if(r == null){ // 缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        this.set(key, r, time, timeUnit);
        return r; // 返回数据库查询结果
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 缓存击穿
    public <R, ID> R queryWithLogicalExpire(String prefixKey, String prefixLock, ID id, Class<R> clazz, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = prefixKey+id;
        // 1.查询缓存中是否存在该id的商铺
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)) {
            // 2.如果缓存中不存在，则直接返回
            return null;
        }

        // 3.1存在，反序列化json
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, clazz);

        // 3.2判断是否逻辑过期
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            // 3.3若逻辑未过期，直接返回
            return r;
        }

        // 3.4若逻辑过期，则缓存重建

        // 3.5加互斥锁
        String lock = prefixLock + id;
        boolean isLock = tryLock(lock);

        if(isLock){
            // 3.6加锁成功，新开一个线程缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 缓存重建
                    this.setWithLogicalExpire(key, r1, time, timeUnit);
                }catch (RuntimeException e){
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lock);
                }
            });
        }

        // 3.7返回过期数据
        return r;
    }

    /**
     * try to lock
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, LOCK_VALUE, LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
