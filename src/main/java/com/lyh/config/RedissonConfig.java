package com.lyh.config;

import cn.hutool.core.bean.BeanUtil;
import com.lyh.entity.Shop;
import com.lyh.service.impl.ShopServiceImpl;
import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;
import java.util.List;
import java.util.Scanner;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://47.97.82.35:6379").setPassword("sys");
        // 创建Redisson对象
        return Redisson.create(config);
    }



}
