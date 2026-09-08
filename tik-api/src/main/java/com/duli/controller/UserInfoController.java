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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import com.duli.bo.UpdatedUserBO;
import com.duli.grace.result.GraceJSONResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import javax.validation.Valid;

@Api(tags = "UserInfoController 用户信息与统计模块")
@RequestMapping("/userInfo")
@RestController
public class UserInfoController extends com.duli.config.BaseInfoProperties { // 继承基础类

    @Autowired
    private IUsersService userService;

    @ApiOperation(value = "获取当前用户的个人主页信息及统计数据")
    @GetMapping("query")
    public GraceJSONResult query(HttpServletRequest request) throws Exception {

        // 1. 安全核心：从拦截器塞进来的 Token 关联获取当前真实用户 ID（彻底杜绝越权漏洞）
        String currentUserId = (String) request.getAttribute("currentUserId");
        if (StringUtils.isBlank(currentUserId)) {
            return GraceJSONResult.errorMsg("当前未登录");
        }

        // 2. 查数据库：获取用户基础信息
        Users user = userService.getById(currentUserId);
        if (user == null) {
            return GraceJSONResult.errorMsg("用户不存在");
        }

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);

        // 3. 查 Redis：直接使用父类继承过来的 redisTemplate 操作高频统计数据
        String myFollowsCountsStr = redisTemplate.opsForValue().get(REDIS_MY_FOLLOWS_COUNTS + ":" + currentUserId);
        String myFansCountsStr = redisTemplate.opsForValue().get(REDIS_MY_FANS_COUNTS + ":" + currentUserId);
        String likedVlogerCountsStr = redisTemplate.opsForValue().get(REDIS_VLOGER_BE_LIKED_COUNTS + ":" + currentUserId);

        // 4. 判空与默认值转换处理（防止 Redis 刚开始没数据时拿到 null 报错）
        Integer myFollowsCounts = StringUtils.isNotBlank(myFollowsCountsStr) ? Integer.valueOf(myFollowsCountsStr) : 0;
        Integer myFansCounts = StringUtils.isNotBlank(myFansCountsStr) ? Integer.valueOf(myFansCountsStr) : 0;
        Integer likedVlogerCounts = StringUtils.isNotBlank(likedVlogerCountsStr) ? Integer.valueOf(likedVlogerCountsStr) : 0;

        // 5. 将数据组装进 UserVO 返回给前端
        userVO.setMyFollowsCounts(myFollowsCounts);
        userVO.setMyFansCounts(myFansCounts);
        userVO.setTotalLikeMeCounts(likedVlogerCounts);

        return GraceJSONResult.success(userVO);
    }


    @ApiOperation(value = "修改用户信息")
    @PostMapping("modifyUserInfo")
    // 加上 @Valid 让 BO 里的 @NotBlank 等校验生效
    // 加入了valid注解之后就不用去后端判断前端有没有传入必须的参数了
    public GraceJSONResult modifyUserInfo(@RequestBody @Valid UpdatedUserBO updatedUserBO,
                                          HttpServletRequest request) {
        //@Valid 就是一个“开关”，用来激活BO里写的那些校验规则：控制是否真的不为空等
        // 1. 安全核心：从 request 中拿当前真正登录的用户 ID
        String currentUserId = (String) request.getAttribute("currentUserId");
        if (StringUtils.isBlank(currentUserId)) {
            return GraceJSONResult.errorMsg("当前未登录");
        }

        // 2. 绝对防越权：不管前端有没有传 id，强行覆盖为当前登录用户的 id！
        updatedUserBO.setId(currentUserId);

        // 3. 调用 Service 执行修改
        Users updatedUser = userService.updateUserInfo(updatedUserBO);

        // 4. 返回成功，已采用 success
        return GraceJSONResult.success(updatedUser);
    }
}