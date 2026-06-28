package com.yu.yuaifather.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * PGvector 辅助数据源配置。仅在 {@code agent.retrieve.vector.enabled=true} 时激活。
 * 使用 {@link DriverManagerDataSource} 避免与 Spring Boot 自动配置的 HikariCP MySQL 主数据源冲突。
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

    @Bean(name = "pgVectorDataSource")
    public DataSource pgVectorDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    @Bean
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }
}
