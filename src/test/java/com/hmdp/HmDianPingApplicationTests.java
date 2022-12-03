package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private IUserService userService;

    @Resource
    private CacheClient cacheClient;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate template;

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


    @Test
    void testRedisson() throws Exception {
        //获取锁(可重入)，指定锁的名称
        RLock lock = redissonClient.getLock("anyLock");
        //尝试获取锁，参数分别是：获取锁的最大等待时间(期间会重试)，锁自动释放时间，时间单位
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        //判断获取锁成功
        if (isLock) {
            try {
                System.out.println("执行业务");
            } finally {
                //释放锁
                lock.unlock();
            }

        }
    }

    //    将各类别的店铺信息根据地理位置存入缓存（存入店铺id，经纬度geo）
    @Test
    void testShopData() {
//        1.遍历所有shop信息
        List<Shop> list = shopService.query().list();
//        2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
/*        Map<Long, List<Shop>> map = new HashMap<>();
        List<Shop> foodList = new ArrayList<>();
        List<Shop> ktvList = new ArrayList<>();
        for (Shop shop : list) {
            if (shop.getTypeId() == 1) {
                foodList.add(shop);
                map.put(1L, foodList);
            } else {
                foodList.add(shop);
                map.put(2L, ktvList);
            }
        }*/
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(shop -> shop.getTypeId()));
//        3.将shop信息存入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> shopList = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shopList.size());
            for (Shop shop : shopList) {
//                template.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())));
            }
//            写入redis
            template.opsForGeo().add(key, locations);
        }
    }


    @Test
    void testSign(){
        userService.sign();
    }
}
