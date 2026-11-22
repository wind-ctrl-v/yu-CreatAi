package com.yu.yuaifather.repository;

import com.yu.yuaifather.config.VectorStoreProperties;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MySQL 向量存储实现（{@code agent.retrieve.vector.store=mysql}，生产 2C4G 单机推荐）。
 * <p>
 * 设计目标：不部署 PostgreSQL 也能保留 3B 语义检索——
 * <ul>
 *   <li><b>持久化</b>：向量以 {@code BLOB}（小端序 float 数组）+ {@code dim}/{@code norm} 落 MySQL
 *       {@code design_fingerprint_vector} 表，由 {@code docs/sql/migrations/021_design_fingerprint_vector_mysql.sql} 建表；</li>
 *   <li><b>检索</b>：JVM 内存快照 + Java 余弦 TopK（{@link VectorSnapshot} + {@link VectorMath}），
 *       单机 APPROVED 指纹量级（默认上限 2 万条 × 1024 维）毫秒级完成；</li>
 *   <li><b>加载</b>：{@link ApplicationReadyEvent} 启动加载；{@code @Scheduled} 定时重载
 *       （{@code agent.retrieve.vector.reload-interval-ms}，&le;0 关闭）；</li>
 *   <li><b>写穿</b>：upsert 先写 DB 再更新快照，deactivate 先置 DB 失效再移除快照；</li>
 *   <li><b>降级</b>：加载失败只记 ERROR、保留旧快照（启动时为空快照 → 3B 检索返回空
 *       → 上层自动回退 3A 结构化召回），不阻塞应用启动。</li>
 * </ul>
 * 维度治理：{@code expected-dim>0} 时强校验，不一致的向量跳过并 ERROR；
 * {@code expected-dim=0} 时以首个入库/加载向量自动探测。{@code max-entries} 超限时只加载
 * 优先级最高的部分并 ERROR（低优先级条目本进程不可召回，避免 OOM）。
 * <p>
 * 注：本类由 {@code config.VectorStoreConfiguration} 以 {@code @Bean} 方式装配（非 {@code @Component}），
 * 以免与 PG 后端同时注册。
 *
 * @author yu
 */
@Slf4j
public class MysqlVectorStore implements VectorStore {

    /** upsert（MySQL 8 之前通用写法；VALUES() 在 8.0 已标记 deprecated 但兼容）。 */
    private static final String UPSERT_SQL = """
            INSERT INTO design_fingerprint_vector
                (fingerprint_id, style_profile, status, confidence, priority,
                 embedding_text, embedding, dim, norm, is_active,
                 source_scope, owner_user_id, project_id, visibility, source_type,
                 created_at, updated_at)
            VALUES (?, ?, 'APPROVED', ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, now(), now())
            ON DUPLICATE KEY UPDATE
                style_profile = VALUES(style_profile),
                confidence = VALUES(confidence),
                priority = VALUES(priority),
                embedding_text = VALUES(embedding_text),
                embedding = VALUES(embedding),
                dim = VALUES(dim),
                norm = VALUES(norm),
                is_active = 1,
                source_scope = VALUES(source_scope),
                owner_user_id = VALUES(owner_user_id),
                project_id = VALUES(project_id),
                visibility = VALUES(visibility),
                source_type = VALUES(source_type),
                updated_at = now()
            """;

    private static final String COUNT_SQL = """
            SELECT COUNT(*) FROM design_fingerprint_vector
            WHERE is_active = 1 AND status = 'APPROVED'
            """;

    private static final String LOAD_SQL = """
            SELECT id, fingerprint_id, style_profile, status, confidence, priority,
                   embedding_text, embedding, dim, source_scope, owner_user_id, project_id,
                   visibility, created_at, updated_at, is_active, source_type
            FROM design_fingerprint_vector
            WHERE is_active = 1 AND status = 'APPROVED'
            ORDER BY priority DESC, confidence DESC, updated_at DESC
            """;

