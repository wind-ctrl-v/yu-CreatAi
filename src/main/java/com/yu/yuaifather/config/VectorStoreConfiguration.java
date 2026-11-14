package com.yu.yuaifather.config;

import com.yu.yuaifather.repository.MysqlVectorStore;
import com.yu.yuaifather.repository.PgVectorStore;
import com.yu.yuaifather.repository.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 向量存储后端装配（A 改造：PG → MySQL 内存余弦，保留语义检索）。
 * <p>
 * 仅在 {@code agent.retrieve.vector.enabled=true}（3B 向量检索开关）时生效，
 * 按 {@code agent.retrieve.vector.store} 二选一装配 {@link VectorStore}：
 * <ul>
 *   <li>{@code pg}（默认，未配置时 matchIfMissing）：{@link PgVectorStore}，
 *       依赖 {@code pgVectorJdbcTemplate}，行为与改造前完全一致；</li>
 *   <li>{@code mysql}：{@link MysqlVectorStore}，使用 Spring Boot 主 MySQL 数据源的
 *       {@link JdbcTemplate}，不创建 PG 数据源。</li>
 * </ul>
 * 上层统一通过门面 {@code DesignFingerprintVectorRepository} 使用，调用方无感知。
 *
 * @author yu
 */
@Configuration
@ConditionalOnProperty(name = "agent.retrieve.vector.enabled", havingValue = "true")
public class VectorStoreConfiguration {

    /**
     * PG 后端（默认）：复用 {@code PgVectorDataSourceConfig} 提供的专用 JdbcTemplate。
     */
    @Bean
    @ConditionalOnProperty(name = "agent.retrieve.vector.store", havingValue = "pg", matchIfMissing = true)
    public VectorStore pgVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbc) {
        return new PgVectorStore(jdbc);
    }

    /**
     * MySQL 后端：使用主数据源 JdbcTemplate + 内存快照（需先执行 021 建表脚本）。
     */
    @Bean
    @ConditionalOnProperty(name = "agent.retrieve.vector.store", havingValue = "mysql")
    public VectorStore mysqlVectorStore(JdbcTemplate jdbc, VectorStoreProperties properties) {
        return new MysqlVectorStore(jdbc, properties);
    }
}
