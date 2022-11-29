package com.hmdp.config;


import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class MqConfig {

//    需要使用Bean加载

    //    声明交换机
    @Bean
    public TopicExchange topicExchange() {
//        durable 默认 true， autoDelete默认false
        return new TopicExchange("DIANPING_EXCHANGE", true, false);
    }

    //    新增队列
    @Bean
    public Queue voucherOrderQueue() {
        return new Queue("voucherOrder_queue");
    }

    @Bean
    public Queue voucherOrderQueue2() {
        return new Queue("voucherOrder_queue2");
    }

    //    将队列绑定到交换机上，使用topic连接方式，设置RoutingKey

    @Bean                                   // 使用自动装配填入形参
    public Binding BindingInsertQueue(Queue voucherOrderQueue, TopicExchange topicExchange) {
//        满足对应的key才会将消息发送给 对应的queue
        return BindingBuilder.bind(voucherOrderQueue).to(topicExchange).with("voucher_order");
    }

    @Bean                                   // 使用自动装配填入形参
    public Binding BindingInsertQueue2(Queue voucherOrderQueue2, TopicExchange topicExchange) {
//        满足对应的key才会将消息发送给 对应的queue
        return BindingBuilder.bind(voucherOrderQueue2).to(topicExchange).with("voucher_order");
    }

}
