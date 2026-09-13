package com.duli.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duli.pojo.Fans;
import com.duli.vo.FansVO;
import com.duli.vo.VlogerVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
/**
 * <p>
 * 粉丝表

 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-09-05
 */
public interface FansMapper extends BaseMapper<Fans> {

    // 查询我的关注列表
    List<VlogerVO> queryMyFollows(@Param("myId") String myId);

    // 查询我的粉丝列表
    List<FansVO> queryMyFans(@Param("myId") String myId);

}
