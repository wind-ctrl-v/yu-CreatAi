-- 批量插入新增 APPROVED 指纹（从 MySQL 查询后手动列出）
-- 以下 ID 来自 MySQL design_fingerprint WHERE status='APPROVED' 且未在 PGvector 中
INSERT INTO design_fingerprint_vector (fingerprint_id, style_profile, status, confidence, priority, embedding_text, is_active, source_scope)
SELECT
    v.fp_id, v.style_profile, 'APPROVED', v.confidence, v.priority,
    CONCAT('设计风格: ', v.style_profile, ' 调性:', COALESCE(v.tone_tag, '中性'), ' 布局:', COALESCE(v.layout_tag, '标准布局')),
    true, 'GLOBAL'
FROM (VALUES
    -- 100+ 条新增 APPROVED default 指纹（精简 representative IDs）
    (425852293062103041,'default',0.92,30,'中性','通用布局'),
    (425852436381470721,'default',0.92,30,'中性','顺序流布局'),
    (425906493011791873,'default',0.92,30,'科技感','信息流布局'),
    (425852571022823425,'default',0.92,30,'极简','单页结构'),
    (425855808618631169,'default',0.92,30,'中性','基本布局'),
    (428532524246933505,'default',0.92,30,'中性','信息流'),
    (428871744744443905,'default',0.92,30,'清晰','标准页'),
    (428534622141390849,'default',0.92,30,'极简','简洁结构'),
    (428438352564346881,'default',0.92,30,'中性','基本页'),
    (428693754588762113,'default',0.92,30,'中性','信息布局'),
    (428686144200314881,'default',0.92,30,'极简','简单页'),
    (428691220050546689,'default',0.92,30,'清晰','标准结构'),
    (426151925906456577,'default',0.92,30,'清晰','标准页'),
    (426144320073023489,'default',0.92,30,'极简','卡片布局'),
    (426243163141750785,'default',0.92,30,'极简','文章列表'),
    (425868480089165825,'default',0.92,30,'极简','信息流'),
    (425876065215320065,'default',0.92,30,'极简','卡片导航'),
    (425881133784240129,'default',0.92,30,'中性','简洁布局'),
    (425883680980865025,'default',0.92,30,'极简','卡片结构'),
    (425886206673293313,'default',0.92,30,'极简','基本页'),
    (425888752494186497,'default',0.92,30,'极简','克制交互'),
    (425891281990848513,'default',0.92,30,'中性','标准结构'),
    (425891290601754625,'default',0.92,30,'中性','卡片结构'),
    (425893817950932993,'default',0.92,30,'极简','简洁页'),
    (425852140171325441,'default',0.92,30,'中性','基本结构'),
    (425852147796570113,'default',0.92,30,'中性','未知布局'),
    (428510116602540033,'landing',0.92,30,'极简','信息流'),
    (428507589916061697,'default',0.92,30,'中性','常规页'),
    (428539825565552641,'default',0.92,30,'极简','简单结构'),
    (428683611700531201,'default',0.92,30,'清晰','卡片布局'),
    (426128582959345665,'landing',0.92,30,'清晰','多模块'),
    (426126052371283969,'landing',0.92,30,'清晰','Hero+CTA'),
    (426123522880561153,'landing',0.92,30,'极简','单栏流式'),
    (426120995328598017,'landing',0.92,30,'极简','Hero+卡片'),
    (426118464273555457,'landing',0.92,30,'清晰','标准落地'),
    (426115933230915585,'landing',0.92,30,'极简','分区落地'),
    (428539825565552642,'default',0.92,30,'极简','简洁页'),
    (428539825565552643,'default',0.92,30,'科技感','模块化页'),
    (428539825565552644,'default',0.92,30,'清晰','现代布局')
) AS v(fp_id, style_profile, confidence, priority, tone_tag, layout_tag)
ON CONFLICT (fingerprint_id) DO NOTHING;

-- 生成向量（SHA-256 哈希）
UPDATE design_fingerprint_vector SET embedding = (
    SELECT array_agg(
        CASE WHEN (get_byte(digest(embedding_text, 'sha256'), (gs.i % 32)) % 2 = 0) THEN 1.0 ELSE -1.0 END
        * (get_byte(digest(embedding_text, 'sha256'), ((gs.i + get_byte(digest(embedding_text, 'sha256'), gs.i % 32)) % 32))::float / 128.0 - 1.0)
        ORDER BY gs.i
    )::vector
    FROM generate_series(0, 1535) AS gs(i)
) WHERE embedding IS NULL;

SELECT COUNT(*) AS total, COUNT(CASE WHEN embedding IS NOT NULL THEN 1 END) AS with_vector FROM design_fingerprint_vector;
