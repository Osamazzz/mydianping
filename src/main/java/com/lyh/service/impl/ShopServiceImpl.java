package com.lyh.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lyh.dto.Result;
import com.lyh.entity.Shop;
import com.lyh.mapper.ShopMapper;
import com.lyh.service.IShopService;
import com.lyh.utils.CacheClient;
import com.lyh.utils.RedisConstants;
import com.lyh.utils.SystemConstants;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 服务实现类
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    // TODO 重点复习

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        // TODO 布隆过滤器
//        BloomFilter<Long> filter = BloomFilter.create(Funnels.longFunnel(), 200, 0.01);
//        RBloomFilter<Object> shopList = redissonClient.getBloomFilter("shopList");
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        // 用逻辑过期解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById,
//                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 无论如何，总要返回店铺信息
        return shop == null ? Result.fail("店铺不存在") : Result.ok(shop);
    }

    /**
     * 使用互斥锁解决缓存击穿
     */
    // TODO 互斥锁
    /*public Shop queryWithMutex(Long id) {
        String key = "cache:shop:" + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在则直接返回店铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // shopjson若是返回空字符串值不会进入上面的if语句中，还要在此处判断是否是空字符串
        if (shopJson != null) {
            // 查到一个穿透的结果
            return null;
        }
        // 实现缓存重建
        // 获取互斥锁
        String lockKey = "lock:shop:" + id;
        // 判断是否成功获取锁
        Shop shop;
        try {
            if (!tryLock(lockKey)) {
                // 失败则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 成功则根据id查询数据库
            shop = getById(id);
            // 模拟重建的延迟
            Thread.sleep(200);
            if (shop == null) {
                // 还不存在，处理错误信息
                // 将空字符串返回redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            unlock(lockKey);
        }
        // 无论如何，总要返回店铺信息
        return shop;
    }*/

    /*public Shop queryWithPassThrough(Long id) {
        String key = "cache:shop:" + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在则直接返回店铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // shopjson若是返回空字符串值不会进入上面的if语句中，还要在此处判断是否是空字符串
        if (shopJson != null) {
            return null;
        }
        // 不存在，根据id查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            // 还不存在，处理错误信息
            // 将空字符串返回redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 无论如何，总要返回店铺信息
        return shop;
    }*/

    /*private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    // 逻辑过期，处理热点key数据导致缓存击穿的问题
    public Shop queryWithLogicalExpire(Long id) {
        String key = "cache:shop:" + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 未存在则直接返回null
            // 说明不是热点key，热点key已经提前加载过了
            return null;
        }
        // 命中，需要吧json反序列为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期,看看是不是在当前时间之后
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期则直接返回
            return shop;
        }
        // 已过期需要重建缓存
        // 缓存重建
        // 获取互斥锁
        String lockKey = "lock:shop:" + id;
        // 判断是否获取锁成功
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 成功，开启独立线程，实现缓存重建
            // 使用线程池
            CACHE_REBUILD_EXECUTOR.submit(()->{
                // 重建缓存
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 返回过期的商铺信息

        // 不存在，根据id查询数据库
//        Shop shop = getById(id);

        // 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 无论如何，总要返回店铺信息
        return shop;
    }*/

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 先更新数据库
        updateById(shop);
        // 删除缓存
        Boolean isDelete = stringRedisTemplate.delete("cache:shop:" + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 查询redis，按照距离排序，分页，结果：shopId，distance
        // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(key, new Circle(new Point(x, y), new Distance(5000)), RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end));
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        // 截取from到end
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(res -> {
            // 获取店铺id
            String shopIdStr = res.getContent().getName();
            // 获取距离
            Distance distance = res.getDistance();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, distance);
        });
        // 根据id查询店铺并返回
        // 通过,连接的ids
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }

    /*private boolean tryLock(String key) {
        // 这里设置过期时间，是为了避免线程出现问题导致这个“锁”迟迟得不到释放，进行一个兜底
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(isLock);
    }

    private void unlock(String key) {
        // 删除“锁“,相当于释放锁
        stringRedisTemplate.delete(key);
    }*/
    /*public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 查询店铺数据
        Thread.sleep(200);
        Shop shop = getById(id);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/
}
