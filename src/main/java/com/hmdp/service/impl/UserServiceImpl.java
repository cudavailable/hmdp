package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.MessageConstant;
import com.hmdp.constant.TermConstant;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

        // 保存验证码到session中
        session.setAttribute(TermConstant.SESSION_CODE, code);

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
        Object cacheCode = session.getAttribute(TermConstant.SESSION_CODE);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            return Result.fail(MessageConstant.CODE_INCORRECT);
        }

        // 根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq(TermConstant.PHONE, phone).one();
        if (user == null) {
            // 不存在创建用户
            user = createUserWithPhone(phone);
        }

        // 保存用户信息到session中
        session.setAttribute(TermConstant.USER, user);

        return Result.ok();
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
