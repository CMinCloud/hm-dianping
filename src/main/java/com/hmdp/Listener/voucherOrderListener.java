package com.hmdp.Listener;


import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component  //声明为spring的组件
public class voucherOrderListener {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = "voucherOrder_queue")
    public void ListenerVoucherOrder(VoucherOrder voucherOrder){
        System.out.println("消费者1处理任务");
        voucherOrderService.createVoucherOrder(voucherOrder);
    }

//    两个队列绑定在同一个交换机上，模拟不同主机的不同队列消费同一个消息
    @RabbitListener(queues = "voucherOrder_queue2")
    public void ListenerVoucherOrder2(VoucherOrder voucherOrder){
        System.out.println("消费者2处理任务");
        voucherOrderService.createVoucherOrder(voucherOrder);
    }


    /**
     * 使用完全注解的方式实现mq异步
     */
/*    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "voucherOrder_queue"),
            exchange = @Exchange(name = "DIANPING_EXCHANGE",type = ExchangeTypes.TOPIC),
            key = {"voucher_order"}
    ))
    public void ListenerVoucherOrder2(VoucherOrder voucherOrder){
        voucherOrderService.createVoucherOrder(voucherOrder);
    }*/
}
