package com.duli.service;

import com.duli.exceptions.ShopException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

/** Redis 全局/用户固定窗口限流加本机并发闸门；不在 Redis 中维护第二份库存。 */
@Component
public class ShopAdmissionGuard {
 private final StringRedisTemplate redis;
 private final Semaphore slots;
 private final int globalLimit,userLimit;
 /** 一个 Lua 调用同时检查并递增全局/用户计数；任一超限时两个计数都不变。 */
 private static final DefaultRedisScript<Long> LIMIT=new DefaultRedisScript<>(
   "local g=tonumber(redis.call('get',KEYS[1]) or '0'); "
  +"local u=tonumber(redis.call('get',KEYS[2]) or '0'); "
  +"if g>=tonumber(ARGV[1]) or u>=tonumber(ARGV[2]) then return 0 end; "
  +"if redis.call('incr',KEYS[1])==1 then redis.call('pexpire',KEYS[1],1000) end; "
  +"if redis.call('incr',KEYS[2])==1 then redis.call('pexpire',KEYS[2],1000) end; return 1;",Long.class);

 /** 配置为学习环境保守默认值，生产吞吐应由目标环境压测后决定。 */
 public ShopAdmissionGuard(StringRedisTemplate redis,@Value("${shop.admission.global-per-second:200}") int global,
        @Value("${shop.admission.user-per-second:3}") int user,@Value("${shop.admission.local-concurrency:4}") int concurrency) {
  this.redis=redis;this.globalLimit=Math.max(1,global);this.userLimit=Math.max(1,user);
  this.slots=new Semaphore(Math.max(1,concurrency));
 }
 /**
  * 先用 Semaphore 限制当前实例并发，再由 Lua 原子判断全局/用户每秒配额。
  * 任一限额触发返回 429；Redis 故障返回 503，绝不绕开限流直接写 MySQL。
  * 成功取得名额后由 submit 的 finally 调用 leave；本方法失败则自行释放。
  */
 public void enter(String user) {
  if(user==null||user.trim().isEmpty()) throw new ShopException(401,"请先登录");
  // 先限制单实例正在受理的 HTTP 数，避免本机线程堆积；成功后必须由调用方 finally 释放。
  if(!slots.tryAcquire()) throw new ShopException(429,"当前下单人数较多，请稍后重试");
  try {
   // 两个键共用 Redis Cluster hash tag；限流只保护入口，商品库存仍以 MySQL 为准。
   Long allowed=redis.execute(LIMIT,Arrays.asList("shop:{admission}:global","shop:{admission}:user:"+user),
       String.valueOf(globalLimit),String.valueOf(userLimit));
   if(!Long.valueOf(1).equals(allowed)) throw new ShopException(429,"下单过于频繁，请稍后重试");
  } catch(RuntimeException error) {
   slots.release();
   if(error instanceof ShopException) throw error;
   throw new ShopException(503,"下单受理服务暂不可用，请稍后使用原请求编号重试");
  }
 }
 /** 必须在已成功 enter 的 finally 中释放本机名额。 */
 public void leave() { slots.release(); }
}
