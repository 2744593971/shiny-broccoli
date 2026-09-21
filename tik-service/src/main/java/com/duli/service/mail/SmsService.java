package com.duli.service.mail;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class SmsService {

    // 直接注入 StringRedisTemplate
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void saveCodeToRedis(String mobile, String code) {

        // 定义存入 Redis 的 Key，通常会加个前缀规范一下，比如 "MOBILE_CODE:138xxxx"
        String redisKey = "MOBILE_CODE:" + mobile;

        // 核心操作：存入 Redis，并设置过期时间为 5 分钟
        stringRedisTemplate.opsForValue().set(redisKey, code, 5, TimeUnit.MINUTES);

        System.out.println("验证码已成功存入 Redis，Key: " + redisKey + ", Value: " + code);
    }

    public String getCodeFromRedis(String mobile) {
        // 当用户点击登录时，用这个方法把验证码取出来比对
        return stringRedisTemplate.opsForValue().get("MOBILE_CODE:" + mobile);
    }
}
