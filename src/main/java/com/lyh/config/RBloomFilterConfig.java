package com.lyh.config;

import com.lyh.mapper.ShopMapper;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class RBloomFilterConfig {
    private final RedissonClient redissonClient;
    private final ShopMapper shopMapper;


    public RBloomFilterConfig(RedissonClient redissonClient, ShopMapper shopMapper) {
        this.redissonClient = redissonClient;
        this.shopMapper = shopMapper;
    }


    @Bean
    public RBloomFilter<Long> rBloomFilter() {
        RBloomFilter<Long> shopListFilter = redissonClient.getBloomFilter("shopList");
        shopListFilter.tryInit(20, 0.01);
        List<Long> list = shopMapper.getIds();
        for (Long aLong : list) {
            shopListFilter.add(aLong);
        }
        return shopListFilter;
    }
}
