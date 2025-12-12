package com.yu.yuaifather;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class YuAiFatherApplication {

    public static void main(String[] args) {
        SpringApplication.run(YuAiFatherApplication.class, args);
    }

}
