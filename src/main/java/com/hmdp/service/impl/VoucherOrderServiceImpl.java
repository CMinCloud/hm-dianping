package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate template;

    @Autowired
    private RedissonClient redissonClient;

    private IVoucherOrderService currentProxy = null; //对代理对象做初始化

    private static final DefaultRedisScript<Long> SECKILL_LOCK;  //定义lua脚本对象

    /**
     * 在进行业务操作时发送mq消息
     */
    @Resource
    private RabbitTemplate rabbitTemplate;


    static {
        SECKILL_LOCK = new DefaultRedisScript<>();
        SECKILL_LOCK.setLocation(new ClassPathResource("seckill.lua"));   // 脚本文件
        SECKILL_LOCK.setResultType(Long.class);     // 返回值类型
    }

    //    声明阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //    创建线程池,一个单线程的异步处理就行
    private static final ExecutorService SECLILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

//      线程任务

    @PostConstruct  //在当前类初始化完后就执行线程池
    private void init() {
        SECLILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
//                    1.获取阻塞队列中的订单信息  (没有则阻塞在此,所以while不影响内存)
                    VoucherOrder voucherOrder = orderTasks.take();    // 当主线程中add了，这里就会take然后从阻塞中恢复开始运行
//                    2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }
            }
        }
    }

    //    处理阻塞队列中的订单业务
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
//        注意该方法在线程中执行,如果从userHolder中拿取是获取不到的
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        获取锁对象
        boolean isLock = lock.tryLock();    //使用redisson的锁来进行上锁操作
        if (!isLock) {
            log.error("不允许重复下单");
        }
        try {
            // 保证锁对象 唯一
//                !!! 使用Transactional注解管理事务需要获取当前的代理对象，而直接调用本类内部的方法不受spring管理
//                所以事务不生效,需要自己调用自己的代理对象才行!!!
//           // 获取代理事务, 下订单
            currentProxy.createVoucherOrder(voucherOrder);          // 事务提交之后才释放锁
        } finally {
            lock.unlock();
        }
    }

    //    这个方法不用添加事务transactional了
//    自定义redis锁解决秒杀下单业务
    @Override
/*    public Result seckillVoucher(Long voucherId) {
//        1 查询优惠卷
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        2 判断秒杀是否开始
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        LocalDateTime now = LocalDateTime.now();
//        2.1 秒杀未开始
        if (now.isBefore(beginTime))
            return Result.fail("秒杀尚未开始");
        if (now.isAfter(endTime))
            return Result.fail("秒杀已经结束");

//        3 秒杀开始，判断库存是否充足
        Integer stock = seckillVoucher.getStock();
//        3.1 库存不足，返回异常
        if (stock <= 0) {
            return Result.fail("无库存剩余");
        }
        Long userId = UserHolder.getUser().getId();
//        使用自定义构造锁
//        SimpleRedisLock lock = new SimpleRedisLock("lock:order:" + userId, template);   // 构建redis锁对象,锁对象为userId
//        boolean isLock = lock.tryLock(1200);   //设置过期时间，定期释放锁
//        使用分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        获取锁对象
        boolean isLock = lock.tryLock();    //使用redisson的锁来
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }

        try {
            // 保证锁对象 唯一
//                !!! 使用Transactional注解管理事务需要获取当前的代理对象，而直接调用本类内部的方法不受spring管理
//                所以事务不生效,需要自己调用自己的代理对象才行!!!
            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();   // 获取代理事务
            return currentProxy.createVoucherOrder(voucherId);          // 事务提交之后才释放锁
        } finally {
            lock.unlock();
        }
    }*/


    //    这里是要新增数据到数据库，我们采用悲观锁（保证 同一个用户 只有一个线程能够去下单优惠卷，
//    共享资源为该登录用户， 所以不能直接对方法添加synchronized声明 / 这样锁是该Impl类对象）
    @Transactional

    public Result createVoucherOrder(Long voucherId) {
        //        6 判断用户是否已经获取过优惠卷：
        Long userId = UserHolder.getUser().getId();
//        对用户 新增操作设置锁 ， 防止黄牛刷票
        //        select count(*) from tb_voucher_order where voucher_id = ? and user_id = ?
        long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        if (count > 0) {
//            6.1 用户已经购买过该优惠卷
            return Result.fail("您已获取该优惠卷");
        }
//        4 库存充足
//        4.1 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")        // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0)  // where voucher_id = ? and stock > 0
                .update();  //完成更新
        if (!success) return Result.fail("库存不足");
//        4.2 创建订单  ,封装订单信息
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);  //订单id
        voucherOrder.setUserId(UserHolder.getUser().getId());   //用户id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
//        5 返回订单id
        return Result.ok(orderId);
    }

    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
//        对用户 新增操作设置锁 ， 防止黄牛刷票
        //        select count(*) from tb_voucher_order where voucher_id = ? and user_id = ?
        long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        if (count > 0) {
//            6.1 用户已经购买过该优惠卷
            log.error("您已获取该优惠卷");
        }
//        4 库存充足
//        4.1 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")        // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0)  // where voucher_id = ? and stock > 0
                .update();  //完成更新
        if (!success) log.error("库存不足");
//        4.2向数据库提交订单
        save(voucherOrder);
    }

    //    使用lua脚本，异步实现 秒杀下单业务
    public Result seckillVoucher(Long voucherId) {
//        1.执行lua脚本：将 下单条件的判定和存入缓存 与 实际的数据库下单分离开来
        Long result = template.execute(
                SECKILL_LOCK,
                Collections.emptyList(),
                voucherId.toString(), UserHolder.getUser().toString()
        );
//        2.判断结果是为0 ，可以下单
        int r = result.intValue();
        if (r != 0) {
//        2.1. 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不允许重复下单");
        }
//        2.2. 为0，有购买资格，把下单信息保存到阻塞队列
//        2.3准备订单,保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);  //订单id
        voucherOrder.setUserId(UserHolder.getUser().getId());   //用户id
        voucherOrder.setVoucherId(voucherId);
//        2.4 存放到阻塞队列中去处理
//        对代理对象初始化,之后在方法中就可以调用
        currentProxy = (IVoucherOrderService) AopContext.currentProxy();
        orderTasks.add(voucherOrder);
        return Result.ok(orderId);
    }


    /**
     * 使用mq来实现消息的异步处理
     */
    public Result seckillVoucherByRabbitMq(Long voucherId) {
//        1.执行lua脚本：将 下单条件的判定和存入缓存 与 实际的数据库下单分离开来
        Long result = template.execute(
                SECKILL_LOCK,
                Collections.emptyList(),
                voucherId.toString(), UserHolder.getUser().toString()
        );
//        2.判断结果是为0 ，可以下单
        int r = result.intValue();
        if (r != 0) {
//        2.1. 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不允许重复下单");
        }
//        2.2. 为0，有购买资格，把下单信息保存到阻塞队列
//        2.3准备订单,保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);  //订单id
        voucherOrder.setUserId(UserHolder.getUser().getId());   //用户id
        voucherOrder.setVoucherId(voucherId);
//        2.4 存放到阻塞队列中去处理
//        对代理对象初始化,之后在方法中就可以调用
//        currentProxy = (IVoucherOrderService) AopContext.currentProxy();
//        orderTasks.add(voucherOrder);
//        生产者发布消息                   （交换机名称，路由key，发送的参数）
        rabbitTemplate.convertAndSend("DIANPING_EXCHANGE", "voucher_order", voucherOrder);
        return Result.ok(orderId);
    }
}
