package com.lyh;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@MapperScan("com.lyh.mapper")
@EnableAspectJAutoProxy(exposeProxy = true) // 暴露代理对象
@SpringBootApplication
public class MyDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyDianPingApplication.class, args);
    }

}
