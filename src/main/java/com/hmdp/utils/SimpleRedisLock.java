package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate template;

    private String name;

    private static final String KEY_PREFIX = "lock:";
    //    生成特定的id用来表示不同的线程，声明ID_PREFIX为静态类型
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;  //定义lua脚本对象

    /**
     * 使用静态代码块进行  lua脚本构建
     * 最终构建目的，  实现拿锁、判定锁、删锁的原子性操作
     */
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));   // 脚本文件
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    //      对于不同的业务有不同的名字
    public SimpleRedisLock(String name, StringRedisTemplate template) {
        this.name = name;
        this.template = template;    //该类未受spring管理，所以需要手动注入
    }



    @Override
    public boolean tryLock(long timeoutSec) {
//        获取当前线程Id作为线程表示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        设置过期时间，以防线程获取失败   但资源锁迟迟不释放
        Boolean success = template.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
//        尝试获取锁的结果
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
/*        String threadId = template.opsForValue().get(KEY_PREFIX + name);    // 获取缓存中过的id
        String cacheId = ID_PREFIX + Thread.currentThread().getId(); //拼接当前线程的threadID
        if (threadId.equals(cacheId))          // 相同则代表时一个线程可以释放锁
            template.delete(KEY_PREFIX + name);*/
        template.execute(
                UNLOCK_SCRIPT,          //执行脚本
                Collections.singletonList(KEY_PREFIX + name),   // 生成一个只包含指定对象的list集合
                ID_PREFIX + Thread.currentThread().getId());    //比较的参数
    }
}
