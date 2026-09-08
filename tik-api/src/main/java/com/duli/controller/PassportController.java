package com.duli.controller;

import com.duli.bo.RegistLoginBO;
import com.duli.grace.result.GraceJSONResult;
import com.duli.grace.result.ResponseStatusEnum;
import com.duli.pojo.Users;
import com.duli.service.IUsersService;
import com.duli.service.MailService;
import com.duli.utils.IPUtil;
import com.duli.utils.JwtUtil;
import com.duli.vo.UserVO;
import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.concurrent.TimeUnit;


@Slf4j
@Api(tags = "PassportController 通行证接口模块")
@RequestMapping("/passport")
@RestController
public class PassportController {

    @Autowired
    private MailService mailService;

    @Autowired
    private IUsersService userService;

    // 1. 注入 Redis 模板
    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 1. 获取邮箱验证码接口
     */
    @PostMapping("/getSMSCode")
    public GraceJSONResult getSMSCode(@RequestParam String mobile,
                                      HttpServletRequest request) {

        if (StringUtils.isBlank(mobile)) {
            return GraceJSONResult.errorMsg("手机号不能为空");
        }


        // --- MyBatis-Plus 特色：使用 LambdaQueryWrapper 查询 ---
        // 相当于执行了：SELECT * FROM users WHERE mobile = ? LIMIT 1
        LambdaQueryWrapper<Users> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Users::getMobile, mobile);

        // getOne 是 MyBatis-Plus IService 接口自带的方法
        Users user = userService.getOne(queryWrapper);

        // 业务校验
        if (user == null) {
            log.info("该手机号尚未注册，正在注册");
            user = userService.createUser(mobile);
        }

        String email = user.getEmail();
        if (StringUtils.isBlank(email)) {
            return GraceJSONResult.errorMsg("该手机号尚未绑定邮箱，无法使用此方式接收验证码");
        }

        // 生成6位随机验证码
        String code = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        log.info("验证码为：{}", code);

        try {
            // 发送邮件
            mailService.sendVerificationCode(email, code);

            //  把验证码存入 Redis，设置 5 分钟过期
            redisTemplate.opsForValue().set("MOBILE_CODE:" + mobile, code, 5, TimeUnit.MINUTES);
            log.info("验证码已成功存入 Redis。手机号: {}, 验证码: {}", mobile, code);
            //获取ip，用ip限制60s
            String userIp = IPUtil.getRequestIp(request);
            //  把限制信息limited存入 Redis，设置 1 分钟过期
            redisTemplate.opsForValue().set("SMS_LIMIT:" + userIp, "LIMITED", 60, TimeUnit.SECONDS);
            log.info("验证码已成功存入 Redis，并开启 60 秒防刷限制。");
        } catch (Exception e) {
            log.error("邮件发送失败, 手机号: {}, 邮箱: {}", mobile, email, e);
            return GraceJSONResult.errorMsg("验证码发送失败，请稍后重试");
        }

