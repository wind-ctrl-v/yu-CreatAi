package com.yu.yuaifather.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PGvector 辅助数据源配置。
 * <p>
 * 与 MySQL 主数据源（由 {@code spring.datasource} 自动配置）共存，
 * 通过 {@code @Qualifier} 注入，仅服务于向量存储与语义检索，不参与 MyBatis-Flex 主链路。
 *
 * @author yu
 */
@Configuration
public class PgVectorDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.pgvector.datasource")
    public DataSourceProperties pgVectorDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource pgVectorDataSource() {
        return pgVectorDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
