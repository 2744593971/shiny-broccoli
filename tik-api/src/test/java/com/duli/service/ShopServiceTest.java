package com.duli.service;

import com.duli.service.impl.ShopServiceImpl;
import com.duli.mapper.ShopRepository;
import com.duli.bo.ShopOrderBO;
import com.duli.pojo.ShopOrder;
import com.duli.exceptions.ShopException;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真正执行 SQL 和事务的回归测试。H2 MySQL 模式不是线上 MySQL 压测替代品。 */
class ShopServiceTest {
    JdbcTemplate jdbc;
    ShopRepository repository;
    ShopServiceImpl service;
    DataSourceTransactionManager manager;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:shop" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        // 直接复用交付的建表脚本，只移除 H2 不支持的 MySQL 存储引擎和中文 COMMENT。
        String schema = (new String(Files.readAllBytes(Paths.get("../sql/shop/001_shop_schema.sql")), StandardCharsets.UTF_8)
            + new String(Files.readAllBytes(Paths.get("../sql/shop/003_shop_trade.sql")), StandardCharsets.UTF_8)
            + new String(Files.readAllBytes(Paths.get("../sql/shop/004_shop_payment_reliability.sql")), StandardCharsets.UTF_8)
            + new String(Files.readAllBytes(Paths.get("../sql/shop/005_shop_queue_messages.sql")), StandardCharsets.UTF_8))
            .replaceAll("COMMENT\\s*=?\\s*'[^']*'", "")
            .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "")
            .replaceAll(",\\s*ADD COLUMN", "; ALTER TABLE shop_order ADD COLUMN")
            .replaceAll(",\\s*ADD INDEX idx_shop_order_expiry", "; CREATE INDEX idx_shop_order_expiry ON shop_order");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
        }
        repository = new ShopRepository(jdbc);
        manager = new DataSourceTransactionManager(source);
        service = new ShopServiceImpl(repository, manager, new com.duli.mapper.ShopTradeEventRepository(jdbc));
        jdbc.update("INSERT INTO shop_product(id,title,description,price,stock) VALUES('p','商品','说明',199,50)");
        jdbc.update("INSERT INTO shop_activity(id,product_id,title,price,stock,starts_at,ends_at) VALUES('a','p','活动',9.9,5,?,?)",
            new Timestamp(System.currentTimeMillis()-60000), new Timestamp(System.currentTimeMillis()+3600000));
    }
    ShopOrderBO input(String activity, String request) {
        ShopOrderBO input = new ShopOrderBO();
        input.setProductId("p"); input.setActivityId(activity); input.setRequestId(request);
        input.setReceiverName("测试收货人");input.setReceiverPhone("13800000000");input.setReceiverAddress("测试省测试市测试街道 1 号");
        return input;
    }
    int number(String sql) { return jdbc.queryForObject(sql, Integer.class); }

    @Test void normalPriceIdempotencyAndRepeatPurchases() {
        ShopOrder first = service.place("u", input(null,"request_normal_01"));
        assertEquals(0, first.getAmount().compareTo(new java.math.BigDecimal("199")));
        assertEquals(first.getId(), service.place("u", input(null,"request_normal_01")).getId());
        assertNotEquals(first.getId(), service.place("u", input(null,"request_normal_02")).getId());
        assertEquals(48, number("SELECT stock FROM shop_product"));
        assertEquals(5, number("SELECT stock FROM shop_activity"));
        assertEquals(2, number("SELECT COUNT(*) FROM shop_order"));
    }
    @Test void activityLimitPriceAndRetryAfterExpiry() {
        ShopOrder first = service.place("u",input("a","request_flash_01"));
        assertEquals(0, first.getAmount().compareTo(new java.math.BigDecimal("9.90")));
        jdbc.update("UPDATE shop_activity SET ends_at=?",new Timestamp(System.currentTimeMillis()-1000));
        assertEquals(first.getId(),service.place("u",input("a","request_flash_02")).getId());
        assertEquals(1,number("SELECT COUNT(*) FROM shop_order"));
        assertEquals(49,number("SELECT stock FROM shop_product"));
        assertEquals(4,number("SELECT stock FROM shop_activity"));
    }
    @Test void notStartedAndExpiredAreRejected() {
        jdbc.update("UPDATE shop_activity SET starts_at=?",new Timestamp(System.currentTimeMillis()+60000));
        assertEquals(409,assertThrows(ShopException.class,()->service.place("u",input("a","request_future_1"))).getCode());
        jdbc.update("UPDATE shop_activity SET starts_at=?,ends_at=?",new Timestamp(System.currentTimeMillis()-60000),new Timestamp(System.currentTimeMillis()-1000));
        assertThrows(ShopException.class,()->service.place("u",input("a","request_expired1")));
        assertEquals(0,number("SELECT COUNT(*) FROM shop_order"));
    }
    @Test void productSoldOutRollsBackActivityQuota() {
        jdbc.update("UPDATE shop_product SET stock=0");
        assertThrows(ShopException.class,()->service.place("u",input("a","request_soldout1")));
        assertEquals(5,number("SELECT stock FROM shop_activity"));
        assertEquals(0,number("SELECT COUNT(*) FROM shop_order"));
    }
    @Test void insertFailureRollsBackBothStocks() {
        ShopRepository broken = spy(repository);
        doThrow(new IllegalStateException("simulate storage failure")).when(broken).insert(any(ShopOrder.class));
        ShopServiceImpl brokenService = new ShopServiceImpl(broken,manager,new com.duli.mapper.ShopTradeEventRepository(jdbc));
        assertThrows(IllegalStateException.class,()->brokenService.place("u",input("a","request_failure1")));
        assertEquals(5,number("SELECT stock FROM shop_activity"));
        assertEquals(50,number("SELECT stock FROM shop_product"));
    }
    @Test void requestCannotBeReusedForDifferentPurchase() {
        service.place("u",input(null,"request_conflict"));
        assertEquals(409,assertThrows(ShopException.class,()->service.place("u",input("a","request_conflict"))).getCode());
    }
    @Test void ordersArePrivateAndPaginationBounded() {
        ShopOrder order = service.place("alice",input(null,"request_private1"));
        assertEquals(order.getId(),service.order("alice",order.getId()).getId());
        assertEquals(404,assertThrows(ShopException.class,()->service.order("bob",order.getId())).getCode());
        assertTrue(((List<?>)service.orders("bob",1,12).get("rows")).isEmpty());
        assertThrows(ShopException.class,()->service.products(0,12,false));
        assertThrows(ShopException.class,()->service.products(1,51,false));
        assertThrows(ShopException.class,()->service.place(null,input(null,"request_anonymous")));
    }
    @Test void fortyBuyersCannotOversellFiveSlots() throws Exception {
        List<ShopOrder> orders = race(40,false,false);
        assertEquals(5,orders.size());
        assertEquals(5,number("SELECT COUNT(*) FROM shop_order"));
        assertEquals(0,number("SELECT stock FROM shop_activity"));
        assertEquals(45,number("SELECT stock FROM shop_product"));
    }
    @Test void concurrentSameUserDifferentRequestsOnlyDeductOnce() throws Exception {
        List<ShopOrder> orders = race(20,true,false);
        assertEquals(20,orders.size());
        assertEquals(1,orders.stream().map(ShopOrder::getId).distinct().count());
        assertEquals(1,number("SELECT COUNT(*) FROM shop_order"));
        assertEquals(49,number("SELECT stock FROM shop_product"));
        assertEquals(4,number("SELECT stock FROM shop_activity"));
    }
    @Test void concurrentNormalAndFlashShareProductStock() throws Exception {
        jdbc.update("UPDATE shop_product SET stock=3");
        List<ShopOrder> orders = race(20,false,true);
        assertEquals(3,orders.size());
        assertEquals(0,number("SELECT stock FROM shop_product"));
        assertEquals(3,number("SELECT COUNT(*) FROM shop_order"));
        assertEquals(5-number("SELECT COUNT(*) FROM shop_order WHERE activity_id IS NOT NULL"),number("SELECT stock FROM shop_activity"));
    }
    // 所有任务使用独立事务/连接；启动闸门同时放行，真实竞争同一库存行。
    List<ShopOrder> race(int count, boolean sameUser, boolean mixed) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ShopOrder>> futures = new ArrayList<>();
        try {
            for(int i=0;i<count;i++) {
                final int n=i;
                futures.add(pool.submit(()->{
                    gate.await();
                    try { return service.place(sameUser?"u":"u"+n,input(mixed&&n%2==0?null:"a","request_concurrent_"+n)); }
                    catch(ShopException ex) { if(ex.getCode()!=409) throw ex; return null; }
                }));
            }
            gate.countDown();
            List<ShopOrder> result = new ArrayList<>();
            for(Future<ShopOrder> future:futures) { ShopOrder order=future.get(30,TimeUnit.SECONDS); if(order!=null) result.add(order); }
            return result;
        } finally { pool.shutdownNow(); }
    }
}
