package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.sun.org.apache.bcel.internal.generic.NEW;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 第二层拦截器，单纯校验当前是否有用户登陆
 */
public class LoginInterceptor implements HandlerInterceptor {
    /**
     * 单纯查有没有用户，别的不处理
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.查询ThreadLocal，看当前是否有用户
        //1.1没有就返回状态码401，并且拦截请求
        if(UserHolder.getUser()==null){
            response.setStatus(401);
            return false;
        }
        else{
            return true;
        }
    }


}
