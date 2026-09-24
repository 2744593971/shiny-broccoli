package com.duli.service;
import com.duli.mapper.ShopTradeEventRepository;
import com.duli.pojo.ShopOrderMessage;
import com.duli.exceptions.ShopException;
import org.springframework.stereotype.Service;
import java.util.*;
/** 通知读取服务；通知写入只发生在 RabbitMQ 消费事务中。 */
@Service
public class ShopOrderMessageService {
 private final ShopTradeEventRepository events;
 /** 注入消息访问层。 */
 public ShopOrderMessageService(ShopTradeEventRepository events) { this.events=events; }
 /** 查询当前用户消息及未读数；分页有界。 */
 // 秒杀成功、支付与关单等通知只按 JWT 用户查询；消息不承担订单状态的权威来源。
 public Map<String,Object> list(String user,int page,int size) {
  requireUser(user);
  if(page<1||page>10000||size<1||size>50) throw new ShopException(400,"分页参数超出范围");
  List<ShopOrderMessage> rows=events.messages(user,(page-1)*size,size+1);
  Map<String,Object> result=new LinkedHashMap<>();
  result.put("rows",rows.subList(0,Math.min(size,rows.size())));
  result.put("hasMore",rows.size()>size);result.put("unread",events.unread(user));return result;
 }
 /** 只更新本人消息，无法替其他用户清除未读。 */
 public void read(String user,String id) { requireUser(user);events.readMessage(user,id); }
 /** 阻止内部调用绕过登录检查。 */
 private void requireUser(String user) {
  if(user==null||user.trim().isEmpty()) throw new ShopException(401,"请先登录");
 }
}
