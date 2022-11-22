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
import org.jetbrains.annotations.NotNull;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate template;

    //    这个方法不用添加事务transactional了
    @Override
    public Result seckillVoucher(Long voucherId) {
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
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, template);   // 构建redis锁对象,锁对象为userId
        boolean isLock = lock.tryLock(1200);    //设置过期时间，定期释放锁
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }

        try {
            // 保证锁对象 唯一
            /**
             *    !!! 使用Transactional注解管理事务需要获取当前的代理对象，而直接调用本类内部的方法不受spring管理
             *    所以事务不生效,需要自己调用自己的代理对象才行!!!
             */
            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();   // 获取代理事务
            return currentProxy.createVoucherOrder(voucherId);          // 事务提交之后才释放锁
        } finally {
            lock.unlcok();
        }
    }

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
}
