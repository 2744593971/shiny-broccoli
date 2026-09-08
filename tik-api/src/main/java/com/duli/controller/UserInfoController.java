package com.duli.controller;

import com.duli.grace.result.GraceJSONResult;
import com.duli.pojo.Users;
import com.duli.service.IUsersService;
import com.duli.vo.UserVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@Api(tags = "UserInfoController 用户信息与统计模块")
@RequestMapping("/userInfo")
@RestController
public class UserInfoController {

    @Autowired
    private IUsersService userService;

    // 1. 注入 redisTemplate
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 定义 Redis 的 Key 前缀常量（方便统一管理）
    private static final String REDIS_MY_FOLLOWS_COUNTS = "redis_my_follows_counts";
    private static final String REDIS_MY_FANS_COUNTS = "redis_my_fans_counts";
    private static final String REDIS_VLOGER_BE_LIKED_COUNTS = "redis_vloger_be_liked_counts";

    @ApiOperation(value = "获取当前用户的个人主页信息及统计数据")
    @GetMapping("query")
    public GraceJSONResult query(HttpServletRequest request) throws Exception {

        // 🌟 2. 核心安全改动：不从参数拿 userId，而是从拦截器塞进来的 Token 关联获取！
        String currentUserId = (String) request.getAttribute("currentUserId");
        if (StringUtils.isBlank(currentUserId)) {
            return GraceJSONResult.errorMsg("当前未登录");
        }

        // 3. 查数据库：获取用户基础信息 (MyBatis-Plus 的 getById)
        Users user = userService.getById(currentUserId);
        if (user == null) {
            return GraceJSONResult.errorMsg("用户不存在");
        }

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);

        // 4. 查 Redis：获取高频统计数据（关注数、粉丝数、获赞数）
        String myFollowsCountsStr = redisTemplate.opsForValue().get(REDIS_MY_FOLLOWS_COUNTS + ":" + currentUserId);
        String myFansCountsStr = redisTemplate.opsForValue().get(REDIS_MY_FANS_COUNTS + ":" + currentUserId);
        String likedVlogerCountsStr = redisTemplate.opsForValue().get(REDIS_VLOGER_BE_LIKED_COUNTS + ":" + currentUserId);

        // 5. 判空与默认值转换处理（防止 Redis 刚开始没数据时拿到 null 报错）
        Integer myFollowsCounts = StringUtils.isNotBlank(myFollowsCountsStr) ? Integer.valueOf(myFollowsCountsStr) : 0;
        Integer myFansCounts = StringUtils.isNotBlank(myFansCountsStr) ? Integer.valueOf(myFansCountsStr) : 0;
        Integer likedVlogerCounts = StringUtils.isNotBlank(likedVlogerCountsStr) ? Integer.valueOf(likedVlogerCountsStr) : 0;

        // 6. 将数据组装进 VO 返回给前端
        userVO.setMyFollowsCounts(myFollowsCounts);
        userVO.setMyFansCounts(myFansCounts);
        userVO.setTotalLikeMeCounts(likedVlogerCounts);

        return GraceJSONResult.success(userVO);
    }
}