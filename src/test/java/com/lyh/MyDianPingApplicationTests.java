package com.lyh;

import com.lyh.entity.Shop;
import com.lyh.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class MyDianPingApplicationTests {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;

    @Test
    void loadShopData() {
        // 查询店铺信息
        List<Shop> list = shopService.list();
        // 把店铺分组，按照typeid分组，id一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 分批完成写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 获取类型id
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            // 获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            // 写入redis geoadd key 经度 纬度 member
            for (Shop shop : value) {
                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
            }
        }
    }
}
