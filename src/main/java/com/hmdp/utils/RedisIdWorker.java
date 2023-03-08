package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于redis的全局id生成器
 */
@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static int COUNT_BITS = 32;
    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public Long nextId(String keyPrefix){
        LocalDateTime now=LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long time=nowSecond-BEGIN_TIMESTAMP;
        String format=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // Redis Incrby 命令将 key 中储存的数字加上指定的增量值。
        // 如果 key 不存在，那么 key 的值会先被初始化为 0 ，然后再执行 INCRBY 命令
        //format是一个很巧妙的后缀，结合keyPrefix(业务前缀)正好可以帮我们统计一天内某个业务产生的id需求量是多少
        Long count=stringRedisTemplate.opsForValue().increment("icr"+keyPrefix+format);
        return time<<COUNT_BITS|count;
    }

}
