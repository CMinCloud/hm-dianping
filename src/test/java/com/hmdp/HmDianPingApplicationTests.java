package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Test
    void testSave() throws InterruptedException {
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void queryWithLogicalLock(){
        shopService.queryWithLogicLock(1L);
    }

    @Test
    void setData(){
        List<Shop> list = shopService.query().list();
        for (Shop shop : list) {
            cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+shop.getId(),shop,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }

    }
}
