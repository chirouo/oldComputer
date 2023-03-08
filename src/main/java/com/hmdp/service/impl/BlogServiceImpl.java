package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.aspectj.weaver.ast.Var;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });

        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取登陆用户
        UserDTO user = UserHolder.getUser();
        //2.判断当前登陆用户是否已经点赞
        String key="blog:liked:"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        //3如果未点赞，可以点赞
        if(score==null){
            //3.1数据库点赞数加1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            //3.2保存用户到redis的set集合 zadd key value score
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,user.getId().toString(),System.currentTimeMillis());
            }
        }else{
            //4.如果已经点赞了，取消点赞
            //4.1数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            //4.2把用户从redis的set集合移除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,user.getId().toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1.查询top5点赞的用户 zrange key 04
        String key=BLOG_LIKED_KEY+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //3.更具id查询用户 WHERE id IN (5,1) ORDER BY FIELD(id,5,1)
        String idsStr= StrUtil.join(",",ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id,"+ids+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2. 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        // 3. 查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id=?
        List<Follow> follows = followService.query()
                .eq("follow_user_id", user.getId()).list();
        //4. 推送笔记id给粉丝
        for(Follow follow:follows){
            //4.1获取粉丝id
            Long userId = follow.getUserId();
            //4.2推送
            String key="feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());

        }
        return Result.ok();
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //4.解析数据：blogId,minTime(这两个数据里的最小时间戳)、offset
        ArrayList<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime=0;
        int ofs=1;
        for(ZSetOperations.TypedTuple<String> tuple:typedTuples){
            //4.1获取id
            String idStr=tuple.getValue();
            ids.add(Long.valueOf(idStr));
            //4.2获取分数（这两个数据里的最小时间戳）
            long time=tuple.getScore().longValue();
            if(time==minTime){//如果一样 offset加1
                ofs++;
            }else{//如果不一样那么现在的time绝对比之前的minTime小
                minTime=time;
                ofs=1;
            }
        }
        //5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //5.1查询blog的作者
            queryBlogUser(blog);
            //5.2查询blog是否被点赞
            isBlogLiked(blog);
        }
        //5.封装并返回
        ScrollResult r=new ScrollResult();
        r.setList(blogs);
        r.setOffset(ofs);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail("Blog不存在！");
        }
        //2.查询blog有关的用户
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1.获取登陆用户
        UserDTO user = UserHolder.getUser();
        if(user==null){
            //用户未登陆，无需查询是否点赞
            return;
        }
        //2.判断当前登陆用户是否已经点赞
        String key="blog:liked:"+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score!=null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
