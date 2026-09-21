package com.duli.service;

import com.duli.config.RabbitMQConfig;
import com.duli.dto.MessageMQDTO;
import com.duli.enums.MessageEnum;
import com.duli.mapper.MessageOutboxRepository;
import com.duli.pojo.MessageOutbox;
import com.duli.service.impl.MessageOutboxServiceImpl;
import com.duli.service.mq.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** ==================== Codex 优化：真实SQL事务验证，不用“mock事务”假装验证回滚 ==================== */
class MessageOutboxTest {
    JdbcTemplate jdbc;
    MessageOutboxRepository repository;
    IMessageOutboxService outbox;
    ConfirmedNotificationSender sender;
    MessageOutboxPublisher publisher;
    TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        JdbcDataSource source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:outbox"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc=new JdbcTemplate(source);
        String schema=new String(Files.readAllBytes(Paths.get("../sql/message/001_reliable_messages.sql")),StandardCharsets.UTF_8)
            .replaceAll("COMMENT\\s*=?\\s*'[^']*'","").replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
        try(Connection c=source.getConnection()) { ScriptUtils.executeSqlScript(c,new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8))); }
        jdbc.execute("CREATE TABLE business_record(id VARCHAR(32) PRIMARY KEY)");
        DataSourceTransactionManager manager=new DataSourceTransactionManager(source);
        tx=new TransactionTemplate(manager);
        repository=new MessageOutboxRepository(jdbc);
        MessageOutboxServiceImpl target=new MessageOutboxServiceImpl(repository,new ObjectMapper());
        ProxyFactory proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        outbox=(IMessageOutboxService)proxy.getProxy();
        sender=mock(ConfirmedNotificationSender.class);
        publisher=new MessageOutboxPublisher(repository,sender);
    }
    MessageMQDTO event() {
        MessageMQDTO event=new MessageMQDTO();
        event.setFromUserId("sender");event.setToUserId("receiver");event.setMsgType(MessageEnum.FOLLOW_YOU.type);
        event.setMsgContent(Collections.singletonMap("isFriend",false));return event;
    }
    String commitEvent() {
        MessageMQDTO event=event();
        tx.execute(status->{outbox.enqueue(RabbitMQConfig.EXCHANGE_MSG,"sys.msg.follow",event);return null;});
        return event.getEventId();
    }
    int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    String state(String id) {return jdbc.queryForObject("SELECT status FROM message_outbox WHERE id=?",String.class,id);}
    void dueNow(String id) {
        jdbc.update("UPDATE message_outbox SET next_attempt_at=?,lease_until=? WHERE id=?",
            new Timestamp(System.currentTimeMillis()-2000),new Timestamp(System.currentTimeMillis()-2000),id);
    }
    @Test void withoutBusinessTransactionEnqueueIsRejected() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
            ()->outbox.enqueue(RabbitMQConfig.EXCHANGE_MSG,"sys.msg.follow",event()));
        assertEquals(0,count("message_outbox"));
    }
    @Test void rollbackRemovesBusinessAndEventAndNeverSends() {
        assertThrows(IllegalStateException.class,()->tx.execute(status->{
            jdbc.update("INSERT INTO business_record VALUES('business')");
            outbox.enqueue(RabbitMQConfig.EXCHANGE_MSG,"sys.msg.follow",event());
            throw new IllegalStateException("business fails after enqueue");
        }));
        assertEquals(0,count("business_record"));assertEquals(0,count("message_outbox"));
        publisher.publishBatch();verifyNoInteractions(sender);
    }
    @Test void checkedBusinessExceptionAlsoRollsBackWhenConfigured() throws Exception {
        ProxyFactory proxy=new ProxyFactory(new CheckedBusiness(businessMapper(),outbox));
        proxy.addAdvice(new TransactionInterceptor(tx.getTransactionManager(),new AnnotationTransactionAttributeSource()));
        CheckedBusiness business=(CheckedBusiness)proxy.getProxy();
        assertThrows(java.io.IOException.class,()->business.writeThenFail(event()));
        assertEquals(0,count("business_record"));assertEquals(0,count("message_outbox"));
        publisher.publishBatch();verifyNoInteractions(sender);
    }
    @Test void commitsBusinessAndEventWithoutDependingOnBroker() throws Exception {
        doThrow(new IllegalStateException("broker unavailable")).when(sender).send(any());
        String id=tx.execute(status->{
            jdbc.update("INSERT INTO business_record VALUES('business')");
            MessageMQDTO event=event();outbox.enqueue(RabbitMQConfig.EXCHANGE_MSG,"sys.msg.follow",event);
            verifyNoInteractions(sender);return event.getEventId();
        });
        assertEquals(1,count("business_record"));assertEquals("PENDING",state(id));
        publisher.publishBatch();
        assertEquals("PENDING",state(id));assertEquals(1,count("business_record"));
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM message_outbox",Integer.class));
    }
    @Test void outboxInsertFailureRollsBackBusiness() {
        jdbc.execute("DROP TABLE message_outbox"); // 仅删除每个测试独享的内存表，绝不连接用户数据库。
        assertThrows(org.springframework.dao.DataAccessException.class,()->tx.execute(status->{
            jdbc.update("INSERT INTO business_record VALUES('business')");
            outbox.enqueue(RabbitMQConfig.EXCHANGE_MSG,"sys.msg.follow",event());return null;
        }));
        assertEquals(0,count("business_record"));
    }
    @Test void uncommittedEventsCannotBeSeenFromPublisherConnection() throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            tx.execute(status->{
                outbox.enqueue(RabbitMQConfig.EXCHANGE_MSG,"sys.msg.follow",event());
                try { assertTrue(worker.submit(()->repository.dueIds()).get(3,TimeUnit.SECONDS).isEmpty()); }
                catch(Exception e) {throw new RuntimeException(e);}
                assertThrows(IllegalStateException.class,()->publisher.publishBatch());
                return null;
            });
            assertEquals(1,repository.dueIds().size());
        } finally { worker.shutdownNow(); }
    }
    @Test void confirmedPublishMarksSent() throws Exception {
        String id=commitEvent();publisher.publishBatch();
        assertEquals("SENT",state(id));
        verify(sender,times(1)).send(any());publisher.publishBatch();verifyNoMoreInteractions(sender);
    }
    @Test void transientFailureRecoversWithSameEventId() throws Exception {
        String id=commitEvent();
        doThrow(new IllegalStateException("offline")).doNothing().when(sender).send(any());
        publisher.publishBatch();assertEquals("PENDING",state(id));
        assertTrue(repository.dueIds().isEmpty());dueNow(id);publisher.publishBatch();
        assertEquals("SENT",state(id));
        org.mockito.ArgumentCaptor<MessageOutbox> capture=org.mockito.ArgumentCaptor.forClass(MessageOutbox.class);
        verify(sender,times(2)).send(capture.capture());
        assertEquals(id,capture.getAllValues().get(0).getId());
        assertEquals(id,capture.getAllValues().get(1).getId());
        assertNotEquals(capture.getAllValues().get(0).getLeaseToken(),capture.getAllValues().get(1).getLeaseToken());
    }
    @Test void concurrentPublishersCannotClaimSameLease() throws Exception {
        String id=commitEvent();ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<MessageOutbox> work=()->{start.await();return repository.claim(id,UUID.randomUUID().toString().replace("-",""));};
            Future<MessageOutbox> a=pool.submit(work),b=pool.submit(work);start.countDown();
            int winners=(a.get(5,TimeUnit.SECONDS)!=null?1:0)+(b.get(5,TimeUnit.SECONDS)!=null?1:0);
            assertEquals(1,winners);
        } finally {pool.shutdownNow();}
    }
    @Test void expiredLeaseRecoversAndOldWorkerCannotOverwrite() {
        String id=commitEvent();MessageOutbox old=repository.claim(id,"old");
        dueNow(id);MessageOutbox fresh=repository.claim(id,"fresh");
        assertNotNull(fresh);repository.sent(old);assertEquals("IN_FLIGHT",state(id));
        repository.failed(old,"old failure");assertEquals("IN_FLIGHT",state(id));
        repository.sent(fresh);assertEquals("SENT",state(id));
    }
    @Test void boundedRetriesLeaveAnAuditableFailedRecord() throws Exception {
        String id=commitEvent();doThrow(new IllegalStateException("offline")).when(sender).send(any());
        for(int n=0;n<10;n++){dueNow(id);publisher.publishBatch();}
        assertEquals("FAILED",state(id));assertEquals(1,repository.failedCount());
        publisher.publishBatch();verify(sender,times(10)).send(any());
        assertEquals(1,count("message_outbox"));
    }
    @Test void crashOnLastAttemptDoesNotLeavePermanentInFlight() {
        String id=commitEvent();
        jdbc.update("UPDATE message_outbox SET attempts=9 WHERE id=?",id);
        assertEquals(10,repository.claim(id,"last").getAttempts());
        dueNow(id);repository.expireExhausted();assertEquals("FAILED",state(id));
    }
    @Test void malformedEventRollsBackBusiness() {
        assertThrows(IllegalArgumentException.class,()->tx.execute(status->{
            jdbc.update("INSERT INTO business_record VALUES('business')");
            MessageMQDTO event=event();event.setMsgType(-1);
            outbox.enqueue(RabbitMQConfig.EXCHANGE_MSG,"sys.msg.follow",event);return null;
        }));
        assertEquals(0,count("business_record"));assertEquals(0,count("message_outbox"));
    }
    // Verify that MyBatis business writes and JDBC Outbox writes share one transaction.
    public interface BusinessMapper {
        @org.apache.ibatis.annotations.Insert("INSERT INTO business_record(id) VALUES(#{id})")
        void insert(String id);
    }
    BusinessMapper businessMapper() throws Exception {
        org.mybatis.spring.SqlSessionFactoryBean factory=new org.mybatis.spring.SqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        org.apache.ibatis.session.SqlSessionFactory sessions=factory.getObject();
        sessions.getConfiguration().addMapper(BusinessMapper.class);
        return new org.mybatis.spring.SqlSessionTemplate(sessions).getMapper(BusinessMapper.class);
    }
    public static class CheckedBusiness {
        private final BusinessMapper mapper;
        private final IMessageOutboxService outbox;
        public CheckedBusiness(BusinessMapper mapper,IMessageOutboxService outbox) {
            this.mapper=mapper;this.outbox=outbox;
        }
        @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
        public void writeThenFail(MessageMQDTO event) throws java.io.IOException {
            mapper.insert("checked");
            outbox.enqueue(RabbitMQConfig.EXCHANGE_MSG,"sys.msg.follow",event);
            throw new java.io.IOException("failure after enqueue");
        }
    }
    @Test void myBatisBusinessAndJdbcOutboxCommitTogether() throws Exception {
        BusinessMapper mapper=businessMapper();
        String id=tx.execute(status->{
            mapper.insert("mybatis");
            MessageMQDTO event=event();
            outbox.enqueue(RabbitMQConfig.EXCHANGE_MSG,"sys.msg.follow",event);
            verifyNoInteractions(sender);
            return event.getEventId();
        });
        assertEquals(1,count("business_record"));assertEquals("PENDING",state(id));
    }
    @Test void caughtEnqueueFailureStillRollsBackMyBatisBusiness() throws Exception {
        BusinessMapper mapper=businessMapper();
        assertThrows(org.springframework.transaction.UnexpectedRollbackException.class,()->tx.execute(status->{
            mapper.insert("caught");
            MessageMQDTO invalid=event();invalid.setMsgType(-1);
            assertThrows(IllegalArgumentException.class,
                ()->outbox.enqueue(RabbitMQConfig.EXCHANGE_MSG,"sys.msg.follow",invalid));
            return null;
        }));
        assertEquals(0,count("business_record"));assertEquals(0,count("message_outbox"));
        publisher.publishBatch();verifyNoInteractions(sender);
    }
}
