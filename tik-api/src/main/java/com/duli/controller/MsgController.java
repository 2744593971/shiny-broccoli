package com.duli.controller;

import com.duli.mo.MessageMO;
import com.duli.service.MsgService;
// 假设你有一个统一返回对象 GraceJSONResult，如果没有请替换为你项目中的通用返回类
import com.duli.grace.result.GraceJSONResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public Object queryList(@RequestParam String userId,
                            @RequestParam Integer page,
                            @RequestParam Integer pageSize) {
        
        // 前端传来的 userId 实际上就是 MessageMO 里的 toUserId (接收方)
        List<MessageMO> list = msgService.queryList(userId, page, pageSize);
        
        // 前端代码通过 result.data.status == 200 来判断成功，因此必须用统一对象包装返回
        return GraceJSONResult.success(list);
    }


}