package com.duli.controller;

import com.duli.service.impl.AliyunOSSService;
import com.duli.grace.result.GraceJSONResult; // 替换成你自己的返回类包路径
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
//@RequestMapping("/file")
@CrossOrigin
public class FileController {

    // 直接注入你写好的 Service
    @Autowired
    private AliyunOSSService aliyunOSSService;

    //@PostMapping("/upload")
    public GraceJSONResult uploadVideo(@RequestAttribute("currentUserId") String userId,
                                       @RequestParam("file") MultipartFile file) throws Exception {

        // 调用你改造后的通用方法，指定放入 "videos" 文件夹
        String videoUrl = aliyunOSSService.uploadFile(file, userId, "videos");

        return GraceJSONResult.success(videoUrl);
    }
}
