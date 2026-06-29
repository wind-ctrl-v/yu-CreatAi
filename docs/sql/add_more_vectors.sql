INSERT INTO design_fingerprint_vector (fingerprint_id, style_profile, status, confidence, priority, embedding_text, is_active, source_scope)
SELECT generate_series(1,50) + 425900000000000000, 'default', 'APPROVED', 0.92, 30,
    '设计风格: default 通用布局 中性色调 card组件 idx' || generate_series(1,50),
    true, 'GLOBAL'
ON CONFLICT (fingerprint_id) DO NOTHING;

UPDATE design_fingerprint_vector SET embedding = (
    SELECT array_agg(
        CASE WHEN (get_byte(digest(embedding_text, 'sha256'), (gs.i % 32)) % 2 = 0) THEN 1.0 ELSE -1.0 END
        * (get_byte(digest(embedding_text, 'sha256'), ((gs.i + get_byte(digest(embedding_text, 'sha256'), gs.i % 32)) % 32))::float / 128.0 - 1.0)
        ORDER BY gs.i
    )::vector FROM generate_series(0, 1535) gs(i)
) WHERE embedding IS NULL;

SELECT COUNT(*) AS total FROM design_fingerprint_vector;
