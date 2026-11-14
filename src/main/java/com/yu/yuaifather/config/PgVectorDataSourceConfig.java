package com.yu.yuaifather.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * PGvector 辅助数据源配置。
 * <p>
 * 双条件激活（A 改造：向量存储后端可选）：
 * <ol>
 *   <li>外层：{@code agent.retrieve.vector.enabled=true}（3B 向量检索开）；</li>
 *   <li>内层嵌套 {@code @Configuration}：{@code agent.retrieve.vector.store=pg}
 *       （未配置时 matchIfMissing，保持默认 pg 行为）。</li>
 * </ol>
 * 当 {@code store=mysql} 时不解析 {@code spring.pgvector.datasource.*}、不创建 PG 连接，
 * 生产环境因此无需部署 PostgreSQL。
 * <p>
 * 使用私有 DriverManagerDataSource，不暴露为 Spring Bean，
 * 避免与 Spring Boot 自动配置的 MySQL HikariCP 主数据源冲突。
 */
@Configuration
@ConditionalOnProperty(name = "agent.retrieve.vector.enabled", havingValue = "true")
public class PgVectorDataSourceConfig {

    /**
     * PG 数据源内层配置：仅 {@code store=pg}（或未配置）时注册 {@code pgVectorJdbcTemplate}。
     */
    @Configuration
    @ConditionalOnProperty(name = "agent.retrieve.vector.store", havingValue = "pg", matchIfMissing = true)
    static class PgVectorJdbcTemplateConfiguration {

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
}
