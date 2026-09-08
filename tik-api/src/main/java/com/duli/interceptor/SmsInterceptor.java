package com.duli.interceptor;

import com.duli.grace.result.GraceJSONResult;
import com.duli.grace.result.ResponseStatusEnum;
import com.duli.utils.IPUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * “拦截器”限制发送频率，这里用到的不是MyBatis-Plus 拦截器（那是拦截 SQL 的），
 * 而是 Spring MVC 拦截器（HandlerInterceptor），它用来拦截前端发过来的 HTTP 请求。
 * 用拦截器结合 Redis 来做 60 秒防刷限制，是一个非常标准且优雅的企业级做法！
 * 因为这样可以把“防刷逻辑”和“发邮件的业务逻辑”解耦。
 *
 * 这个拦截器会在请求到达Controller之前，去Redis里检查有没有60秒的限制记录。
 */
@Slf4j
public class SmsInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 获取用户的 IP，用ip限制60s（或者也可以从 request 中获取 mobile 参数来限制手机号）
        String userIp = IPUtil.getRequestIp(request);

        // 拼接一个用于限制频率的 Redis Key
        String limitKey = "SMS_LIMIT:" + userIp;

        // 判断 Redis 中是否存在这个 Key
        boolean isExist = stringRedisTemplate.hasKey(limitKey);

        if (isExist) {
            // 如果存在，说明 60 秒内已经发过了，直接拦截！
            log.warn("IP: {} 尝试频繁获取验证码，已被拦截", userIp);

            // 构建返回给前端的 JSON 错误信息
            GraceJSONResult result = GraceJSONResult.errorMsg("短信发送太快啦，请60秒后再试！");

            // 将 JSON 数据写回给前端
            response.setContentType("application/json;charset=utf-8");//声明返回的数据格式是 JSON，并设置 UTF-8 编码（防止中文乱码）。
            response.getWriter().write(new ObjectMapper().writeValueAsString(result));//将 Java 结果对象转换成 JSON 字符串。把转换好的 JSON 字符串，直接写进 HTTP 的响应体（Body）里面，发送给前端。

            // 返回 false 代表拦截请求，不会再进入 Controller，而且请求体也会显示result信息
            //不会进入controller但是依然会响应一个响应体告诉前端：短信发送太快啦，请60秒后再试！
            return false;
        }

        // 返回 true 代表放行请求，继续进入 Controller 执行后续逻辑
        return true;
    }
}
