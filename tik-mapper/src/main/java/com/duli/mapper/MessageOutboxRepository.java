package com.duli.mapper;
import com.duli.pojo.MessageOutbox;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.*;

/** ==================== Codex 优化：业务事务内落表，投递使用独立短事务/原子更新 ==================== */
@Repository
public class MessageOutboxRepository {
    private final JdbcTemplate jdbc;
    public MessageOutboxRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public long now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Timestamp.class).getTime(); }
    public void insert(String id,String routingKey,String payload) {
        jdbc.update("INSERT INTO message_outbox(id,routing_key,payload) VALUES(?,?,?)",id,routingKey,payload);
    }
    private static final String DUE="((status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP) OR "
        +"(status='IN_FLIGHT' AND lease_until<=CURRENT_TIMESTAMP))";
    public List<String> dueIds() {
        return jdbc.queryForList("SELECT id FROM message_outbox WHERE "+DUE+" AND attempts<10 ORDER BY created_at,id LIMIT 20",String.class);
    }
    public MessageOutbox claim(String id,String token) {
        // 比较更新抢占；多进程抢到同一条时只有一个成功。锁不覆盖网络等待。
        if(jdbc.update("UPDATE message_outbox SET status='IN_FLIGHT',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=CURRENT_TIMESTAMP "
                +"WHERE id=? AND "+DUE+" AND attempts<10",token,new Timestamp(now()+60000),id)!=1) return null;
        return jdbc.queryForObject("SELECT * FROM message_outbox WHERE id=? AND lease_token=?",(rs,n)->{
            MessageOutbox event=new MessageOutbox();event.setId(rs.getString("id"));event.setPayload(rs.getString("payload"));
            event.setRoutingKey(rs.getString("routing_key"));event.setLeaseToken(rs.getString("lease_token"));
            event.setAttempts(rs.getInt("attempts"));return event;
        },id,token);
    }
    public void sent(MessageOutbox event) {
        // ACK 丢失可能重发；过期工作者不能覆盖新租约。固定 eventId 供消费端后续幂等改造使用。
        jdbc.update("UPDATE message_outbox SET status='SENT',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP "
            +"WHERE id=? AND status='IN_FLIGHT' AND lease_token=?",event.getId(),event.getLeaseToken());
    }
    public void failed(MessageOutbox event,String error) {
        long delay=Math.min(600,5L*(1L<<Math.min(event.getAttempts()-1,7)))*1000;
        jdbc.update("UPDATE message_outbox SET status=?,next_attempt_at=?,last_error=?,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP "
            +"WHERE id=? AND status='IN_FLIGHT' AND lease_token=?",
            event.getAttempts()>=10?"FAILED":"PENDING",new Timestamp(now()+delay),error,event.getId(),event.getLeaseToken());
    }
    public void expireExhausted() {
        // 第10次发送时进程崩溃也必须进入可观察的失败状态，不能永远卡在 IN_FLIGHT。
        jdbc.update("UPDATE message_outbox SET status='FAILED',last_error='LEASE_EXHAUSTED',lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP "
            +"WHERE "+DUE+" AND attempts>=10");
    }
    public long failedCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE status='FAILED'",Long.class); }
}
