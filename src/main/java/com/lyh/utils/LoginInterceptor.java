package com.lyh.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断是否需要拦截(threadLocal是否有用户,是否已经登录了)
        if (UserHolder.getUser() == null) {
            // 没有需要拦截
            response.setStatus(401);
            return false;
        }
        // 有用户放行
        return true;
    }
}
