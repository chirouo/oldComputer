package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT=new DefaultRedisScript<Long>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    //给线程一个任务，runnable
        private class VoucherOrderHandler implements Runnable{
            String queueName="stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    //1. 获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获取成功
                    if(list==null||list.isEmpty()){
                        //2.1 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.解析订单中的消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4. 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //5. ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                   log.error("创建订单异常",e);
                   handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while(true){
                try {
                    //1. 获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1  STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息是否获取成功
                    if(list==null||list.isEmpty()){
                        //2.1 如果获取失败，说明pengdinglist没有异常消息，结束循环
                        break;
                    }
                    //3.解析订单中的消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4. 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //5. ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("创建pengding-list订单异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }

        }
    }

//    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while(true){
//
//                try {
//                    //1. 获取队列中的订单信息
//                    VoucherOrder voucherOrder=orderTasks.take();
//                    //2.创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                   log.error("创建订单异常",e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1. 获取用户
        Long userId=voucherOrder.getUserId();
        //2. 创建锁对象
         SimpleRedisLock simpleRedisLock=new SimpleRedisLock("order"+userId, stringRedisTemplate);
        //获取互斥锁
        boolean isLock = simpleRedisLock.tryLock(1200);
        if(!isLock){
           log.error("抱歉，一个人只能抢购一次该优惠卷,不允许重复下单");
        }
        try {
            //获取代理对象，事务才能正常执行，否则事务会失效
             proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            simpleRedisLock.unlock();
        }
    }

    //定义一个方法，让线程在程序开始时就执行任务
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId=UserHolder.getUser().getId();
       //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2.判断结果是否为0
        int r=result.intValue();
        if(r!=0){
            //2.1 不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //2.2 为0，有购买资格，保存下单信息到阻塞队列中
        Long orderId=redisIdWorker.nextId("order");

        //获取代理对象
        proxy=(IVoucherOrderService) AopContext.currentProxy();
        //3.返回订单id
       return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀活动尚未开始！");
//        }
//        //判断秒杀是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀活动已经结束！");
//        }
//        //判断库存
//        if(seckillVoucher.getStock()<1){
//            return Result.fail("库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        SimpleRedisLock simpleRedisLock=new SimpleRedisLock("order"+userId, stringRedisTemplate);
//        //获取互斥锁
//        boolean isLock = simpleRedisLock.tryLock(1200);
//        if(!isLock){
//            return Result.fail("抱歉，一个人只能抢购一次该优惠卷,不允许重复下单");
//        }
//        try {
//            //获取代理对象，事务才能正常执行，否则事务会失效
//            IVoucherOrderService proxy=(IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            simpleRedisLock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count>0){
            log.error("用户已经购买过一次优惠卷！");
        }
        //扣减库存
        boolean success = seckillVoucherService.update().
                setSql("stock=stock-1").
                eq("voucher_id", voucherOrder.getVoucherId()).
                gt("stock",0).//乐观锁
                        update();
        //扣减失败
        if(!success){
            log.error("扣减失败，库存不足！");
        }
        //创建订单
        save(voucherOrder);
    }
}
