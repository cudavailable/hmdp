package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.constant.RedisConstants.FOLLOW_KEY;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 关注或取关一个指定的用户
     * @param followUserId
     * @param isFollow
     * @return
     */
    public Result follow(Long followUserId, Boolean isFollow) {
        // 判断目前是要关注还是取关
        Long userId = UserHolder.getUser().getId();

        if (isFollow) { // 关注
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean success = save(follow);// 新增到数据库

            if (success) { // 数据库新增成功时，redis添加
                String key = FOLLOW_KEY + userId;
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else{ // 取关
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("follow_user_id", followUserId)
                    .eq("user_id", userId));

            if (success) { // 数据库删除成功时，redis删除
                String key = FOLLOW_KEY + userId;
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }

        return Result.ok();
    }

    /**
     * 判断是否关注了特定的用户
     * @param followUserId
     * @return
     */
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();

        Integer count = query()
                .eq("follow_user_id", followUserId)
                .eq("user_id", userId).count();

        return Result.ok(count > 0); // 若在follow表中有对应的记录，则返回已关注
    }

    /**
     * 查看当前用户与id对应的用户的共同关注
     * @param id
     * @return
     */
    public Result followCommmon(Long id) {
        Long userId = UserHolder.getUser().getId();

        // 针对redis中的两个集合取交
        String key = FOLLOW_KEY + userId;
        String key2 = FOLLOW_KEY + id;
        Set<String> set = stringRedisTemplate.opsForSet().intersect(key, key2);

        if (set == null || set.isEmpty()) { // 如果共同关注集合为空，返回空列表
            return Result.ok(Collections.emptyList());
        }

        // 转化为共同关注用户的id列表
        List<Long> commonFollowList = set.stream().map(Long::valueOf).collect(Collectors.toList());

        // 查询共同关注用户列表
        List<UserDTO> userDTOs = userService.listByIds(commonFollowList).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOs);
    }
}
