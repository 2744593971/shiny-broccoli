package com.duli.service;
import com.duli.exceptions.ShopException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Redis 执行边界模拟；真实 Lua/多实例吞吐需在目标 Redis 集群联调。 */
class ShopAdmissionGuardTest {
 /** 本机无等待闸门达到上限后拒绝，释放后可继续受理。 */
 @Test @SuppressWarnings("unchecked") void localBulkheadIsBounded() {
  StringRedisTemplate redis=mock(StringRedisTemplate.class);
  when(redis.execute(any(RedisScript.class),anyList(),any(),any())).thenReturn(1L);
  ShopAdmissionGuard guard=new ShopAdmissionGuard(redis,200,3,2);
  guard.enter("u");guard.enter("v");
  assertEquals(429,assertThrows(ShopException.class,()->guard.enter("w")).getCode());
  guard.leave();guard.enter("w");guard.leave();guard.leave();
 }
 /** 限流和 Redis 故障都释放本机名额，故障不能降级为无保护写库存。 */
 @Test @SuppressWarnings("unchecked") void redisRejectionAndFailureReleaseSlots() {
  StringRedisTemplate redis=mock(StringRedisTemplate.class);
  when(redis.execute(any(RedisScript.class),anyList(),any(),any()))
      .thenReturn(0L).thenThrow(new IllegalStateException("offline")).thenReturn(1L);
  ShopAdmissionGuard guard=new ShopAdmissionGuard(redis,200,3,1);
  assertEquals(429,assertThrows(ShopException.class,()->guard.enter("u")).getCode());
  assertEquals(503,assertThrows(ShopException.class,()->guard.enter("u")).getCode());
  assertDoesNotThrow(()->guard.enter("u"));guard.leave();
 }
}
