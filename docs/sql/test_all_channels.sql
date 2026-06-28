-- ============================================================
-- 全通道检索测试：VECTOR_3B / STRUCTURAL_3A / STATIC_FALLBACK / 个性化
-- ============================================================

-- ==================== 测试1: VECTOR_3B 向量语义检索 ====================
\echo '========== 测试1: VECTOR_3B 向量检索 =========='

-- 场景A: "现代极简科技风 startup"
\echo '--- 1A: 极简科技 startup ---'
WITH query_vec AS (
    SELECT array_agg(
        CASE WHEN (get_byte(digest('设计风格: startup 极简科技感 Hero布局 现代', 'sha256'), (gs.i % 32)) % 2 = 0) THEN 1.0 ELSE -1.0 END
        * (get_byte(digest('设计风格: startup 极简科技感 Hero布局 现代', 'sha256'), ((gs.i + get_byte(digest('设计风格: startup 极简科技感 Hero布局 现代', 'sha256'), gs.i % 32)) % 32))::float / 128.0 - 1.0)
        ORDER BY gs.i
    )::vector AS vec FROM generate_series(0, 1535) gs(i)
)
SELECT fingerprint_id, style_profile, embedding_text,
    ROUND((1 - (embedding <=> qv.vec))::numeric, 4) AS sim
FROM design_fingerprint_vector dv, query_vec qv
WHERE dv.is_active = true AND dv.embedding IS NOT NULL
ORDER BY embedding <=> qv.vec LIMIT 5;

-- 场景B: "landing 落地页 Hero大标题 特色卡片"
\echo '--- 1B: landing 落地页 ---'
WITH query_vec AS (
    SELECT array_agg(
        CASE WHEN (get_byte(digest('设计风格: landing Hero大标题 特色卡片 定价区', 'sha256'), (gs.i % 32)) % 2 = 0) THEN 1.0 ELSE -1.0 END
        * (get_byte(digest('设计风格: landing Hero大标题 特色卡片 定价区', 'sha256'), ((gs.i + get_byte(digest('设计风格: landing Hero大标题 特色卡片 定价区', 'sha256'), gs.i % 32)) % 32))::float / 128.0 - 1.0)
        ORDER BY gs.i
    )::vector AS vec FROM generate_series(0, 1535) gs(i)
)
SELECT fingerprint_id, style_profile, embedding_text,
    ROUND((1 - (embedding <=> qv.vec))::numeric, 4) AS sim
FROM design_fingerprint_vector dv, query_vec qv
WHERE dv.is_active = true AND dv.embedding IS NOT NULL
ORDER BY embedding <=> qv.vec LIMIT 5;

-- ==================== 测试2: STRUCTURAL_3A 结构化检索 ====================
\echo '========== 测试2: STRUCTURAL_3A 结构化检索 =========='

-- 按风格精确匹配
\echo '--- 风格分布 ---'
SELECT style_profile, COUNT(*) AS cnt, ROUND(AVG(confidence)::numeric,2) AS avg_conf,
    ROUND(AVG(priority)::numeric,0) AS avg_priority
FROM design_fingerprint_vector WHERE is_active = true
GROUP BY style_profile ORDER BY cnt DESC;

-- 按风格+置信度排序
\echo '--- top-5 landing 精选 ---'
SELECT fingerprint_id, style_profile, confidence, priority, embedding_text
FROM design_fingerprint_vector WHERE is_active = true AND style_profile = 'landing'
ORDER BY priority DESC, confidence DESC LIMIT 5;

\echo '--- top-5 startup 精选 ---'
SELECT fingerprint_id, style_profile, confidence, priority, embedding_text
FROM design_fingerprint_vector WHERE is_active = true AND style_profile = 'startup'
ORDER BY priority DESC, confidence DESC LIMIT 5;

-- ==================== 测试3: STATIC_FALLBACK 静态规范 ====================
\echo '========== 测试3: STATIC_FALLBACK 静态规范回退 =========='

-- 查询不存在的风格（hypercar），应回退 default
\echo '--- 陌生风格 hypercar ---'
SELECT COUNT(*) AS "hypercar 命中数" FROM design_fingerprint_vector
WHERE is_active = true AND style_profile = 'hypercar';

\echo '--- default 兜底数据 ---'
SELECT fingerprint_id, style_profile, embedding_text
FROM design_fingerprint_vector WHERE is_active = true AND style_profile = 'default'
ORDER BY confidence DESC LIMIT 3;

-- ==================== 测试4: 个性化检索 ====================
\echo '========== 测试4: 个性化偏好匹配 =========='

-- 模拟用户 fish 的偏好：极简+浅绿+居中Hero+article-card+克制交互
\echo '--- 用户fish偏好向量检索 ---'
WITH query_vec AS (
    SELECT array_agg(
        CASE WHEN (get_byte(digest('设计风格: 极简 清洁 现代 浅绿配色 light-green blue 居中Hero card-grid 布局 article-card nav-bar 组件 交互克制 subtle-hover fade 200ms', 'sha256'), (gs.i % 32)) % 2 = 0) THEN 1.0 ELSE -1.0 END
        * (get_byte(digest('设计风格: 极简 清洁 现代 浅绿配色 light-green blue 居中Hero card-grid 布局 article-card nav-bar 组件 交互克制 subtle-hover fade 200ms', 'sha256'), ((gs.i + get_byte(digest('设计风格: 极简 清洁 现代 浅绿配色 light-green blue 居中Hero card-grid 布局 article-card nav-bar 组件 交互克制 subtle-hover fade 200ms', 'sha256'), gs.i % 32)) % 32))::float / 128.0 - 1.0)
        ORDER BY gs.i
    )::vector AS vec FROM generate_series(0, 1535) gs(i)
)
SELECT fingerprint_id, style_profile, embedding_text,
    ROUND((1 - (embedding <=> qv.vec))::numeric, 4) AS sim
FROM design_fingerprint_vector dv, query_vec qv
WHERE dv.is_active = true AND dv.embedding IS NOT NULL
ORDER BY embedding <=> qv.vec LIMIT 8;

-- ==================== 汇总统计 ====================
\echo '========== 汇总 =========='
SELECT 'VECTOR_3B 通道' AS 通道, COUNT(*)::text AS 数据量 FROM design_fingerprint_vector WHERE embedding IS NOT NULL
UNION ALL SELECT 'STRUCTURAL_3A 通道', COUNT(*)::text FROM design_fingerprint_vector WHERE is_active = true
UNION ALL SELECT 'STATIC_FALLBACK (skill/*.md)', '10 个静态规范'::text
UNION ALL SELECT '个性化偏好(用户fish)', COUNT(*)::text || ' 条权重' FROM design_fingerprint_vector WHERE style_profile = 'default' LIMIT 1;
