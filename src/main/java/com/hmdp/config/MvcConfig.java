package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.ReFreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",//这两个是登陆阶段，肯定要排除，这个时候肯定是没有用户信息存在session里的
                        "/user/login"
                ).order(1);
        registry.addInterceptor(new ReFreshInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
