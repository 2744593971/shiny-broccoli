package com.duli.interceptor;

import com.duli.grace.result.GraceJSONResult;
import com.duli.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

//⭐：它负责协调整个 Web 请求的生命周期。它不仅会调用 JwtUtil 去验真伪，
// 还要去连 Redis 检查状态（防顶号/登出），最后甚至还要往 request 里塞属性
@Slf4j
public class JwtInterceptor implements HandlerInterceptor {

    // ⭐️ 注入 Redis 工具 (因为我们在 WebMvcConfig 里用了 @Bean 注册拦截器，所以这里可以正常注入)
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 🌟 1. 必须放行浏览器的 OPTIONS 预检请求！
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        //  从 HTTP 请求头中获取 Token（前端一般会把 token 放在 header 里传过来）
        // 前端约定的 header 名字叫 "headerUserToken"
        String userToken = request.getHeader("headerUserToken");

        if (StringUtils.isBlank(userToken)) {
            returnErrorResponse(response, GraceJSONResult.errorMsg("请先登录"));
            return false;
        }

        // 1. 第一重校验：验证 JWT 自身的真伪和有效期
        boolean isVerify = JwtUtil.verifyToken(userToken);
        if (!isVerify) {
            returnErrorResponse(response, GraceJSONResult.errorMsg("登录已过期或 Token 被篡改，请重新登录"));
            return false;
        }

        // 2. ⭐️ 第二重校验：结合 Redis 判断是否登出或被顶号
        String userId = JwtUtil.getUserId(userToken);
        String redisToken = stringRedisTemplate.opsForValue().get("USER_TOKEN:" + userId);

        if (StringUtils.isBlank(redisToken)) {
            // 场景 A：用户已经点击了“退出登录”，Redis 里被清空了
            log.warn("用户 {} 已经退出登录", userId);
            returnErrorResponse(response, GraceJSONResult.errorMsg("您已退出登录，请重新登录"));
            return false;
        }

        if (!redisToken.equals(userToken)) {
            // 场景 B：别人在另一台手机登录了，Redis 里的 Token 被覆盖成了最新的，和当前传过来的不一样！
            log.warn("用户 {} 在其他设备登录被顶号", userId);
            returnErrorResponse(response, GraceJSONResult.errorMsg("您的账号已在其他设备登录，被迫下线！"));
            return false;
        }

        // 3. 将解析出来的 userId 放到 request 属性里，方便后面的 Controller 直接用！
        request.setAttribute("currentUserId", userId);
        return true;
    }

    private void returnErrorResponse(HttpServletResponse response, GraceJSONResult result) throws Exception {
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(new ObjectMapper().writeValueAsString(result));
    }
}


