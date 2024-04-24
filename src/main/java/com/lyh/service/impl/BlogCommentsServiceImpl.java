package com.lyh.service.impl;

import com.lyh.entity.BlogComments;
import com.lyh.mapper.BlogCommentsMapper;
import com.lyh.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 *  服务实现类
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
