package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    @Override
    public Result queryBYId(Long id) {
//        $$$使用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        $$$使用逻辑过期来解决缓存击穿
        Shop shop = queryWithLogicLock(id);
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
            System.out.println("是否命中！"+StrUtil.isBlank(jsonStr));
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
                    saveShop2Redis(id,20L);  //设置20s方便测试
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
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
