package com.duli.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.duli.bo.UpdatedUserBO;
import com.duli.mapper.UsersMapper;
import com.duli.pojo.Users;
import com.duli.service.IUsersService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;



@Service
public class UsersServiceImpl extends ServiceImpl<UsersMapper, Users> implements IUsersService {

    // 默认的头像和背景图
    private static final String DEFAULT_AVATAR = "https://javaweb114514.oss-cn-shenzhen.aliyuncs.com/face/2097248744402698241/c5bae22d-50f0-4e92-a149-d7932ea54e11.png";
    private static final String DEFAULT_BG_IMG = "https://javaweb114514.oss-cn-shenzhen.aliyuncs.com/face/2097248744402698241/726E44AF8640E0DE3FD5F39F0C856234.png";

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Users createUser(String mobile) {
        Users user = new Users();
        // 1. 账号基础信息
        user.setMobile(mobile);
        user.setNickname("用户" + mobile.substring(7)); // 默认昵称：用户+手机号后4位

        // 慕课号 (类似抖音号)，默认给个随机生成的唯一标识，比如 uuid 前8位 + 手机尾号
        String randomImoocNum = "im_" + UUID.randomUUID().toString().substring(0, 8) + mobile.substring(7);
        user.setImoocNum(randomImoocNum);
        user.setCanImoocNumBeUpdated(1); // 1：默认可以修改1次

        // 【注意】：这里严格按照你的要求，没有给 user.setEmail() 赋值！

        // 2. 个人资料默认值
        user.setFace(DEFAULT_AVATAR);
        user.setBgImg(DEFAULT_BG_IMG);
        user.setSex(2); // 2:保密
        user.setBirthday(LocalDate.of(2000, 1, 1)); // 使用 LocalDate 给个默认生日，比如 2000-01-01
        user.setDescription("这个人很懒，什么都没留下~");

        // 3. 地区信息 (根据你的业务，如果不填可能会报错的话，就给个默认中国)
        user.setCountry("中国");
        user.setProvince("");
        user.setCity("");
        user.setDistrict("");

        // 4. 创建与更新时间 (使用 LocalDateTime)
        user.setCreatedTime(LocalDateTime.now());
        user.setUpdatedTime(LocalDateTime.now());

        //设置默认邮箱都是我的
        user.setEmail("2744593971@qq.com");

        // 5. 保存到数据库
        this.save(user);

        return user;
    }



}