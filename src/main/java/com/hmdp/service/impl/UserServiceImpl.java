package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.cron.pattern.matcher.DayOfMonthValueMatcher;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.MessageConstant;
import com.hmdp.constant.RedisConstants;
import com.hmdp.constant.TermConstant;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constant.RedisConstants.*;


@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送短信验证码
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            // 如果不符合，提醒用户发送失败
            return Result.fail(MessageConstant.PHONE_INVALID);
        }

        // 如果符合，生成短信验证码
        String code = RandomUtil.randomNumbers(6);

        // 保存验证码到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 发送验证码（模拟发送）
        log.debug("发送短信验证码成功，验证码：{}", code);

        // 返回ok
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        // 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            // 如果不符合，提醒用户发送失败
            return Result.fail(MessageConstant.PHONE_INVALID);
        }

        // 验证验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail(MessageConstant.CODE_INCORRECT);
        }

        // 根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq(TermConstant.PHONE, phone).one();
        if (user == null) {
            // 不存在创建用户
            user = createUserWithPhone(phone);
        }

        // 生成登录校验token
        String token = UUID.randomUUID().toString(true);

        // 将User对象转为Map对象
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((filedName, filedValue)->filedValue.toString()));

        // 将Map对象存到redis中
        String tokenkey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenkey, map);
        stringRedisTemplate.expire(tokenkey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    /**
     * 签到功能
     * @return
     */
    public Result sign() {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();

        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        // 拼接
        String key = USER_SIGN_KEY + userId + keySuffix;

        // 当月中的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 在bitmap上签到
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();

        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        // 拼接
        String key = USER_SIGN_KEY + userId + keySuffix;

        // 当月中的第几天(2025.03.06)
        int dayOfMonth = now.getDayOfMonth();

        // BITFIELD sign:1010:202503 GET u6 0
        List<Long> list = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );

        if (list == null || list.isEmpty()) {
            return Result.ok(0);
        }
        Long num = list.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }

        // 统计连续签到次数
        int count = 0;
        while((num & 1) != 0){// 当num取末尾不为0时
            count++;
            num = num >>> 1;
        }

        return Result.ok(count);
    }

    /**
     * 根据手机号创建一个新用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(TermConstant.DEFAULT_USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10));

        // 保存用户
        save(user);
        return user;
    }
}
