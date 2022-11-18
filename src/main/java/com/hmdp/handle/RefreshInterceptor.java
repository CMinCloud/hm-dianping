package com.hmdp.handle;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;


@Slf4j
@Component
public class RefreshInterceptor implements HandlerInterceptor {
//    在登录方法执行前进行拦截

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //    拦截器，不对操作进行拦截，只是在用户进行操作时如果已登录则更新登录信息
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {       //token为空,直接放行
            return true;
        }
//        从redis中获取用户信息
        Map<Object, Object> entries = stringRedisTemplate.
                opsForHash().entries(LOGIN_USER_KEY + token);
        //        重新转换为userDTO对象
        if(entries.isEmpty()){          //用户信息为空,直接放行
            return true;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);
//        存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
//        刷新token有效期：30min内有操作
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);
//        放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        清除用户:拦截器的执行流程是 先执行所有的pre方法,再倒序执行所有的after方法
        UserHolder.removeUser();
    }

}
