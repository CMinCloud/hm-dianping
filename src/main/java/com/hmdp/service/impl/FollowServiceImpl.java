package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Resource
    private StringRedisTemplate template;

    @Autowired
    private IUserService userService;

    //    判断当前用户是否已经关注目标用户
    @Override
    public Result isFollowed(Long followUserId) {
//        1、获取登录用户
        Long userId = UserHolder.getUser().getId();
//        从follow表中查看:select count(*) from tb_follow where follow_user_id = followUserId and user_id = userId
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    //    获取共同关注对象
    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.求交集
        String MyKey = "follows:" + userId;
        String OtherKey = "follows:" + id;
//        获取交集  =》 也就是存储的value：用户id
        Set<String> intersect = template.opsForSet().intersect(MyKey, OtherKey);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集,返回一个空用户集合
            return Result.ok(Collections.emptyList());
        }
//       3. 将id转换为一个集合
        List<Long> ids = intersect.stream().map(Long::parseLong).collect(Collectors.toList());
//       4.查询用户并返回一个用户集合
        List<UserDTO> userDTOList = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOList);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2.判断到底是关注还是取关
//        判断操作是关注还是取关
        if (isFollow) {
//            关注，新增用户
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
//                将关注我的关注存入redis缓存
                template.opsForSet().add(key, followUserId.toString());
            }
        } else {
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId)
            );
//            删除缓存中我的关注列表中该人
            template.opsForSet().remove(key, followUserId.toString());
        }
        // 3.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
        return Result.ok();
    }


}
