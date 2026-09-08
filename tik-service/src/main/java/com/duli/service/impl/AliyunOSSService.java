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

    public String uploadFile(MultipartFile file, String userId) throws Exception {
        // 1. 获取原文件名并提取后缀 (例如 .jpg)
        String originalFilename = file.getOriginalFilename();
        String suffix = originalFilename.substring(originalFilename.lastIndexOf("."));

        // 2. 构造防重名的云端文件名 (按 userId 分文件夹存放)
        String newFileName = "face/" + userId + "/" + UUID.randomUUID().toString() + suffix;

        // 3. 创建 OSSClient 实例
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            // 4. 上传文件流
            InputStream inputStream = file.getInputStream();
            ossClient.putObject(bucketName, newFileName, inputStream);

            // 5. 拼装可以在外网访问的图片 URL
            // 格式通常是: https://[bucketName].[endpoint]/[newFileName]
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