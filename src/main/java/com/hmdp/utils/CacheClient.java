package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private  static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 向缓存中添加KEY
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 向缓存中添加热点KEY,但是设置的是逻辑过期时间
     */
    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 返回空值，解决缓存穿透问题
     */
    public <R,ID>R queryWithPassThrough(String prefix, ID id,Class<R> type,Function<ID,R> dataFallBack,Long time,TimeUnit unit){
        //1.查询redis
        String key=prefix+id;
        String stringJSON = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(stringJSON)){
            //缓存命中
            R r = JSONUtil.toBean(stringJSON, type);
            return r;
        }
        //判断是否为空字符串
        if("".equals(stringJSON)){
            return null;
        }
        //2.缓存未命中
        //3.查询数据库
        R r= dataFallBack.apply(id);
        //4.没有查到数据,缓存空值
        if(r==null){
            //缓存空值，并且设置一下NULL的TTL，这可不是逻辑过期
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //5.查到了数据，正常存入redis，把结果返回
//        String string = JSONUtil.toJsonStr(r);
//        stringRedisTemplate.opsForValue().set(key,string,time,unit);直接用类中写好的set就行
        this.set(key,r,time,unit);
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿问题
     */
    public <R,ID>R queryWithLogicalExpire(String prefix,ID id,Class<R> type,Function<ID,R> dataFallBack,Long time,TimeUnit unit){
        //1.在redis缓存中查询信息
        String key=prefix+id;
        String stringJSON = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(stringJSON)) {
            //缓存未命中,直接返回空值就行了，
            //因为逻辑过期每个redis缓存都是永久的，没有的话说明数据预热的时候就没有
            return null;
        }
        //2.缓存命中，查看是否逻辑过期
        //将json反序列化为RedisData对象，方便查看逻辑过期时间
        RedisData redisData = JSONUtil.toBean(stringJSON, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);//关键
        if(expireTime.isAfter(LocalDateTime.now())){
            //未期了
            return r;
        }
        //3.过期了，尝试获取互斥锁去开启一个新线程查询数据库进行缓存重建
        String lockKey=LOCK_SHOP_KEY+id;
        Boolean isLock = this.tryLock(lockKey);
        if(isLock){
            //获取到了互斥锁，开启新线程缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r_dataBack = dataFallBack.apply(id);
                    //存入redis
                    this.setWithLogicalExpire(key,r_dataBack,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //4.无论是否获取了互斥锁，都要返回数据
        //走到这一步说明数据肯定是过期了的，即使进入锁的线程返回的也是旧数据，
        //只不过这个线程帮助其他线程缓存重建了
        return r;
    }
    /**
     * 获取互斥锁
     */
    private Boolean tryLock(String lockKey){
        Boolean key = stringRedisTemplate.opsForValue().setIfAbsent(lockKey,"1",LOCK_SHOP_TTL,TimeUnit.MINUTES);
        return BooleanUtil.isTrue(key);
    }

    /**
     *释放锁
     */
    private void unLock(String lockKey){
        stringRedisTemplate.delete(lockKey);
    }
}
