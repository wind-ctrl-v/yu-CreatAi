-- 批量同步剩余APPROVED指纹（使用代表性ID覆盖default+landing）
INSERT INTO design_fingerprint_vector (fingerprint_id, style_profile, status, confidence, priority, embedding_text, is_active, source_scope) VALUES
(425852571022823426,'default','APPROVED',0.92,30,'设计风格: default 极简 单页结构 中性色调',true,'GLOBAL'),
(425852571022823427,'default','APPROVED',0.92,30,'设计风格: default 科技感 模块化 深色主色',true,'GLOBAL'),
(425852571022823428,'default','APPROVED',0.92,30,'设计风格: default 干净 信息流 card布局',true,'GLOBAL'),
(425852571022823429,'default','APPROVED',0.92,30,'设计风格: default 极简 克制交互 浅色背景',true,'GLOBAL'),
(425852571022823430,'default','APPROVED',0.92,30,'设计风格: default 现代 圆角卡片 柔和阴影',true,'GLOBAL'),
(425852571022823431,'default','APPROVED',0.92,30,'设计风格: default 中性 标准布局 通用组件',true,'GLOBAL'),
(425852571022823432,'landing','APPROVED',0.92,30,'设计风格: landing Hero大标题 + CTA按钮 大圆角',true,'GLOBAL'),
(425852571022823433,'landing','APPROVED',0.92,30,'设计风格: landing 三栏特性 + 定价卡片 对比布局',true,'GLOBAL'),
(425852571022823434,'landing','APPROVED',0.92,30,'设计风格: landing Logo墙 + 用户评价 社会证明',true,'GLOBAL'),
(425852571022823435,'landing','APPROVED',0.92,30,'设计风格: landing FAQ折叠区 + 底部强CTA',true,'GLOBAL'),
(425852571022823436,'default','APPROVED',0.92,30,'设计风格: default 柔和色调 warm色系 衬线字体',true,'GLOBAL'),
(425852571022823437,'default','APPROVED',0.92,30,'设计风格: default 蓝色主调 科技感 card grid nav',true,'GLOBAL'),
(425852571022823438,'default','APPROVED',0.92,30,'设计风格: default 极简黑白 大留白 排版优先',true,'GLOBAL'),
(425852571022823439,'default','APPROVED',0.92,30,'设计风格: default 翠绿色调 清新风格 article-card',true,'GLOBAL'),
(425852571022823440,'default','APPROVED',0.92,30,'设计风格: default 琥珀点缀 教育平台 进度条星级',true,'GLOBAL')
ON CONFLICT (fingerprint_id) DO NOTHING;

UPDATE design_fingerprint_vector SET embedding = (
    SELECT array_agg(
        CASE WHEN (get_byte(digest(embedding_text, 'sha256'), (gs.i % 32)) % 2 = 0) THEN 1.0 ELSE -1.0 END
        * (get_byte(digest(embedding_text, 'sha256'), ((gs.i + get_byte(digest(embedding_text, 'sha256'), gs.i % 32)) % 32))::float / 128.0 - 1.0)
        ORDER BY gs.i
    )::vector FROM generate_series(0, 1535) gs(i)
) WHERE embedding IS NULL;

SELECT COUNT(*) AS total, COUNT(CASE WHEN embedding IS NOT NULL THEN 1 END) AS with_embeddings FROM design_fingerprint_vector;
