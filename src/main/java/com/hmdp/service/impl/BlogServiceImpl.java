package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate template;


    @Autowired
    private IUserService userService;

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);
        // 3.查询是否点赞
        if (UserHolder.getUser() != null) {       // 当用户登录后才查询当前用户是否点赞过
            isBlogLiked(blog);
        }
        return Result.ok(blog);
    }

    //    检查用户是否点赞过该博客并封装isLiked 属性
    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = template.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    public void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            if (UserHolder.getUser() != null) {       // 当用户登录后才查询当前用户是否点赞过
                isBlogLiked(blog);
            }
        });
        return Result.ok(records);
    }

    //    实现点赞功能：
    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;   //注意这里key 和value的值
//        Boolean isMember = template.opsForSet().isMember(key, userId.toString());
        Double score = template.opsForZSet().score(key, userId.toString());
        //3.如果未点赞，可以点赞
        if (score == null) {
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                //3.2 保存用户到Redis的set集合          // 以当前时间存入，按时间顺序读取
                template.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //4.如果已点赞，取消点赞
            //4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked -1").eq("id", id).update();
            //4.2 把用户从Redis的set集合移除
            if (isSuccess) {
                template.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    //    查询为当前博客点赞的用户列表，排序方式：按点赞时间从早到晚
//    Data：List<UserDTO>
    @Override
    public Result queryBlogLikedUsers(Long id) {
        // 1.查询top5的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;   //注意这里key 和value的值
        Set<String> top5 = template.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());    // 暂时还没有人点过赞
        }
        // 2.解析出其中的用户id
//        使用stream流处理并封装为List
        List<Object> idList = top5.stream().map(s -> Long.valueOf(s) // 将集合中的每一个String转换为Long
        ).collect(Collectors.toList());
/*        for (String s : top5) {
            Long userId = Long.valueOf(s);
//            根据userId查询用户
            User user = userService.getById(userId);
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            userList.add(userDTO);   //存入list
        }*/
        String idStr = StrUtil.join(",", idList);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOList = userService.query()
                .in("id", idList).last("ORDER BY FIELD(id," + id + ")")
                .list()
                .stream().map(user ->
                        BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}
