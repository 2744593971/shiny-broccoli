package com.duli.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.TokenExpiredException;
import java.util.Date;

@Component
public class JwtUtil {

    // ⚠️这里是 static 变量，但是把原来写死的字符串去掉了
    private static String SECRET_KEY;
    private static long EXPIRE_TIME;

    // ⚠️写一个非静态的 set 方法，在方法上打 @Value 注解去读取 yml 的值
    @Value("${jwt.secret}")
    public void setSecretKey(String secret) {
        JwtUtil.SECRET_KEY = secret;
    }

    @Value("${jwt.expire-time}")
    public void setExpireTime(long expireTime) {
        JwtUtil.EXPIRE_TIME = expireTime;
    }

    /**
     * ⭐ 生成 JWT Token
     * JwtUtil 本质上是一个工具类（就像你代码里用到的 StringUtils.isBlank() 或者是 Math.random()）。
     * 对于工具类，我们通常希望随时随地、拿来就用
     *如果 createToken 不是静态方法，那么你在 PassportController 里想用它，就必须先把它注入进来
     */
    public static String createToken(String userId, String mobile) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + EXPIRE_TIME);

        return JWT.create()
                .withClaim("userId", userId)
                .withClaim("mobile", mobile)
                .withIssuedAt(now)
                .withExpiresAt(expireDate)
                .sign(Algorithm.HMAC256(SECRET_KEY)); // 这里使用的就是从 yml 读出来的密钥了
    }

    /**
     * ⭐校验 Token 的真伪和是否过期
     *
     * @return true: 有效，false: 无效或已过期
     *
     * verifyToken方法校验token时候不用去redis里面比对
     * 因为 JWT 是“无状态（Stateless）”的，它自带“防伪标识”，
     * 不需要依赖任何外部存储（如数据库或 Redis）就能自证清白。
     *
     * 一个标准的 JWT 是由三段组成的（中间用 . 隔开）：Header.Payload.Signature
     * Header（头部）：记录算法（如 HMAC256）。
     * Payload（载荷）：里面装着你之前放进去的 userId、mobile，以及自动生成的签发时间和过期时间。（这部分其实是明文Base64编码的）
     * Signature（签名）：核心机密！后端用 Header + Payload + SECRET_KEY 经过加密算法算出来的一串哈希值
     *
     * 后端拿着jwt里的header和payload 与自己存的密钥运算，运算结果在与签名比对
     */
    public static boolean verifyToken(String token) {
        try {
            // 用之前的秘钥创建一个验证器
            JWTVerifier verifier = JWT.require(Algorithm.HMAC256(SECRET_KEY)).build();
            // 验证 token
            verifier.verify(token);
            return true;
        } catch (TokenExpiredException e) {
            // Token 已经过期
            return false;
        } catch (Exception e) {
            // Token 被篡改或格式不对
            return false;
        }
    }

    /**
     * 不校验签名，直接从 Token 的 Payload 中获取 userId
     * (因为在调用这个方法前，拦截器已经调用 verifyToken 校验过签名了，所以这里直接解密绝对安全)
     */
    public static String getUserId(String token) {
        try {
            DecodedJWT jwt = JWT.decode(token);
            return jwt.getClaim("userId").asString();
        } catch (Exception e) {
            return null;
        }
    }
}