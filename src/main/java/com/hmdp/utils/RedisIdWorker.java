package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

//全局Id生成器
@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1668939753L;    //取定义开始时间戳

    private static final long COUNT_BITS = 32;  //位移序列号位数

    @Resource
    private StringRedisTemplate template;

    public long nextId(String keyPrefix) {       //keyPrefix代表业务前缀，不同的业务可以使用该util来生成id
//        1.生成时间戳
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp = now - BEGIN_TIMESTAMP;   //获取当前时间戳
//        2.生成序列号:  每天更新序列号,防止key上限
//        2.1 获取当前日期，精确到天
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("YYYY:MM:dd"));
//        2.2 自增长  :只在redis中存储当天id 的数量
        Long count = template.opsForValue().increment("icr:" + keyPrefix + ":" + date);
//            3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("YYYY:MM:dd");
        String format = dateFormat.format(new Date());  //获取当前日期
        System.out.println(format);
        System.out.println(new Date().getTime());   //获取当前时间毫秒值

        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        System.out.println(now);
    }

}
