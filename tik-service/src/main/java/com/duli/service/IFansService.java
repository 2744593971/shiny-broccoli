package com.duli.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.duli.pojo.Fans;

public interface IFansService extends IService<Fans> {
    
    // 查询是否关注
    boolean queryDoIFollowVloger(String myId, String vlogerId);
    
    // 执行关注
    void doFollow(String myId, String vlogerId);
    
    // 执行取消关注
    void doCancel(String myId, String vlogerId);

    // 返回Map是为了完美契合前端提取 rows 和 total 的格式
    java.util.Map<String, Object> queryMyFollows(String myId, Integer page, Integer pageSize);
    java.util.Map<String, Object> queryMyFans(String myId, Integer page, Integer pageSize);
}