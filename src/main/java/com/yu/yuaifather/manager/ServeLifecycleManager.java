package com.yu.yuaifather.manager;

import com.yu.yuaifather.service.ServeDeployService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ServeLifecycleManager {
    
    @Autowired
    private ServeDeployService serveDeployService;
    
    /**
     * Spring Boot 启动完成后启动 Serve 服务
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        serveDeployService.startServeService();
    }
    
    /**
     * Spring Boot 关闭时停止 Serve 服务
     */
    @PreDestroy
    public void onApplicationShutdown() {
        log.info("Shutting down Serve service...");
        serveDeployService.stopServeService();
    }
}
