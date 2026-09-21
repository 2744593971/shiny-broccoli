package com.duli.service;
import com.duli.bo.ShopOrderBO;
import com.duli.pojo.ShopOrder;
import java.util.Map;

/** 商城业务接口，与原项目 controller -> service/impl -> mapper 分层一致。 */
public interface IShopService {
    Map<String,Object> products(int page,int size,boolean seckill);
    Map<String,Object> detail(String productId,String activityId);
    Map<String,Object> orders(String userId,int page,int size);
    ShopOrder order(String userId,String id);
    ShopOrder orderByRequest(String userId,String requestId);
    ShopOrder place(String userId,ShopOrderBO input);
}
