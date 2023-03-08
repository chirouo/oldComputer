package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 基于店铺信息解决缓存击穿的方案二逻辑过期
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
