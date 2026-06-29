package com.yu.yuaifather.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * PGvector 辅助数据源配置。仅在 {@code agent.retrieve.vector.enabled=true} 时激活。
 * <p>
 * 使用私有 DriverManagerDataSource，不暴露为 Spring Bean，
 * 避免与 Spring Boot 自动配置的 MySQL HikariCP 主数据源冲突。
 */
@Configuration
@ConditionalOnProperty(name = "agent.retrieve.vector.enabled", havingValue = "true")
public class PgVectorDataSourceConfig {

    @Value("${spring.pgvector.datasource.url}")
    private String url;

    @Value("${spring.pgvector.datasource.username}")
    private String username;

    @Value("${spring.pgvector.datasource.password}")
    private String password;

    /**
     * PGvector 专用 JdbcTemplate，使用私有非池化数据源，不注册为 DataSource Bean。
     */
    @Bean
    public JdbcTemplate pgVectorJdbcTemplate() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return new JdbcTemplate(ds);
    }
}
