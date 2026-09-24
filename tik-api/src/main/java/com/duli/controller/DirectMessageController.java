package com.duli.controller;

import com.duli.grace.result.GraceJSONResult;
import com.duli.service.DirectMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dm")
public class DirectMessageController {
    @Autowired private DirectMessageService service;

    @GetMapping("/conversations")
    public GraceJSONResult conversations(@RequestAttribute("currentUserId") String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        try { return GraceJSONResult.success(service.conversations(userId, page, pageSize)); }
        catch (IllegalArgumentException ex) { return GraceJSONResult.errorMsg(ex.getMessage()); }
    }

    @GetMapping("/messages")
    public GraceJSONResult messages(@RequestAttribute("currentUserId") String userId,
            @RequestParam String peerId, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        try { return GraceJSONResult.success(service.messages(userId, peerId, page, pageSize)); }
        catch (IllegalArgumentException ex) { return GraceJSONResult.errorMsg(ex.getMessage()); }
    }

    @PostMapping("/send")
    public GraceJSONResult send(@RequestAttribute("currentUserId") String userId,
            @RequestBody SendRequest request) {
        try { return GraceJSONResult.success(service.send(userId, request.getToUserId(), request.getContent())); }
        catch (IllegalArgumentException ex) { return GraceJSONResult.errorMsg(ex.getMessage()); }
    }

    @PostMapping("/read")
    public GraceJSONResult read(@RequestAttribute("currentUserId") String userId,
            @RequestParam String peerId) {
        try { service.markRead(userId, peerId); return GraceJSONResult.success(); }
        catch (IllegalArgumentException ex) { return GraceJSONResult.errorMsg(ex.getMessage()); }
    }

    public static class SendRequest {
        private String toUserId;
        private String content;
        public String getToUserId() { return toUserId; }
        public void setToUserId(String toUserId) { this.toUserId = toUserId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}
