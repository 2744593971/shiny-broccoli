package com.duli.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.duli.bo.VlogBO;
import com.duli.pojo.Vlog;
import com.duli.vo.IndexVlogVO;

import java.util.Map;

/**
 * <p>
 * 短视频表 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-05
 */
public interface IVlogService extends IService<Vlog> {

    void createVlog(VlogBO vlogBO);
    //首页视频列表
    Map<String, Object> getIndexVlogList(String userId, String search, Integer page, Integer pageSize);

    // 🌟 修改：加入 currentUserId 参数用来判断点赞/关注状态
    Map<String, Object> getMyVlogList(String vlogerId, String currentUserId, Integer isPrivate, Integer page, Integer pageSize);

    Map<String, Object> getMyLikedList(String userId, String currentUserId, Integer page, Integer pageSize);

    // 查询视频详情
    IndexVlogVO getVlogDetailById(String userId, String vlogId);

    // 🌟 新增：点赞与取消点赞的方法定义
    void userLikeVlog(String userId, String vlogId, String vlogerId);
    void userUnLikeVlog(String userId, String vlogId, String vlogerId);

    /**
     * 修改视频的私密/公开状态
     * @param userId 视频作者的ID
     * @param vlogId 视频的ID
     * @param yesOrNo 1: 私密, 0: 公开
     */
    void changeToPrivateOrPublic(String userId, String vlogId, Integer yesOrNo);
    /**
     * 根据视频主键查询视频的当前总点赞数
     */
    Integer getVlogBeLikedCounts(String vlogId);

    // 查询我关注的博主发布的视频列表
    Map<String, Object> getMyFollowVlogList(String myId, Integer page, Integer pageSize);


    Map<String, Object> getMyFriendVlogList(String myId, Integer page, Integer pageSize);
}
