package com.duli.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duli.pojo.Vlog; // 你的实体类
import com.duli.vo.IndexVlogVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface VlogMapper extends BaseMapper<Vlog> {
    // 🌟 把自定义的多表联查方法直接加在这里
    //第一个参数加page使得sql出来的结果能分页
    Page<IndexVlogVO> getIndexVlogList(@Param("page") Page<IndexVlogVO> pageParam,
                                       @Param("paramMap") Map<String, Object> paramMap);

    // 🌟 修改：区分 vlogerId (被查的博主) 和 currentUserId (当前登录用户)
    // ⚠️ 记得第一个参数必须是 Page 对象
    Page<IndexVlogVO> getMyVlogList(Page<IndexVlogVO> page,
                                    @Param("vlogerId") String vlogerId,
                                    @Param("currentUserId") String currentUserId,
                                    @Param("isPrivate") Integer isPrivate);

    // ⚠️ 第一个参数必须是 Page 对象
    Page<IndexVlogVO> getMyLikedList(Page<IndexVlogVO> page,
                                     @Param("userId") String userId);
    // 连表查询视频详情，以及当前用户对该视频的点赞/关注状态
    List<IndexVlogVO> getVlogDetailById(@Param("userId") String userId,
                                        @Param("vlogId") String vlogId);

    Page<IndexVlogVO> getMyFollowVlogList(@Param("page") Page<IndexVlogVO> page,
                                          @Param("myId") String myId);


    Page<IndexVlogVO> getMyFriendVlogList(@Param("page") Page<IndexVlogVO> page,
                                          @Param("myId") String myId);

    //List<IndexVlogVO> getIndexVlogListByCursor(@Param("paramMap") Map<String, Object> paramMap);
}