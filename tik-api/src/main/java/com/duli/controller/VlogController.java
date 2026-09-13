package com.duli.controller;

import com.duli.bo.VlogBO;
import com.duli.service.IVlogService;
import com.duli.vo.IndexVlogVO;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
// 假设你有一个统一的返回结果类 GraceJSONResult
import com.duli.grace.result.GraceJSONResult;
import org.apache.commons.lang3.StringUtils;
import javax.servlet.http.HttpServletRequest;

import java.util.Map;

@RestController
@RequestMapping("/vlog")
@CrossOrigin
public class VlogController {

    @Autowired
    private IVlogService vlogService;

    @PostMapping("/publish")
    public GraceJSONResult publish(@RequestBody VlogBO vlogBO) {
        // 🌟 修改校验：不仅防 null，还防空字符串
//        if (StringUtils.isBlank(vlogBO.getVlogerId()) || StringUtils.isBlank(vlogBO.getUrl())) {
//            return GraceJSONResult.errorMsg("视频还没上传完成，请稍后再试！");
//        }
        vlogService.createVlog(vlogBO);
        return GraceJSONResult.success();
    }


    @GetMapping("/indexList")
    public GraceJSONResult indexList(@RequestParam(defaultValue = "") String userId,
                                     @RequestParam(defaultValue = "") String search,
                                     @RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "10") Integer pageSize) {

        // 直接返回封装了 rows 和 total 的 Map
        Map<String, Object> map = vlogService.getIndexVlogList(userId, search, page, pageSize);
        return GraceJSONResult.success(map);
    }
    /*
@GetMapping("/indexList")
    public GraceJSONResult indexList(@RequestParam(defaultValue = "") String userId,
                                     @RequestParam(defaultValue = "") String search,
                                     @RequestParam(required = false) Long cursor, // 🌟 替换 page
                                     @RequestParam(defaultValue = "10") Integer pageSize) {

        // 🌟 核心防御：首次加载，如果没有传游标，默认取当前系统时间戳
        if (cursor == null) {
            cursor = System.currentTimeMillis();
        }

        // 调用 Service
        Map<String, Object> map = vlogService.getIndexVlogList(userId, search, cursor, pageSize);
        return GraceJSONResult.success(map);
    }
     */

    /**
     * 查询我的视频列表（支持区分公开/私密）
     * 对应前端请求：/vlog/myVlogList?userId=xxx&isPrivate=0&page=1&pageSize=10
     */
    // 1. 公开作品列表
    // 1. 公开作品列表
    @GetMapping("/myPublicList")
    public GraceJSONResult myPublicList(@RequestParam String userId, // 这里的 userId 其实是被查的博主 ID
                                        @RequestParam(defaultValue = "1") Integer page,
                                        @RequestParam(defaultValue = "10") Integer pageSize,
                                        HttpServletRequest request) { // 🌟 注入 request

        // 🌟 核心：从拦截器拿当前登录的观众 ID
        String currentUserId = (String) request.getAttribute("currentUserId");
        if (currentUserId == null) currentUserId = "";

        // isPrivate = 0 表示公开。传入 currentUserId 进行状态判断
        Map<String, Object> map = vlogService.getMyVlogList(userId, currentUserId, 0, page, pageSize);
        return GraceJSONResult.success(map);
    }

    // 2. 私密作品列表
    @GetMapping("/myPrivateList")
    public GraceJSONResult myPrivateList(@RequestParam String userId,
                                         @RequestParam(defaultValue = "1") Integer page,
                                         @RequestParam(defaultValue = "10") Integer pageSize,
                                         HttpServletRequest request) { // 🌟 同样注入 request

        String currentUserId = (String) request.getAttribute("currentUserId");
        if (currentUserId == null) currentUserId = "";

        Map<String, Object> map = vlogService.getMyVlogList(userId, currentUserId, 1, page, pageSize);
        return GraceJSONResult.success(map);
    }

    // 3. 赞过的视频列表
    @GetMapping("/myLikedList")
    public GraceJSONResult myLikedList(@RequestParam String userId,
                                       @RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "10") Integer pageSize) {
        // 调用查询点赞视频的方法
        Map<String, Object> map = vlogService.getMyLikedList(userId, page, pageSize);
        return GraceJSONResult.success(map);
    }

    /**
     * 查询单个视频详情
     * 对应前端请求：/vlog/detail?userId=xxx&vlogId=xxx
     */
    @GetMapping("/detail")
    public GraceJSONResult detail(@RequestParam(defaultValue = "") String userId,
                                  @RequestParam String vlogId) {

        if (StringUtils.isBlank(vlogId)) {
            return GraceJSONResult.errorMsg("视频ID不能为空");
        }

        // 调用 Service 获取视频详情
        IndexVlogVO vlogDetail = vlogService.getVlogDetailById(userId, vlogId);

        if (vlogDetail == null) {
            return GraceJSONResult.errorMsg("视频不存在或已被删除");
        }

        return GraceJSONResult.success(vlogDetail);
    }

    /**
     * 将视频转为私密
     * 对应前端请求：/vlog/changeToPrivate?userId=xxx&vlogId=xxx
     */
    @PostMapping("/changeToPrivate")
    public GraceJSONResult changeToPrivate(@RequestParam String userId,
                                           @RequestParam String vlogId) {
        // 传入 1 代表私密
        vlogService.changeToPrivateOrPublic(userId, vlogId, 1);
        return GraceJSONResult.success();
    }

    /**
     * 将视频转为公开
     * 对应前端请求：/vlog/changeToPublic?userId=xxx&vlogId=xxx
     */
    @PostMapping("/changeToPublic")
    public GraceJSONResult changeToPublic(@RequestParam String userId,
                                          @RequestParam String vlogId) {
        // 传入 0 代表公开
        vlogService.changeToPrivateOrPublic(userId, vlogId, 0);
        return GraceJSONResult.success();
    }

    @PostMapping("/like")
    public GraceJSONResult like(@RequestParam String userId,
                                @RequestParam String vlogerId,
                                @RequestParam String vlogId) {

        if (StringUtils.isBlank(userId) || StringUtils.isBlank(vlogerId) || StringUtils.isBlank(vlogId)) {
            return GraceJSONResult.errorMsg("参数不能为空");
        }
        vlogService.userLikeVlog(userId, vlogId, vlogerId);
        return GraceJSONResult.success();
    }

    @PostMapping("/unlike")
    public GraceJSONResult unlike(@RequestParam String userId,
                                  @RequestParam String vlogerId,
                                  @RequestParam String vlogId) {

        if (StringUtils.isBlank(userId) || StringUtils.isBlank(vlogerId) || StringUtils.isBlank(vlogId)) {
            return GraceJSONResult.errorMsg("参数不能为空");
        }
        vlogService.userUnLikeVlog(userId, vlogId, vlogerId);
        return GraceJSONResult.success();
    }

    @ApiOperation(value = "获取视频最新的总点赞数")
    @PostMapping("/totalLikedCounts")
    public GraceJSONResult totalLikedCounts(@RequestParam String vlogId) {

        if (StringUtils.isBlank(vlogId)) {
            return GraceJSONResult.errorMsg("视频ID不能为空");
        }

        // 调用 Service 去 Redis 拿最新数据
        Integer counts = vlogService.getVlogBeLikedCounts(vlogId);

        // 返回成功，并且把数字放在 data 里，契合前端的 result.data.data
        return GraceJSONResult.success(counts);
    }

    @ApiOperation(value = "查询我关注的博主的视频列表")
    @GetMapping("/followList")
    public GraceJSONResult followList(@RequestParam String myId,
                                      @RequestParam(defaultValue = "1") Integer page,
                                      @RequestParam(defaultValue = "10") Integer pageSize) {

        if (org.apache.commons.lang3.StringUtils.isBlank(myId)) {
            return GraceJSONResult.errorMsg("用户未登录");
        }

        Map<String, Object> map = vlogService.getMyFollowVlogList(myId, page, pageSize);
        return GraceJSONResult.success(map);
    }

    @ApiOperation(value = "查询朋友（互粉）的视频列表")
    @GetMapping("/friendList")
    public GraceJSONResult friendList(@RequestParam String myId,
                                      @RequestParam(defaultValue = "1") Integer page,
                                      @RequestParam(defaultValue = "10") Integer pageSize) {

        if (org.apache.commons.lang3.StringUtils.isBlank(myId)) {
            return GraceJSONResult.errorMsg("用户未登录");
        }

        Map<String, Object> map = vlogService.getMyFriendVlogList(myId, page, pageSize);
        return GraceJSONResult.success(map);
    }

}