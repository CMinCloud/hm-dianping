package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissionConifg {

    @Bean
    public RedissonClient redissonClient(){
//        配置
        Config config = new Config();   //引用自redission依赖
        config.useSingleServer().setAddress("redis://47.109.59.20:6379")   //指定redis地址
                .setPassword("403630");
//        创建RedissionClient对象
        return Redisson.create(config);

    }
}
