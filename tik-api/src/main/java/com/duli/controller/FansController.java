package com.duli.controller;

import com.duli.service.IFansService;
import com.duli.grace.result.GraceJSONResult; // 假设这是你的统一返回类
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/fans")
@CrossOrigin
public class FansController {

    @Autowired
    private IFansService fansService;

    /**
     * 查询当前用户是否关注了该博主
     * 对应前端：/fans/queryDoIFollowVloger?myId=xxx&vlogerId=xxx
     */
    @GetMapping("/queryDoIFollowVloger")
    public GraceJSONResult queryDoIFollowVloger(@RequestAttribute("currentUserId") String myId,
                                                @RequestParam String vlogerId) {
        boolean isFollow = fansService.queryDoIFollowVloger(myId, vlogerId);
        return GraceJSONResult.success(isFollow);
    }

    /**
     * 关注博主
     * 对应前端：/fans/follow?myId=xxx&vlogerId=xxx
     */
    @PostMapping("/follow")
    public GraceJSONResult follow(@RequestAttribute("currentUserId") String myId,
                                  @RequestParam String vlogerId) {
        // myId 是粉丝，vlogerId 是被关注的博主
        fansService.doFollow(myId, vlogerId);
        return GraceJSONResult.success();
    }

    /**
     * 取消关注
     * 对应前端：/fans/cancel?myId=xxx&vlogerId=xxx
     */
    @PostMapping("/cancel")
    public GraceJSONResult cancel(@RequestAttribute("currentUserId") String myId,
                                  @RequestParam String vlogerId) {
        fansService.doCancel(myId, vlogerId);
        return GraceJSONResult.success();
    }

    /**
     * 获取我的关注列表
     * 对应前端：/fans/queryMyFollows?myId=xxx&page=1&pageSize=10
     */
    @GetMapping("/queryMyFollows")
    public GraceJSONResult queryMyFollows(@RequestAttribute("currentUserId") String myId,
                                          @RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "10") Integer pageSize) {
        Map<String, Object> result = fansService.queryMyFollows(myId, page, pageSize);
        // 前端会去读取 result.data.data.rows 和 result.data.data.total[cite: 6]
        return GraceJSONResult.success(result);
    }

    /**
     * 获取我的粉丝列表
     * 对应前端：/fans/queryMyFans?myId=xxx&page=1&pageSize=10
     */
    @GetMapping("/queryMyFans")
    public GraceJSONResult queryMyFans(@RequestAttribute("currentUserId") String myId,
                                       @RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "10") Integer pageSize) {
        Map<String, Object> result = fansService.queryMyFans(myId, page, pageSize);
        // 前端会去读取 result.data.data.rows 和 result.data.data.total
        return GraceJSONResult.success(result);
    }
}
