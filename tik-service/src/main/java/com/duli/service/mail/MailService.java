package com.duli.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j // 推荐加上这个注解用来打日志
@Service
public class MailService {

    @Autowired
    private JavaMailSender mailSender;

    // 从配置文件中读取发件人账号
    @Value("${spring.mail.username}")
    private String fromEmail;

    /**
     * 发送文本验证码邮件
     * 注意：方法签名上加上 throws Exception，让调用它的 Controller 知道可能报错
     */
    public void sendVerificationCode(String toEmail, String code) throws Exception {

        // 创建一个简单的邮件消息对象
        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject("系统登录验证码");
        message.setText("【itik-tok】您的验证码为：" + code + " ，该验证码5分钟内有效，请勿泄露于他人。");

        log.info("准备向邮箱 {} 发送验证码...", toEmail);

        // 发送邮件 (如果这里报错，异常会直接抛给 Controller，触发 Controller 里的 catch)
        mailSender.send(message);

        log.info("验证码邮件发送成功！收件人：{}", toEmail);
    }
}