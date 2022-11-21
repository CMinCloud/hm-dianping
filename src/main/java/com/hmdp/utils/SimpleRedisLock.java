package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {


    private StringRedisTemplate template;

    private String name;

    private static final String KEY_PREFIX = "lock:";

    //      对于不同的业务有不同的名字
    public SimpleRedisLock(String name,StringRedisTemplate template) {
        this.name = name;
        this.template = template;    //该类未受spring管理，所以需要手动注入
    }

    @Override
    public boolean tryLock(long timeoutSec) {
//        获取当前线程Id作为线程表示
        long id = Thread.currentThread().getId();
        Boolean success = template.opsForValue().setIfAbsent(KEY_PREFIX + name, String.valueOf(id), timeoutSec, TimeUnit.SECONDS);
//        尝试获取锁的结果
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlcok() {
        template.delete(KEY_PREFIX + name);
    }
}
