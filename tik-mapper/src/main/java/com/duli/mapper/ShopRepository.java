package com.duli.mapper;

import com.duli.pojo.ShopOrder;
import com.duli.vo.ShopItem;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * 商城独立使用参数化 JDBC SQL，不干扰视频模块的 MyBatis 分页插件。
 * 所有用户输入均通过 ? 绑定，不能把商品编号或用户编号拼接到 SQL。
 * 扣库存必须在 ShopService 的同一个数据库事务中执行。
 */
@Repository
public class ShopRepository {
    private final JdbcTemplate jdbc;
    public ShopRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String PRODUCT_COLUMNS =
            "p.id, p.title, p.description, p.cover, p.price, p.stock";
    private static final String ACTIVITY_COLUMNS = PRODUCT_COLUMNS +
            ", a.id activity_id, a.title activity_title, a.price seckill_price," +
            " a.stock activity_stock, a.starts_at, a.ends_at";
    private static final String ACTIVITY_FROM =
            " FROM shop_activity a JOIN shop_product p ON p.id=a.product_id";

    private static final RowMapper<ShopItem> PRODUCT = (rs, row) -> {
        ShopItem item = new ShopItem();
        item.setId(rs.getString("id"));
        item.setTitle(rs.getString("title"));
        item.setDescription(rs.getString("description"));
        item.setCover(rs.getString("cover"));
        item.setPrice(rs.getBigDecimal("price"));
        item.setStock(rs.getInt("stock"));
        return item;
    };
    private static final RowMapper<ShopItem> ACTIVITY = (rs, row) -> {
        ShopItem item = PRODUCT.mapRow(rs, row);
        item.setActivityId(rs.getString("activity_id"));
        item.setActivityTitle(rs.getString("activity_title"));
        item.setSeckillPrice(rs.getBigDecimal("seckill_price"));
        item.setActivityStock(rs.getInt("activity_stock"));
        item.setStartsAt(rs.getTimestamp("starts_at").getTime());
        item.setEndsAt(rs.getTimestamp("ends_at").getTime());
        return item;
    };
    static final RowMapper<ShopOrder> ORDER = (rs, row) -> {
        ShopOrder order = new ShopOrder();
        order.setId(rs.getString("id"));
        order.setUserId(rs.getString("user_id"));
        order.setProductId(rs.getString("product_id"));
        order.setActivityId(rs.getString("activity_id"));
        order.setRequestId(rs.getString("request_id"));
        order.setTitle(rs.getString("title"));
        order.setCover(rs.getString("cover"));
        order.setAmount(rs.getBigDecimal("amount"));
        order.setStatus(rs.getString("status"));
        order.setCreatedAt(rs.getTimestamp("created_at").getTime());
        order.setReceiverName(rs.getString("receiver_name"));
        order.setReceiverPhone(rs.getString("receiver_phone"));
        order.setReceiverAddress(rs.getString("receiver_address"));
        order.setExpiresAt(millis(rs.getTimestamp("expires_at")));
        order.setPaidAt(millis(rs.getTimestamp("paid_at")));
        order.setShippedAt(millis(rs.getTimestamp("shipped_at")));
        order.setReceivedAt(millis(rs.getTimestamp("received_at")));
        order.setRefundedAt(millis(rs.getTimestamp("refunded_at")));
        order.setCarrier(rs.getString("carrier"));
        order.setTrackingNo(rs.getString("tracking_no"));
        order.setPaymentChannel(rs.getString("payment_channel"));
        return order;
    };

    /** 数据库时间是活动开始、结束的唯一裁决时钟。 */
    public long now() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class).getTime();
    }
    public List<ShopItem> products(int offset, int limit) {
        return jdbc.query("SELECT " + PRODUCT_COLUMNS +
                " FROM shop_product p WHERE p.enabled=1 ORDER BY p.id LIMIT ? OFFSET ?",
                PRODUCT, limit, offset);
    }
    /** 展示正在进行和即将开始的活动；已结束活动仍可通过详情看到结束状态。 */
    public List<ShopItem> activities(int offset, int limit) {
        return jdbc.query("SELECT " + ACTIVITY_COLUMNS + ACTIVITY_FROM +
                " WHERE p.enabled=1 AND a.enabled=1 AND a.ends_at>CURRENT_TIMESTAMP" +
                " ORDER BY a.starts_at,a.id LIMIT ? OFFSET ?", ACTIVITY, limit, offset);
    }
    public ShopItem product(String id) {
        return first(jdbc.query("SELECT " + PRODUCT_COLUMNS +
                " FROM shop_product p WHERE p.id=? AND p.enabled=1", PRODUCT, id));
    }
    public ShopItem activity(String id) {
        return first(jdbc.query("SELECT " + ACTIVITY_COLUMNS + ACTIVITY_FROM +
                " WHERE a.id=? AND a.enabled=1 AND p.enabled=1", ACTIVITY, id));
    }

    /**
     * UPDATE 的行锁和 stock>0 条件一起保证不超卖，不能换成“先查库存再无条件减一”。
     * 普通购买与秒杀均扣同一商品总库存；活动库存只是该场活动的独立配额。
     */
    public int takeProduct(String id) {
        return jdbc.update("UPDATE shop_product SET stock=stock-1" +
                " WHERE id=? AND enabled=1 AND stock>0", id);
    }
    public int takeActivity(String id) {
        return jdbc.update("UPDATE shop_activity SET stock=stock-1 WHERE id=? AND enabled=1" +
                " AND stock>0 AND starts_at<=CURRENT_TIMESTAMP AND ends_at>CURRENT_TIMESTAMP", id);
    }
    public void insert(ShopOrder order) {
        jdbc.update("INSERT INTO shop_order" +
                " (id,user_id,product_id,activity_id,request_id,title,cover,amount,status,created_at," +
                "receiver_name,receiver_phone,receiver_address,expires_at)" +
                " VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?,?,?)",
                order.getId(), order.getUserId(), order.getProductId(), order.getActivityId(),
                order.getRequestId(), order.getTitle(), order.getCover(), order.getAmount(), order.getStatus(),
                order.getReceiverName(),order.getReceiverPhone(),order.getReceiverAddress(),
                new java.sql.Timestamp(order.getExpiresAt()));
    }
    /** 查询和删除类操作都必须把当前用户写进 WHERE，不能只依靠前端隐藏按钮。 */
    public ShopOrder order(String id, String userId) {
        return first(jdbc.query("SELECT * FROM shop_order WHERE id=? AND user_id=?", ORDER, id, userId));
    }
    public ShopOrder byRequest(String userId, String requestId) {
        return first(jdbc.query("SELECT * FROM shop_order WHERE user_id=? AND request_id=?",
                ORDER, userId, requestId));
    }
    public ShopOrder byActivity(String userId, String activityId) {
        return first(jdbc.query("SELECT * FROM shop_order WHERE user_id=? AND activity_id=?",
                ORDER, userId, activityId));
    }
    public List<ShopOrder> orders(String userId, int offset, int limit) {
        return jdbc.query("SELECT * FROM shop_order WHERE user_id=?" +
                " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", ORDER, userId, limit, offset);
    }
    private static <T> T first(List<T> rows) { return rows.isEmpty() ? null : rows.get(0); }
    private static Long millis(java.sql.Timestamp value) { return value == null ? null : value.getTime(); }
}
