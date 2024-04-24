package com.lyh.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyh.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    // TODO 解决redis缓存问题
    private final StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RBloomFilter<Long> rBloomFilter;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决redis缓存穿透
     */
    /*public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 存在则直接返回信息
            return JSONUtil.toBean(json, type);
        }
        // shopjson若是返回空字符串值不会进入上面的if语句中，还要在此处判断是否是空字符串
        if (json != null) {
            // 如果对象本身不为空，则证明json为空数据"",也就是下面写的""
            return null;
        }
        // 不存在，根据id查询数据库,由上层调用者来实现
        R ret = dbFallback.apply(id);
        if (ret == null) {
            // 还不存在，处理错误信息
            // 将空字符串返回redis,为这个key创建空缓存
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在，写入redis
        this.set(key, ret, time, unit);
        // 无论如何，总要返回信息
        return ret;
    }*/

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 获取布隆过滤器
        // 查询id是否存在于过滤器中
        boolean isContains =  rBloomFilter.contains((Long) id);
        if (!isContains) {
            log.error("布隆过滤器拦截了");
            return null;
        }
        // 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 存在则直接返回信息
            return JSONUtil.toBean(json, type);
        }
        // 不存在，根据id查询数据库,由上层调用者来实现
        R ret = dbFallback.apply(id);
        // 存在，写入redis
        this.set(key, ret, time, unit);
        // 无论如何，总要返回信息
        return ret;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期，处理热点key数据导致缓存击穿的问题
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        // fail-fast
        if (StrUtil.isBlank(json)) {
            // 未存在则直接返回null
            // 说明不是热点key，热点key已经提前加载过了
            return null;
        }
        // 命中，需要把json反序列为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期,看看是不是在当前时间之后
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期则直接返回
            return r;
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
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 重建缓存
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 无论如何，总要返回信息
        return r;
    }

    private boolean tryLock(String key) {
        // 这里设置过期时间，是为了避免线程出现问题导致这个“锁”迟迟得不到释放，进行一个兜底
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(isLock);
    }

    private void unlock(String key) {
        // 删除“锁“,相当于释放锁
        stringRedisTemplate.delete(key);
    }
}
