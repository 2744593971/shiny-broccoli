package com.duli.controller;

import com.duli.mo.MessageMO;
import com.duli.service.MsgService;
// 假设你有一个统一返回对象 GraceJSONResult，如果没有请替换为你项目中的通用返回类
import com.duli.grace.result.GraceJSONResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/msg")
public class MsgController {

    @Autowired
    private MsgService msgService;

    /**
     * 查询消息列表
     * 对应前端请求: GET /msg/list?userId={userId}&page={page}&pageSize=10
     */
    @GetMapping("/list")
    public Object queryList(@RequestAttribute("currentUserId") String userId,
                            @RequestParam Integer page,
                            @RequestParam Integer pageSize) {
        
        // 消息接收者只能是 Token 验证过的当前用户。
        List<MessageMO> list = msgService.queryList(userId, page, pageSize);
        
        // 前端代码通过 result.data.status == 200 来判断成功，因此必须用统一对象包装返回
        return GraceJSONResult.success(list);
    }

    @PostMapping("/delete")
    public Object delete(@RequestParam String msgId,
                         @RequestAttribute("currentUserId") String userId) {

        if (!msgService.deleteMsg(msgId, userId)) {
            return GraceJSONResult.errorMsg("消息不存在或无权删除");
        }
        // 返回你项目中通用的成功响应格式 (例如 GraceJSONResult.ok())
        return GraceJSONResult.success();
    }


}
