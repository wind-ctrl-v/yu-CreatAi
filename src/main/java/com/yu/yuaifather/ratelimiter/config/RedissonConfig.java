package com.yu.yuaifather.ratelimiter.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson配置类
 * 用于配置Redisson客户端，连接Redis服务器
 */
@Configuration
public class RedissonConfig {

    // 从配置文件中注入Redis主机地址
    @Value("${spring.data.redis.host}")
    private String redisHost;

    // 从配置文件中注入Redis端口号
    @Value("${spring.data.redis.port}")
    private Integer redisPort;

    // 从配置文件中注入Redis密码
    @Value("${spring.data.redis.password}")
    private String redisPassword;

    // 从配置文件中注入Redis数据库索引
    @Value("${spring.data.redis.database}")
    private Integer redisDatabase;

    /**
     * 创建Redisson客户端Bean
     * @return 配置好的Redisson客户端实例
     */
    @Bean
    public RedissonClient redissonClient() {
        // 创建Redisson配置对象
        Config config = new Config();
        // 构建Redis服务器地址
        String address = "redis://" + redisHost + ":" + redisPort;
        // 配置单机Redis服务器
        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress(address)                    // 设置服务器地址
                .setDatabase(redisDatabase)             // 设置数据库索引
                .setConnectionMinimumIdleSize(1)        // 设置最小空闲连接数
                .setConnectionPoolSize(10)             // 设置连接池大小
                .setIdleConnectionTimeout(30000)       // 设置连接空闲超时时间(毫秒)
                .setConnectTimeout(5000)               // 设置连接超时时间(毫秒)
                .setTimeout(3000)                      // 设置命令等待超时时间(毫秒)
                .setRetryAttempts(3)                   // 设置命令重试次数
                .setRetryInterval(1500);                // 设置命令重试间隔(毫秒)
        // 如果有密码则设置密码
        if (redisPassword != null && !redisPassword.isEmpty()) {
            singleServerConfig.setPassword(redisPassword);
        }
        return Redisson.create(config);
    }
}
