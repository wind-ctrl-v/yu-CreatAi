package com.yu.yuaifather.config;

import com.yu.yuaifather.repository.MysqlVectorStore;
import com.yu.yuaifather.repository.PgVectorStore;
import com.yu.yuaifather.repository.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 向量存储后端装配测试：验证 {@code agent.retrieve.vector.store} 条件装配是否正确，
 * 以及默认（未配置 store）仍走 PG、关闭 enabled 时整体不装配。
 * <p>
 * 使用空 {@link JdbcTemplate}（不连库），仅验证 Bean 装配与条件，不触发任何 SQL。
 * 两个 profile 分开注册模板 Bean，避免真实环境中不会出现的 JdbcTemplate 类型歧义。
 */
class VectorStoreConfigurationTest {

    /** pg 模式：同时存在主库模板与 PG 专用模板（与生产 pg 模式等价）。 */
    private final ApplicationContextRunner pgRunner = new ApplicationContextRunner()
            .withUserConfiguration(VectorStoreConfiguration.class, PgSupportConfig.class);

    /** mysql 模式：只存在主库模板（生产 mysql 模式不会创建 PG 模板）。 */
    private final ApplicationContextRunner mysqlRunner = new ApplicationContextRunner()
            .withUserConfiguration(VectorStoreConfiguration.class, MysqlSupportConfig.class);

    @Test
    void shouldWirePgStoreByDefault() {
        pgRunner.withPropertyValues("agent.retrieve.vector.enabled=true")
                .run(context -> assertInstanceOf(PgVectorStore.class, context.getBean(VectorStore.class)));
    }

    @Test
    void shouldWirePgStoreWhenStoreIsPg() {
        pgRunner.withPropertyValues(
                        "agent.retrieve.vector.enabled=true",
                        "agent.retrieve.vector.store=pg")
                .run(context -> assertInstanceOf(PgVectorStore.class, context.getBean(VectorStore.class)));
    }

    @Test
    void shouldWireMysqlStoreWhenStoreIsMysql() {
        mysqlRunner.withPropertyValues(
                        "agent.retrieve.vector.enabled=true",
                        "agent.retrieve.vector.store=mysql")
                .run(context -> assertInstanceOf(MysqlVectorStore.class, context.getBean(VectorStore.class)));
    }

    @Test
    void shouldNotWireStoreWhenVectorDisabled() {
        pgRunner.withPropertyValues("agent.retrieve.vector.enabled=false")
                .run(context -> assertNull(context.getBeanProvider(VectorStore.class).getIfAvailable()));
    }

    @Configuration
    @EnableConfigurationProperties(VectorStoreProperties.class)
    static class PgSupportConfig {

        /** PG 专用模板（空数据源，不连库）。 */
        @Bean(name = "pgVectorJdbcTemplate")
        JdbcTemplate pgVectorJdbcTemplate() {
            return new JdbcTemplate(new DriverManagerDataSource());
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(new DriverManagerDataSource());
        }
    }

    @Configuration
    @EnableConfigurationProperties(VectorStoreProperties.class)
    static class MysqlSupportConfig {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(new DriverManagerDataSource());
        }
    }
}
