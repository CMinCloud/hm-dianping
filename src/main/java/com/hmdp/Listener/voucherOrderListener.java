package com.hmdp.Listener;


import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class voucherOrderListener {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = "voucherOrder_queue")
    public void ListenerVoucherOrder(VoucherOrder voucherOrder){
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
