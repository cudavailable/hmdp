package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constant.SystemConstants;
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
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.constant.MessageConstant.BLOG_NOT_EXIST;
import static com.hmdp.constant.MessageConstant.BLOG_SAVE_FAIL;
import static com.hmdp.constant.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.constant.RedisConstants.FEED_KEY;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    /**
     * 根据blog id查询 blog
     * @param id
     * @return
     */
    public Result queryByBlogID(Long id) {
        // 根据id查询博客
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail(BLOG_NOT_EXIST);
        }

        // 根据用户id查询用户昵称和头像
        setUpBlog(blog);
        // 查询有没有被点赞高亮
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查询热门blog
     * @param current
     * @return
     */
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.setUpBlog(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 点赞一篇blog(指定blog id)
     * @param id
     * @return
     */
    public Result likeBlog(Long id) {
        // 查询当前用户
        Long userId = UserHolder.getUser().getId();

        // 判断用户有没有为此blog点过赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score != null){// 如果此前已点过赞
            // 该blog点赞数减一
            boolean success = update().setSql("liked = liked-1").eq("id", id).update();

            if(success){ // 取消成功
                // redis取消点赞
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }else { // 此前未点过赞
            // 该blog点赞数加一
            boolean success = update().setSql("liked = liked+1").eq("id", id).update();

            if(success){ // redis点赞成功
                // redis点赞
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }

        return Result.ok();
    }

    /**
     * 查询一篇指定blog的点赞排行榜
     * @param id
     * @return
     */
    public Result queryBLogLikes(Long id) {
        // 查询redis，获取指定blog id的点赞排行榜前5
        String key = BLOG_LIKED_KEY + id;
        Set<String> set = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        // 如果set为空，返回空列表
        if (set == null || set.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 解析查询结果，获取user id list
        List<Long> userIds = set.stream().map(Long::valueOf).collect(Collectors.toList());

        // 根据user id list，查询用户列表
//        List<User> users = userService.listByIds(userIds); // 该接口仅使用in辅助查询，结果无序
        String idsStr = StrUtil.join(",", userIds);

        // select ? from tb_ where ? in (1, 5) order by filed (user_id, 5, 1)
        List<User> users = userService.query()
                .in("id", userIds).last("ORDER BY FIELD (id, " + idsStr + ")")
                .list();

        // 转化为userDto list
        List<UserDTO> userDtoS = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 返回
        return Result.ok(userDtoS);
    }

    /**
     * 保存blog，并推送
     * @param blog
     * @return
     */
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if(!success){
            return Result.fail(BLOG_SAVE_FAIL);
        }

        // 推送到粉丝的“邮箱”

        // 获取关注粉丝
        List<Follow> fans = followService.query().eq("follow_user_id", user.getId()).list();
        for(Follow f : fans){
            // 将新发的笔记推送给每一个粉丝
            String key = FEED_KEY + f.getUserId();
            stringRedisTemplate.opsForZSet().add(key, String.valueOf(blog.getId()), System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 滚动查询被推送的笔记
     * @param max
     * @param offset
     * @return
     */
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();

        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue())); // 博客的id

            // 时间戳
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                os = 1;
                minTime = time;
            }
        }

        // 根据blog id列表查询
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD (id, " + idStr + ")").list();

        for (Blog blog : blogs) {
            this.setUpBlog(blog);
            this.isBlogLiked(blog);
        }

        // 构造返回结果，最后返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }

    /**
     * 对传入的blog添加用户所带的信息
     * @param blog
     */
    private void setUpBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 根据redis中用户是否已点赞，设置blog中的isLike属性
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        // 查询当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) { // 当前用户未登录，直接返回，不提供点赞排行榜
            return ;
        }

        Long userId = user.getId();

        // 判断用户有没有为此blog点过赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
