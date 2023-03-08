package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

   @Resource
   private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService iUserService;
    /**
     * 发送短信验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.判断电话号码是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("抱歉，电话号码不合法");
        }
        //2.如果合法，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存验证码到redis,顺便设置redis时间有效期
        String key=LOGIN_CODE_KEY+phone;
        stringRedisTemplate.opsForValue().set(key,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5..发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        //成功
        return Result.ok("发送验证码成功");
    }

    /**
     * 实现登陆功能（包括验证码和密码登陆版本）
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.检查手机号是否正确
        String phone=loginForm.getPhone();
        String code = loginForm.getCode();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("抱歉，手机号格式不正确");
        }
        //2.检查验证码是否正确
        //2.1.从redis获得code
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        if(cacheCode==null||!cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //3.查看用户是否存在,不存在则创建新用户
        User user=query().eq("phone",phone).one();
        if(user==null){
            user =  createUserWithPhone(phone);
        }
        //4.将用户数据存入redis，无论用户是否是新用户都要有
        //4.1创建一个token作为key（）
        String token = UUID.randomUUID().toString(true);
        String tokenKey=LOGIN_USER_KEY+token;
        //4.2将取出来的用户转化为hashmap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((filedName,filedValue)->filedValue.toString()));
        //5.存储
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //6.设置redis数据有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //7.返回token给前端客户端（为啥不是tokenKey呢）
        return Result.ok(token);
    }

    @Override
    public Result getCurrentUser() {
        //1.从当前线程获得当前的用户信息
        UserDTO user = UserHolder.getUser();
        //2.返回用户信息给前端
        return Result.ok(user);
    }

    /**
     * 用手机号创建一个用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }
}
