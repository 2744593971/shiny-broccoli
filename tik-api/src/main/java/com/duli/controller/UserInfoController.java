package com.duli.controller;

import com.duli.grace.result.GraceJSONResult;
import com.duli.pojo.Users;
import com.duli.service.IUsersService;
import com.duli.service.impl.AliyunOSSService;
import com.duli.vo.UserVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

import com.duli.bo.UpdatedUserBO;
import com.duli.grace.result.GraceJSONResult;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;

@Slf4j
@Api(tags = "UserInfoController 用户信息与统计模块")
@RequestMapping("/userInfo")
@RestController
public class UserInfoController extends com.duli.config.BaseInfoProperties { // 继承基础类

    @Autowired
    private IUsersService userService;

    @ApiOperation(value = "获取当前用户的个人主页信息及统计数据")
    @GetMapping("/query")
    public GraceJSONResult query(HttpServletRequest request) throws Exception {
        log.info("获取当前用户的个人主页信息");
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
        //  1.安全核心：从 request 中拿当前真正登录的用户 ID
        //  🌟 安全第一：绝对不信任前端传的 ID，直接从拦截器塞进来的 Token 里取真实 ID！
        String currentUserId = (String) request.getAttribute("currentUserId");
        if (StringUtils.isBlank(currentUserId)) {
            return GraceJSONResult.errorMsg("当前未登录");
        }

        // 2. 🌟 强制把真实 ID 塞给 BO（覆盖掉前端可能瞎传的 ID）
        updatedUserBO.setId(currentUserId);

        // 3. 把 BO 拷给 PO (实体类)
        // 配合 MyBatis-Plus，实体类中为 null 的字段不会去更新数据库
        Users user = new Users();
        BeanUtils.copyProperties(updatedUserBO, user);

        // 4. 去数据库执行更新
        userService.updateById(user);

        // 5. 重新查询数据库，获取修改后的最新用户信息
        Users updatedUser = userService.getById(currentUserId);

        // 6. 封装前端需要的 UserVO
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(updatedUser, userVO);

        // 🌟 关键一步：从 Redis 中取回当前 Token 并塞进 VO，防止前端覆盖缓存时导致 Token 丢失
        String token = redisTemplate.opsForValue().get("USER_TOKEN:" + currentUserId);
        userVO.setUserToken(token);

        return GraceJSONResult.success(userVO);
    }

    @Autowired
    private AliyunOSSService aliyunOSSService;
    // 对应前端的 URL: /userInfo/modifyImage
    @PostMapping("/modifyImage")
    public GraceJSONResult modifyImage(@RequestParam("userId") String userId,
                                       @RequestParam("type") Integer type,
                                       @RequestParam("file") MultipartFile file,
                                       HttpServletRequest request) throws Exception {

        // 1. 🌟 安全校验：防止前端伪造 userId
        String currentUserId = (String) request.getAttribute("currentUserId");
        if (StringUtils.isBlank(currentUserId) || !currentUserId.equals(userId)) {
            return GraceJSONResult.errorMsg("当前未登录或用户身份异常");
        }

        // 2. 核心调用：一行代码拿到外网可以访问的阿里云图片链接！
        String imageUrl = aliyunOSSService.uploadFile(file, currentUserId);

        // 3. 更新数据库
        Users user = new Users();
        user.setId(currentUserId); // 强制使用安全的 currentUserId

        // type=1 是修改背景图，type=2 是修改头像
        if (type == 1) {
            log.info("修改背景");
            user.setBgImg(imageUrl);
        } else if (type == 2) {
            log.info("修改头像");
            user.setFace(imageUrl);
        }
        userService.updateById(user);

        // 4. 重新查库，把最新的用户信息封装成 UserVO
        Users updatedUser = userService.getById(currentUserId);
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(updatedUser, userVO);

        // 5. 🌟 补全注释中的逻辑：把 Token 塞进去，防止前端更新本地缓存时把 Token 弄丢
        String token = redisTemplate.opsForValue().get("USER_TOKEN:" + currentUserId);
        userVO.setUserToken(token);

        // 6. 返回给前端！
        return GraceJSONResult.success(userVO);
    }



}