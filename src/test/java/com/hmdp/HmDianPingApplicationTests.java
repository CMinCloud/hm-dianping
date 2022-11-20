package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Test
    void testSave() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

    @Test
    void queryWithLogicalLock() {
        shopService.queryWithLogicLock(1L);
    }

    @Test
    void setData() {
        List<Shop> list = shopService.query().list();
        for (Shop shop : list) {
            cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + shop.getId(), shop, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }
    }

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        String prefix = "order";

        CountDownLatch latch = new CountDownLatch(300);
//        定义线程任务
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
//            执行线程任务并计时
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);     //执行该线程任务 300次，每次 自增100次
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time =" + (end - begin));
    }
}