        return GraceJSONResult.success("验证码已发送到您绑定的邮箱，请查收！");
    }


    /**
     * 2. 一键登录/注册接口
     */
    @PostMapping("/login")
    public GraceJSONResult login(@Valid @RequestBody RegistLoginBO registLoginBO,
                                 HttpServletRequest request) {
        log.info("正在登录------");
        String mobile = registLoginBO.getMobile();
        String verifyCode = registLoginBO.getSmsCode();

        // 1. 从 Redis 中获取刚刚发给用户的验证码
        String redisKey = "MOBILE_CODE:" + mobile;
        String redisCode = redisTemplate.opsForValue().get(redisKey);

        // 2. 校验验证码 (判断空值以及是否匹配，忽略大小写)
        if (StringUtils.isBlank(redisCode) || !redisCode.equalsIgnoreCase(verifyCode)) {
            log.info("验证码错误或已过期");
            return GraceJSONResult.errorCustom(ResponseStatusEnum.SMS_CODE_ERROR);
        }

        // 3. 验证码正确，去数据库查这个用户
        LambdaQueryWrapper<Users> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Users::getMobile, mobile);
        Users user = userService.getOne(queryWrapper);

        // 4. 自动注册逻辑
        if (user == null) {
            user = userService.createUser(mobile);
        }

        // 5. 生成 JWT Token
        String token = JwtUtil.createToken(user.getId(), user.getMobile());


        /*6. 将 Token 放入 Redis，用于拦截器拦截校验
        ⭐
        如果以后App想做成微信那种“手机端 + 电脑端”可以同时在线的效果，也很简单，
        只需要把 Redis 的 Key 改造一下，加上设备标识就可以
        手机端存：USER_TOKEN:张三ID:Mobile;
        电脑端存：USER_TOKEN:张三ID:PC
         */
        //!!!!!!!！！！！
        //纯粹的 JWT 有一个致命的弱点 —— “一旦签发，无法主动销毁“
        redisTemplate.opsForValue().set("USER_TOKEN:" + user.getId(), token, 7, TimeUnit.DAYS);
        // 6. 验证码用完即废，从 Redis 删掉，防止验证码被重复使用
        redisTemplate.delete(redisKey);

        //7.登录成功
        log.info("用户{}登录成功", user.getNickname());

        // 8. 返回 Token 给前端
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        userVO.setUserToken(token);
        return GraceJSONResult.success(userVO);
    }

    //永远不要相信前端传来的用户 ID，直接从拦截器验证过的 Token 里拿！
    //!!!!!!!!!!!!!!
    //这个登出拿到的userid是从请求头上面拿到的，这样更安全不会被被人仅仅拿个userid就踢了
    /*
    1.为什么前端传 userId 不安全？（参数传递）
    如果接口是 logout?userId=1002，黑客只需要在浏览器里随便改一下数字，
    不需要任何技术含量，就能把你的系统搞得鸡犬不宁，让所有用户莫名其妙掉线。因为这里没有任何身份证明。

    2.为什么从 request（实际上是从 Token 里提取）拿最安全？
     你在拦截器里解析出来的 userId，是直接从 JWT 的肚子里取出来，并且经过了签名校验的。
     无法伪造：黑客就算知道李四的 ID 是 1002，他也绝对无法自己“捏造”出一个包含 userId=1002,且能通过拦截器校验的 JWT（因为他没有服务器上的 SECRET_KEY）。
     身份强绑定：只要请求走到了 logout 接口，就说明拦截器已经盖章确认
     “现在发起请求的这个人，手里拿着合法的 Token，且 Token 里写的名字就是张三（1001）”。
        ⭐ 架构师法则：
      在开发任何修改、删除、查询个人私密数据的接口时，代表“我是谁”的 userId，
      永远只能从后端的 Token/Session 中获取，绝不能作为参数让前端传过来。
     */
    @PostMapping("/logout")
    public GraceJSONResult logout(HttpServletRequest request) {
             log.info("退出-------");
        // 1. 直接从 request 里取出拦截器放行时塞进去的安全、真实的 userId
        //最安全的取userid的方法以免别人用测试工具就能进入
        //⭐越权查看他人隐私数据：是指的是一个人自己登录成功之后用自己的token和别人的id去看别人信息
        String currentUserId = (String) request.getAttribute("currentUserId");

        // 如果为空，说明没带 Token 或者拦截器没生效（按理说登出接口是要被 JWT 拦截的）
        if (StringUtils.isBlank(currentUserId)) {
            return GraceJSONResult.errorMsg("当前未登录");
        }

        // 2. 删掉 Redis 里的 Token
        redisTemplate.delete("USER_TOKEN:" + currentUserId);

        log.info("用户 {} 退出登录成功", currentUserId);
        return GraceJSONResult.success("退出登录成功");
    }
}