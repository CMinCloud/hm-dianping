package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;


//      封装缓存工具类
@Slf4j
@Component
public class CacheClient {

    @Resource
    private  StringRedisTemplate template;  //定义一个唯一的redis接口用于该工具类

//    public CacheClient(StringRedisTemplate template) {
//        this.template = template;
//    }

    /**
     * 将任意Java对象序列化为json并存储再string类型的key中，并且可以设置TTL过期时间
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        template.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }


    /**
     * 将任意Java对象序列化为json并存储再string类型的key中，并且可以设置逻辑过期时间
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
//        封装 过期时间和 数据进入 redis
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
//        写入redis
        template.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     */
    public <R, I> R queryWithPassThrough(String prefix, I id, Class<R> type
            , Function<I, R> dbFallback, Long expireTime, TimeUnit unit) {
        String key = prefix + id;
        String jsonStr = template.opsForValue().get(key);
//            1 判断缓存是否命中
        if (StrUtil.isNotBlank(jsonStr)) {
//            1.1 缓存命中，直接返回缓存值
            R r = JSONUtil.toBean(jsonStr, type);
            return r;
        }
//        缓存未命中，查询数据库
//        检查缓存的是否是 空值 “ ” "".equals(jsonStr)
        if (jsonStr != null) {
            return null;   //表示没有数据
        }
//        没有缓存数据,那么就从数据库查询数据
        R r = dbFallback.apply(id);
//        存入缓存
        if (r == null) {      // 为了避免缓存穿透，数据库中存入"" ，防止重复为空值查询数据库
            template.opsForValue().set(key, "", expireTime, TimeUnit.MINUTES);
            return null;
        }
//        template.opsForValue().set(key, JSONUtil.toJsonStr(r), expireTime, TimeUnit.MINUTES);
        this.set(key, r, expireTime, unit);        //直接调用缓存方法存入
        return r;
    }


    //    创建一个线程池用来在未命中缓存后 查询数据库
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, I> R queryWithLogicalLock(String prefix, I id, Class<R> type
            , Function<I, R> dbFallback, Long time, TimeUnit unit) {
        String key = prefix + id;
        String jsonStr = template.opsForValue().get(key);
//            1 判断缓存是否命中
        if (StrUtil.isBlank(jsonStr)) {
//            1.1未命中
            return null;
        }
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);  //获取到的是jsonObject
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);  // 获取商户信息
        LocalDateTime expireTime = redisData.getExpireTime();
//        2.缓存命中 ，查看缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
//        2.1 缓存未过期，直接返回缓存值
            return r;
        }
//        2.2缓存过期
//        3 尝试获取互斥锁
        String LockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(LockKey);
        if (!isLock) {
//        3.1 获取锁失败, 暂时返回一个过期的数据 （因此不能保证一致性）
            return r;
        }
//        3.2获取锁成功，使用线程池开启独立线程（防止过多线程访问数据库）
        CACHE_REBUILD_EXECUTOR.submit(new TimerTask() {     // 设置线程任务
            @Override
            public void run() {
//                重建缓存
                try {
//                    查询数据库
                    R newR = dbFallback.apply(id);
//                    写入redis
                    setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //                释放锁
                    unlock(LockKey);
                }
            }
        });
//        返回一个旧的值
        return r;
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
}
