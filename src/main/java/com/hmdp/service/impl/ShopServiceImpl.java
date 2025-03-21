package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constant.SystemConstants;
import com.hmdp.controller.ShopController;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constant.MessageConstant.SHOP_NOT_EXIST;
import static com.hmdp.constant.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ShopController shopController;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺（缓存版）
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);

        // 封装解决缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 封装解决缓存击穿
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail(SHOP_NOT_EXIST);
        }

        // 返回商铺信息
        return Result.ok(shop);
    }

//    /**
//     * 逻辑过期，解决缓存击穿
//     * @param id
//     * @param expiredSeconds
//     */
//    public void saveShop2Redis(Long id, Long expiredSeconds) {
//        Shop shop = getById(id);
//
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSeconds));
//
//        String key = CACHE_SHOP_KEY+id;
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
//    }
//
//    public Shop queryWithMutex(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        // 1.查询缓存中是否存在该id的商铺
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 2.存在，则直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//
//        // 判断是否是缓存的空字符
//        if (shopJson != null) {
//            return null;
//        }
//
//        // 3.缓存重建
//        String lock = LOCK_SHOP_KEY + id;
//
//        // 3.1加互斥锁
//        Shop shop;
//        try {
//            while (!tryLock(lock)) { // 3.2加锁失败，休眠然后重新查询
//                Thread.sleep(50);
//            }
//
//            // 3.3加锁成功，继续后续操作
//            // 先Double Check
//            String checkJson = stringRedisTemplate.opsForValue().get(key);
//            if (StrUtil.isNotBlank(checkJson)) {
//                return JSONUtil.toBean(checkJson, Shop.class);
//            }
//
//            // 判断是否是缓存的空字符
//            if (checkJson != null) {
//                return null;
//            }
//
//            // 查询数据库
//            shop = getById(id);
//
//            if (shop == null) {
//                // 缓存空值(防止缓存穿透)
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//
//                // 4.不存在，则返回错误
//                return null;
//            }
//
//            // 5.存在，则写回缓存
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 6.释放锁
//            unlock(lock);
//        }
//
//        // 7.返回商铺信息
//        return shop;
//    }
//
//
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//
//    public Shop queryWithLogicalExpire(Long id) {
//        String key = CACHE_SHOP_KEY+id;
//        // 1.查询缓存中是否存在该id的商铺
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if(StrUtil.isBlank(shopJson)) {
//            // 2.如果缓存中不存在，则直接返回
//            return null;
//        }
//
//        // 3.1存在，反序列化shopJson
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        JSONObject data = (JSONObject) redisData.getData();
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//
//        // 3.2判断是否逻辑过期
//        LocalDateTime expireTime = redisData.getExpireTime();
//
//        if(expireTime.isAfter(LocalDateTime.now())){
//            // 3.3若逻辑未过期，直接返回
//            return (Shop) redisData.getData();
//        }
//
//        // 3.4若逻辑过期，则缓存重建
//
//        // 3.5加互斥锁
//        String lock = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lock);
//
//        if(isLock){
//            // 3.6加锁成功，新开一个线程缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    // 缓存重建
//                    saveShop2Redis(id, 20L);
//                }catch (RuntimeException e){
//                    throw new RuntimeException(e);
//                }finally {
//                    // 释放锁
//                    unlock(lock);
//                }
//            });
//        }
//
//        // 3.7返回过期数据
//        return (Shop) redisData.getData();
//    }
//
//    /**
//     * try to lock
//     * @param key
//     * @return
//     */
//    private boolean tryLock(String key){
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, LOCK_VALUE, LOCK_SHOP_TTL, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    private void unlock(String key){
//        stringRedisTemplate.delete(key);
//    }


    /**
     * 根据传入的店铺更新店铺信息（先操作数据库，再删缓存）
     * @param shop
     * @return
     */
    @Transactional
    public Result updateByShop(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail(SHOP_NOT_EXIST);
        }

        String key = CACHE_SHOP_KEY+id;
        // 1.更新数据库
        updateById(shop);

        // 2.删除缓存
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    /**
     * 根据店铺类型查询商铺
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 判断是否需要根据地理坐标进行查询
        if (x == null || y==null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 以下是根据地理坐标查询
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 定义坐标圆心
        Point center = new Point(x, y);

        // 定义搜索半径
        Distance distance = new Distance(5, RedisGeoCommands.DistanceUnit.KILOMETERS);

        Circle circle = new Circle(center, distance);

        // redis的key
        String key = SHOP_GEO_KEY + typeId;

        // 查询距离当前地理位置5km内的商铺，距离由近到远
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                key,
                circle,
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending().limit(end)
        );

        if (results == null || results.getContent().isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(); // {id}
        Map<String, Distance> map = new HashMap<>(); // <id, distance>
        list.stream().skip(from).forEach(
            shop -> {
                String idStr = shop.getContent().getName();
                ids.add(Long.valueOf(idStr));

                Distance dis = shop.getDistance();
                map.put(idStr, dis);
            }
        );

        // 根据shop id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD (id, " + idStr + ")").list();

        // 为每一个返回的商店添加距离信息
        shops.forEach(shop -> {
            Double dis = map.get(shop.getId().toString()).getValue();
            shop.setDistance(dis);
        });

        return Result.ok(shops);
    }
}
