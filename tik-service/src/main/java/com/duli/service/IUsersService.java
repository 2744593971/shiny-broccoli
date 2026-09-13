package com.duli.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.duli.pojo.Users;

/**
 * <p>
 * 用户表 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-05
 */
public interface IUsersService extends IService<Users> {
    /**
     * 新用户自动注册，并初始化默认属性
     */
    Users createUser(String mobile);

}
