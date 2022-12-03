package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;

import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j          // 打印日志
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机信息
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);//调用工具类生成6位数字的验证码
//        保存验证码 ,用403630模拟验证码，不用每次都发送             (有效时间2min)
        stringRedisTemplate.opsForValue()
                .set(LOGIN_CODE_KEY + phone, "403630", LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        发送验证码  （用发送邮箱验证码来替代就好了！！！）
//        sendMail(code);   不用每次都发送，测试一下就好
        log.info("邮箱（手机）验证码发送成功,验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
//        从redis中获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String loginCode = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(loginCode)) {
            return Result.fail("验证码错误");
        }
//        判断用户是否存在（根据手机号判断）
/*        LambdaQueryWrapper wrapper = new LambdaQueryWrapper();
        wrapper.eq(phone,phone);*/
        User user = query().eq("phone", phone).one();
        if (user == null) {    //用户不存在，注册用户并保存
            user = createNewUserByPhone(phone);
            System.out.println("hhh");
            save(user);
        }
/*       保存到session中,封装为UserDTO，防止用户隐私信息泄露
        session.setAttribute("loginUser", BeanUtil.copyProperties(user, UserDTO.class));*/
//        保存到redis中
//        1.生成一个token作为登陆令牌(不过该token仍不够保险)
        String token = UUID.randomUUID().toString();

//        2.将user对象转为hash存储格式
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((filedName, filedValue) ->
                                filedValue.toString()           //将需要修改类型的参数变量修改为String如Long
                        ));
//       3.存储: 将用户基本信息存入redis，以便后续提取
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
//        4.设置token刷新时间
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
//        5.返回token，存储在前端
        return Result.ok(token);
    }

    //    用户签到功能
    @Override
    public Result sign() {
//        1、获取用户信息
        Long userId = UserHolder.getUser().getId();
//        2、获取当前时间
        long time = System.currentTimeMillis();
        String keySuffix = new SimpleDateFormat(":yyyyMM").format(time);
//        3、拼接key
        String key = USER_SIGN_KEY + userId + keySuffix;
//        4.获取今天是本月的第几天  (从1开始到31号)，而redis中存储bitmap从0开始：所以记得-1
        int dayOfMonth = LocalDateTime.now().getDayOfMonth();
        System.out.println("几天是本月的第" + dayOfMonth + "天");
//        5.存入redis ： key =  sign：+userId
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);  //true代表当天签到
//
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取   本月截止今天为止   的所有的签到记录，返回的是一个十进制的数字 如： BITFIELD sign:5:202203 GET u14 0
//        返回值是result，因为field命令可以执行多个操作，所以结果是个集合  ,我们这里只存储了第一个元素
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
//                从该月第一天取到当天
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);   //比如获取存储的十进制签到数字 7  =>  转为二进制 11100000
        if(num == null || num == 0){
            return Result.ok(0);
        }
        // 6.循环遍历
        int signCount = 0;
        while (true) {   //这里的num会被转为2进制   和 1   进行与运算
//        让这个数字的每一位与1做与运算，得到数字的最后一个bit位
            if((num & 1) == 0){
                //        如果为0，说明未签到，结束
                break;
            }else {
                signCount ++;
//        如果不为0，说明已签到，计数器+1
            }
//            把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num = num>>>1;
        }
        return Result.ok(signCount);
    }

    //    根据手机号注册用户
    public User createNewUserByPhone(String phone) {
        User user = new User();
//       生成随机用户名
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(6));
        user.setPhone(phone);
        return user;
    }

    @Resource
    private JavaMailSender javaMailSender;

    public void sendMail(String code) {   // 自动注入邮箱发送接口
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("cm_403630@163.com");       //邮箱发送者
        message.setTo("1587813968@qq.com");     //邮箱接收者
        message.setSubject("");   //设置主题
        message.setText(code);
        javaMailSender.send(message);
    }

//
}
