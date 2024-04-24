package com.lyh.service.impl;

import com.lyh.entity.UserInfo;
import com.lyh.mapper.UserInfoMapper;
import com.lyh.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 *  服务实现类
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
