package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.RedisData;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
//        //解决缓存穿透-----1
//        Shop shop=queryWithPassThrough(id);

        //互斥锁解决缓存击穿
//        Shop shop=queryWithMetux(id);

        //逻辑过期解决缓存击穿----1
//        Shop shop=queryWithLogicalExpire(id);

        //解决缓存穿透-----2
//        Shop shop=cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //逻辑过期解决缓存击穿----2
        Shop shop=cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if(shop==null){
            return Result.fail("店铺不存在!互斥锁解决缓存击穿");
        }
        return Result.ok(shop);
    }

    public Shop queryWithLogicalExpire(Long id){
        //1.在redis中查询商铺信息
        String shopKey = CACHE_SHOP_KEY + id;
        String stringShop = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存是否命中
        if(StrUtil.isBlank(stringShop)){
            //未命中
            return null;
        }
        //命中了
        //3.判断缓存是否过期
        RedisData redisData=JSONUtil.toBean(stringShop,RedisData.class);
        JSONObject jsonShop = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(jsonShop, Shop.class);
        LocalDateTime expiretime=redisData.getExpireTime();
        if(expiretime.isAfter(LocalDateTime.now())){
            //未过期，返回数据
            return shop;
        }
        //4.过期了，缓存重建
        //4.1获得互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //开启独立线程获取数据
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //数据写入redis，无论是否获得了互斥锁，如果获得了互斥锁那么存入的就是新数据，没获得互斥锁存入的就是旧数据
        stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //返回
        return shop;
    }

    /**
     * 查询店铺信息 （互斥锁版本 解决缓存穿透和缓存击穿，未解决缓存雪崩）
     * @param id
     * @return
     */
    public Shop queryWithMetux(Long id){
        //1.查询redis
        //1.1.查询redis缓存有无商铺信息,之前登陆的时候用过hash了，这里就用string练习
        String shopKey = CACHE_SHOP_KEY + id;
        String stringShop = stringRedisTemplate.opsForValue().get(shopKey);
        //1.2.判断数据是否为空
        if (StrUtil.isNotBlank(stringShop)) {
            //1.3.把String变成JSON格式
            Shop shop = JSONUtil.toBean(stringShop, Shop.class);
            //1.4返回给前端
            return shop;
        }
        //1.6现在数据要么是null要么是空字符串，判断是不是空字符串的假缓存（字符串比较不能用==）
        if ("".equals(stringShop)) {
            return null;
        }

        //2.没有就查询MySQL数据库
        //到了这一步就是缓存未命中，要查数据库了，需要保护数据库，防止缓存击穿，获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //如果isLock为false（没获取到锁）
            if (isLock==false) {
                //让没有获取到互斥锁的线程休眠，再重复以上所有步骤(包括redis缓存是否命中步骤)
                Thread.sleep(30);//线程休眠可能有异常，需要try/catch/finally
                return queryWithMetux(id);
            }
            shop = getById(id);
            //2.1.1数据库没查到数据,返回错误信息
            if (shop == null) {
                //2.1.2为了防止缓存穿透，我们返回一个空字符串
                stringRedisTemplate.opsForValue().set(shopKey,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //2.2.数据库查到了,但是要先存在redis里面，然后返回给前端
            String stringShop2 = JSONUtil.toJsonStr(shop);
            //2.3设置超时时间，为线程冲突造成数据不一致兜底
            stringRedisTemplate.opsForValue().set(shopKey,stringShop2,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    /**
     * 查询店铺信息（只解决了缓存穿透的版本，未解决缓存雪崩和缓存击穿）
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        //1.查询redis
        //1.1.查询redis缓存有无商铺信息,之前登陆的时候用过hash了，这里就用string练习
        String shopKey = CACHE_SHOP_KEY + id;
        String stringShop = stringRedisTemplate.opsForValue().get(shopKey);
        //1.2.判断数据是否为空
        if (StrUtil.isNotBlank(stringShop)) {
            //1.3.把String变成JSON格式
            Shop shop = JSONUtil.toBean(stringShop, Shop.class);
            //1.4返回给前端
            return shop;
        }
        //1.6现在数据要么是null要么是空字符串，判断是不是空字符串的假缓存（字符串比较不能用==）
        if ("".equals(stringShop)) {
            return null;
        }
        //2.没有就查询MySQL数据库
        Shop shop = getById(id);
        //2.1.1数据库没查到数据,返回错误信息
        if (shop == null) {
            //2.1.2为了防止缓存穿透，我们返回一个空字符串
            stringRedisTemplate.opsForValue().set(shopKey,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //2.2.数据库查到了,但是要先存在redis里面，然后返回给前端
        String stringShop2 = JSONUtil.toJsonStr(shop);
        //2.3设置超时时间，为线程冲突造成数据不一致兜底
        stringRedisTemplate.opsForValue().set(shopKey,stringShop2,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 更新店铺信息
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        //1.先更新数据库
        updateById(shop);
        //2.再删除redis缓存
        Long id=shop.getId();
        if (id == null) {
            return Result.fail("店铺的id不能为空");
        }
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    /**
     * 互斥锁（利用redis实现）
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        //利用redis的setnx方法特性，如果存在就不创建
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁（直接deleteKey）
     * @param key
     */
    private void unLock(String key){
        //利用redis实现
        stringRedisTemplate.delete(key);
    }

    /**
     * redis数据预热（逻辑过期解决缓存击穿）
     */
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询数据库
        Shop shop = getById(id);
        //手动给线程加一个延迟，这样我们缓存创建有一定的延迟，延迟越长越容易出现线程安全问题
        Thread.sleep(200);
        //2.封装逻辑过期的缓存的时间
        RedisData redisdata=new RedisData();
        redisdata.setData(shop);
        redisdata.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.把数据放入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisdata));
    }
}