    private static final String DEACTIVATE_SQL = """
            UPDATE design_fingerprint_vector
            SET is_active = 0, updated_at = now()
            WHERE fingerprint_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final VectorStoreProperties properties;

    /** 内存快照：检索无锁读，写穿/重载时整表或单条更新。 */
    private final VectorSnapshot snapshot = VectorSnapshot.empty();

    /** 自动探测到的向量维度（0=尚未探测），仅由 synchronized 方法读写。 */
    private int detectedDim;

    /** 定时重载线程（守护线程；reload-interval-ms<=0 时不启动）。 */
    private ScheduledExecutorService reloadExecutor;

    public MysqlVectorStore(JdbcTemplate jdbc, VectorStoreProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    // =================== 启动 / 定时加载 ===================

    /**
     * 应用启动就绪后加载快照（失败不阻塞启动，退化为空快照），并启动定时重载。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        reload();
        startScheduler();
    }

    /**
     * 启动定时重载（单线程守护线程）。
     * <p>
     * 不使用 {@code @Scheduled}：{@code reload-interval-ms<=0}（关闭语义）时 {@code @Scheduled}
     * 会把 fixedDelay 当作 0 导致调度线程空转；这里改为按配置显式创建/不创建调度器。
     * 间隔小于 1 秒时按 1 秒处理，避免误配置打爆数据库。
     */
    private void startScheduler() {
        long interval = properties.getReloadIntervalMs();
        if (interval <= 0) {
            log.info("MySQL 向量快照定时重载已关闭（agent.retrieve.vector.reload-interval-ms<=0）");
            return;
        }
        long effectiveInterval = Math.max(1_000L, interval);
        reloadExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "vector-snapshot-reload");
            thread.setDaemon(true);
            return thread;
        });
        reloadExecutor.scheduleWithFixedDelay(this::safeScheduledReload,
                effectiveInterval, effectiveInterval, TimeUnit.MILLISECONDS);
        log.info("MySQL 向量快照定时重载已启动 intervalMs={}", effectiveInterval);
    }

    /**
     * 定时重载入口：异常不外抛，避免调度线程被终止。
     */
    private void safeScheduledReload() {
        try {
            reload();
        } catch (Exception e) {
            log.error("定时重载 MySQL 向量快照异常", e);
        }
    }

    /**
     * 应用关闭时停止定时重载线程。
     */
    @PreDestroy
    public void shutdownScheduler() {
        if (reloadExecutor != null) {
            reloadExecutor.shutdownNow();
            reloadExecutor = null;
        }
    }

    /**
     * 重新加载整表快照（启动/定时均调用；也供运维/单测手动触发）。
     * <p>
     * 失败时保留当前快照：启动阶段当前快照为空 → 3B 返回空 → 上层回退 3A；运行阶段则继续用旧快照。
     *
     * @return 本次成功加载进快照的条目数
     */
    public synchronized int reload() {
        String reason = snapshot.isEmpty() ? "启动加载" : "定时重载";
        try {
            Integer countObj = jdbc.queryForObject(COUNT_SQL, Integer.class);
            int total = countObj == null ? 0 : countObj;
            int maxEntries = properties.getMaxEntries();
            List<DesignFingerprintVector> rows;
            if (maxEntries > 0 && total > maxEntries) {
                log.error("MySQL 向量条目数 {} 超过 max-entries={}，仅加载优先级最高的 {} 条（其余条目本进程不可召回）",
                        total, maxEntries, maxEntries);
                rows = jdbc.query(LOAD_SQL + " LIMIT ?", ROW_MAPPER, maxEntries);
            } else {
                rows = jdbc.query(LOAD_SQL, ROW_MAPPER);
            }
            List<DesignFingerprintVector> valid = new ArrayList<>(rows.size());
            int skipped = 0;
            int dimMismatch = 0;
            for (DesignFingerprintVector row : rows) {
                if (row.getFingerprintId() == null) {
                    skipped++;
                    continue;
                }
                float[] embedding = row.getEmbedding();
                if (embedding == null || embedding.length == 0) {
                    skipped++;
                    log.warn("向量为空，跳过加载 fingerprintId={}", row.getFingerprintId());
                    continue;
                }
                if (!dimensionAccepted(embedding.length)) {
                    skipped++;
                    // 同一批只打第一条明细，避免换 embedding 模型时 ERROR 刷屏
                    if (dimMismatch == 0) {
                        log.error("向量维度不匹配，跳过加载 fingerprintId={} actual={} expected={}",
                                row.getFingerprintId(), embedding.length, currentDim());
                    }
                    dimMismatch++;
                    continue;
                }
                valid.add(row);
            }
            if (dimMismatch > 0) {
                log.error("本次加载共 {} 条向量因维度不一致被跳过，请检查 agent.retrieve.vector.expected-dim", dimMismatch);
            }
            snapshot.replaceAll(valid);
            log.info("MySQL 向量快照加载完成 reason={} total={} loaded={} skipped={} dim={}",
                    reason, total, valid.size(), skipped, currentDim());
            return valid.size();
        } catch (Exception e) {
            log.error("MySQL 向量快照加载失败，保留当前快照 loaded={}（启动阶段为空则 3B 自动降级 3A）",
                    snapshot.size(), e);
            return snapshot.size();
        }
    }

    // =================== VectorStore 实现 ===================

    @Override
    public synchronized void upsert(Long fingerprintId, String styleProfile,
                                    Double confidence, Integer priority,
                                    String embeddingText, float[] embedding,
                                    String sourceScope, Long ownerUserId, Long projectId,
                                    String visibility, String sourceType) {
        if (embedding == null || embedding.length == 0) {
            log.warn("向量为空，跳过 upsert fingerprintId={}", fingerprintId);
            return;
        }
        if (!acceptDimension(embedding.length, "upsert fingerprintId=" + fingerprintId)) {
            return;
        }
        byte[] bytes = encodeEmbedding(embedding);
        double norm = VectorMath.norm(embedding);
        try {
            jdbc.update(UPSERT_SQL, fingerprintId, styleProfile, confidence, priority,
                    embeddingText, bytes, embedding.length, norm,
                    sourceScope, ownerUserId, projectId, visibility, sourceType);
        } catch (Exception e) {
            // 原则：向量同步失败不阻塞业务主链路（MySQL 指纹状态已提交），内存快照仍写穿以保当前进程可召回
            log.error("MySQL 向量 upsert 失败（本次仅内存索引生效） fingerprintId={}", fingerprintId, e);
        }
        LocalDateTime now = LocalDateTime.now();
        snapshot.put(DesignFingerprintVector.builder()
                .fingerprintId(fingerprintId)
                .styleProfile(styleProfile)
                .status("APPROVED")
                .confidence(confidence)
                .priority(priority)
                .embeddingText(embeddingText)
                .embedding(embedding)
                .isActive(true)
                .sourceScope(sourceScope)
                .ownerUserId(ownerUserId)
                .projectId(projectId)
                .visibility(visibility)
                .sourceType(sourceType)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Override
    public List<DesignFingerprintVector> search(float[] queryEmbedding, String styleProfile,
                                                int topK, double minSimilarity, Long userId) {
        return snapshot.search(queryEmbedding, styleProfile, topK, minSimilarity, userId);
    }

    @Override
    public synchronized void deactivate(Long fingerprintId) {
        if (fingerprintId == null) {
            return;
        }
        try {
            int updated = jdbc.update(DEACTIVATE_SQL, fingerprintId);
            if (updated > 0) {
                log.info("MySQL 向量标记失效 fingerprintId={}", fingerprintId);
            }
        } catch (Exception e) {
            log.error("MySQL 向量失效标记失败（内存索引仍将移除） fingerprintId={}", fingerprintId, e);
        }
        snapshot.remove(fingerprintId);
    }

    // =================== 诊断 ===================

    /**
     * 当前内存快照条目数（运维/诊断用）。
     */
    public int size() {
        return snapshot.size();
    }

    /**
     * 当前生效的向量维度（{@code expected-dim} 优先，否则为自动探测值，0=未知）。
     */
    public int currentDim() {
        return properties.getExpectedDim() > 0 ? properties.getExpectedDim() : detectedDim;
    }

    // =================== 内部辅助 ===================

    /**
     * 维度校验 / 自动探测（带日志版，供单条 upsert 使用）。
     */
    private boolean acceptDimension(int dim, String source) {
        if (dimensionAccepted(dim)) {
            return true;
        }
        log.error("向量维度不匹配，跳过 {} expected={} actual={}", source, currentDim(), dim);
        return false;
    }

    /**
     * 维度校验 / 自动探测核心逻辑（不写明细日志，调用方必须持有本对象锁）。
     * <p>
     * {@code expected-dim>0} 时强校验；否则以首个向量自动探测，后续不一致即判无效。
     */
    private boolean dimensionAccepted(int dim) {
        int expected = properties.getExpectedDim();
        if (expected > 0) {
            return dim == expected;
        }
        if (detectedDim == 0) {
            detectedDim = dim;
            log.info("自动探测向量维度 dim={}", dim);
            return true;
        }
        return detectedDim == dim;
    }

    /**
     * float[] → 小端序 BLOB（与 {@link #decodeEmbedding(byte[])} 对应）。
     */
    static byte[] encodeEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(embedding);
        return buffer.array();
    }

    /**
     * 小端序 BLOB → float[]；长度非法时返回空数组（上层按空向量跳过）。
     */
    static float[] decodeEmbedding(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % Float.BYTES != 0) {
            return new float[0];
        }
        float[] embedding = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(embedding);
        return embedding;
    }

    /** 行映射：embedding BLOB 需手动解码，故不用 BeanPropertyRowMapper。 */
    private static final RowMapper<DesignFingerprintVector> ROW_MAPPER = (rs, rowNum) -> {
        float[] embedding = decodeEmbedding(rs.getBytes("embedding"));
        return DesignFingerprintVector.builder()
                .id(rs.getLong("id"))
                .fingerprintId(rs.getLong("fingerprint_id"))
                .styleProfile(rs.getString("style_profile"))
                .status(rs.getString("status"))
                .confidence(nullableDouble(rs, "confidence"))
                .priority(nullableInteger(rs, "priority"))
                .embeddingText(rs.getString("embedding_text"))
                .embedding(embedding)
                .sourceScope(rs.getString("source_scope"))
                .ownerUserId(nullableLong(rs, "owner_user_id"))
                .projectId(nullableLong(rs, "project_id"))
                .visibility(rs.getString("visibility"))
                .sourceType(rs.getString("source_type"))
                .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
                .updatedAt(toLocalDateTime(rs.getTimestamp("updated_at")))
                .isActive(rs.getBoolean("is_active"))
                .build();
    };

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
