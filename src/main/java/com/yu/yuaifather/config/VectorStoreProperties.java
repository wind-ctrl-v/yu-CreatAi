package com.yu.yuaifather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量存储配置。绑定 {@code agent.retrieve.vector.*}。
 * <p>
 * {@code enabled}/{@code min-similarity} 仍由既有 {@code @Value} 消费，本类只承载
 * 后端选择与 MySQL 内存快照相关参数。默认 {@code store=pg}，保持改造前行为。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.retrieve.vector")
public class VectorStoreProperties {

    /** 向量后端：pg（默认）/ mysql。 */
    private String store = "pg";

    /** mysql 后端内存快照定时重载间隔（毫秒），0=关闭。 */
    private long reloadIntervalMs = 300_000L;

    /** 期望向量维度，0=自动探测（首个入库/加载向量决定）。 */
    private int expectedDim = 0;

    /** 内存快照最大条目数，超出时只加载优先级最高的部分并告警（防 OOM）。 */
    private int maxEntries = 20_000;

    /** 管理员一次性重同步向量的单次上限。 */
    private int resyncMax = 100;
}
