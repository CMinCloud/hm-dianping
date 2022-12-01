package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Bean;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate template;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryBYId(Long id) {
//        使用缓存工具类来解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById,
//                CACHE_SHOP_TTL, TimeUnit.MINUTES);

//        $$$使用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

//        $$$使用逻辑过期来解决缓存击穿
//        Shop shop = queryWithLogicLock(id);

//        使用缓存工具类 调用 逻辑过期方法来解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalLock(CACHE_SHOP_KEY, id, Shop.class, this::getById,
                CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1、从redis中查询商铺缓存
        String shopJson = template.opsForValue().get(key);
        // 2、判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在,直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的值是否是空值
        if (shopJson != null) {
            //返回一个错误信息
            return null;
        }
        // 4.实现缓存重构
        //4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2 判断否获取成功
            if (!isLock) {
                //4.3 失败，则休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);          //重新去尝试 查数据
            }
            //4.4 成功，根据id查询数据库
            shop = getById(id);
            // 5.不存在，返回错误
            if (shop == null) {
                //将空值写入redis
                template.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.写入redis
            template.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }


    //    创建一个线程池用来在未命中缓存后 查询数据库
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicLock(Long id) {
        String jsonStr = template.opsForValue().get(CACHE_SHOP_KEY + id);
//            1 判断缓存是否命中
        if (StrUtil.isBlank(jsonStr)) {
            System.out.println("是否命中！" + StrUtil.isBlank(jsonStr));
//            1.1未命中
            return null;
        }
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);  //获取到的是jsonObject
        Shop shop = BeanUtil.toBean(redisData.getData(), Shop.class);  // 获取商户信息
        LocalDateTime expireTime = redisData.getExpireTime();
//        2.缓存命中 ，查看缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
//        2.1 缓存未过期，直接返回缓存值
            return shop;
        }
//        2.2缓存过期
//        3 尝试获取互斥锁
        String LockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(LockKey);
        if (!isLock) {
//        3.1 获取锁失败, 暂时返回一个过期的数据 （因此不能保证一致性）
            return shop;
        }
//        3.2获取锁成功，使用线程池开启独立线程（防止过多线程访问数据库）
        CACHE_REBUILD_EXECUTOR.submit(new TimerTask() {     // 设置线程任务
            @Override
            public void run() {
//                重建缓存
                try {
                    saveShop2Redis(id, 20L);  //设置20s方便测试
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //                释放锁
                    unlock(LockKey);
                }
            }
        });
//        返回一个旧的值
        return shop;
    }

    //    使用  存入 redis缓存 来模拟上锁，其他线程必须等待
    private boolean tryLock(String key) {
        Boolean flag = template.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //    释放锁
    private void unlock(String key) {
        template.delete(key);
    }

    @Override
    @Transactional      //这里注意理解，这里更新数据库和删除   /  这里用mq实现异步更新其实更好一些
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
//        1.更新数据库
        updateById(shop);
//        2.清理缓存
        String key = CACHE_SHOP_KEY + id;
        template.delete(key);
        return Result.ok();
    }

//    根据商户类型来查询商户信息(可以根据经纬度进行查询)
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
//        1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = template.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),              // 中心点坐标（在实际业务中为自己手机的坐标）
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }


    public void saveShop2Redis(Long id, Long expireTime) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200L);  //模拟缓存延迟,休眠200ms
//        设置逻辑过期时间
        RedisData data = new RedisData();
        data.setData(shop);
        data.setExpireTime(LocalDateTime.now().plusSeconds(expireTime)); //从当前时间开始多少秒
//        存入redis
        template.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(data));
    }
}
