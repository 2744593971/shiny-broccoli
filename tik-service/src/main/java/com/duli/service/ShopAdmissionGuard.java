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
 /** 非阻塞受理；Redis 不可用时快速返回 503，禁止降级为无保护直写库存。 */
 public void enter(String user) {
  if(user==null||user.trim().isEmpty()) throw new ShopException(401,"请先登录");
  if(!slots.tryAcquire()) throw new ShopException(429,"当前下单人数较多，请稍后重试");
  try {
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
