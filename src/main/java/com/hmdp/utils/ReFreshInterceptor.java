package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 第一层拦截器，负责刷新token，拦截一切路径
 */
public class ReFreshInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    public ReFreshInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }
    /**
     * 拦截器--处理请求前
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取token
        String token = request.getHeader("authorization");
        String tokenKey=LOGIN_USER_KEY+token;
        //2.查询redis用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        //3.保存redis里查到的用户到ThreadLocal中
        //3.1把hashMap转化为对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //3.2把userDTO放如当前线程中
        UserHolder.saveUser(userDTO);
        //4.刷新token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //5.放行
        return true;
    }

    /**
     * 拦截器--请求完成后
     * @param request
     * @param response
     * @param handler
     * @param ex
     * @throws Exception
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
       //当请求完成后直接删除线程里面的用户
        UserHolder.removeUser();
    }
}
