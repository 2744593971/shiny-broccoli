package com.duli.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.util.UUID;

@Service
public class AliyunOSSService {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;
    @Value("${aliyun.oss.accessKeyId}")
    private String accessKeyId;
    @Value("${aliyun.oss.accessKeySecret}")
    private String accessKeySecret;
    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    // 增加了一个 folderName 参数，用来区分是传头像还是传视频
    public String uploadFile(MultipartFile file, String userId, String folderName) throws Exception {
        // 1. 获取原文件名并提取后缀 (例如 .jpg 或 .mp4)
        // 1. 获取原文件名并提取后缀 (加入安全校验，防止没有点导致 -1 越界)
        // 1. 获取原文件名
        String originalFilename = file.getOriginalFilename();
        String suffix = "";

        // 2. 智能提取或兜底后缀
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        } else {
            // ⚠️ 兼容 uni-app 传上来的临时文件没有后缀的情况
            if ("videos".equals(folderName)) {
                suffix = ".mp4";  // 如果是视频目录，默认给 .mp4
            } else {
                suffix = ".jpg";  // 如果是头像/背景目录，默认给 .jpg
            }
        }

        // 2. 动态拼接云端文件名 (按 业务文件夹/userId 存放，例如 videos/123/xxx.mp4)
        String newFileName = folderName + "/" + userId + "/" + UUID.randomUUID().toString() + suffix;

        // 3. 创建 OSSClient 实例
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            // 4. 上传文件流
            InputStream inputStream = file.getInputStream();
            ossClient.putObject(bucketName, newFileName, inputStream);

            // 5. 拼装可以在外网访问的图片/视频 URL
            String cleanEndpoint = endpoint.replace("https://", "").replace("http://", "");
            return "https://" + bucketName + "." + cleanEndpoint + "/" + newFileName;
        } finally {
            // 6. ⭐关闭客户端，释放连接
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }
}