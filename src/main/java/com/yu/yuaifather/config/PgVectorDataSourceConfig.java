package com.yu.yuaifather.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PGvector 辅助数据源配置。仅在 {@code agent.retrieve.vector.enabled=true} 时激活。
 *
 * @author yu
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

    @Value("${spring.pgvector.datasource.driver-class-name:org.postgresql.Driver}")
    private String driverClassName;

    @Bean(destroyMethod = "close")
    public HikariDataSource pgVectorDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate pgVectorJdbcTemplate(HikariDataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }
}
