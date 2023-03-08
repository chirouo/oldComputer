package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    //        List<ShopType> typeList = typeService
//                .query().orderByAsc("sort").list();
//        return Result.ok(typeList);
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //1.查询redis
        String shopTypeKey = CACHE_SHOP_TYPE;
        String shopType = stringRedisTemplate.opsForValue().get(shopTypeKey);
        if (StrUtil.isNotBlank(shopType)) {
            //1.1.查到了
            //1.2.转成List
            List<ShopType> shopTypeList = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //1.2.没查到
        //2查询MySQL，存入redis缓存
        List<ShopType> shopTypeList2 = query().orderByAsc("sort").list();
        //2.1没查到
        if (shopTypeList2 == null) {
            return Result.fail("分类信息不存在!");
        }
        //2.2查到了
        String stringShopTypeList2 = JSONUtil.toJsonStr(shopTypeList2);
        stringRedisTemplate.opsForValue().set(shopTypeKey,stringShopTypeList2);
        return Result.ok(shopTypeList2);
    }
}
