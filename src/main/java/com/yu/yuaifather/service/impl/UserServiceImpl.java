package com.yu.yuaifather.service.impl;

import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.mapper.UserMapper;
import com.yu.yuaifather.service.UserService;
import org.springframework.stereotype.Service;

/**
 * 用户 服务层实现。
 *
 * @author yu
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>  implements UserService{

}
