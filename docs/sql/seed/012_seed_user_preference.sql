-- ============================================================
-- 用户偏好种子数据
-- 用途：模拟用户 fish 的设计偏好画像，供个性化检索和偏好闭环使用
-- 执行：导入 MySQL yu_ai_code_mother 数据库
-- 注意：userId 请根据实际用户 ID 替换（示例使用 425763850357334016）
-- ============================================================

-- 设置变量，方便替换
SET @fish_user_id = 425763850357334016;

-- ===================== 1. 用户设计画像 =====================

INSERT INTO user_design_profile (
    id, userId, profileSummary,
    preferredStyles, dislikedStyles,
    preferredColors, dislikedColors,
    preferredLayouts, dislikedLayouts,
    preferredComponents, dislikedComponents,
    preferredInteractions, dislikedInteractions,
    createTime, updateTime
) VALUES (
    425793298163081217, @fish_user_id,
    '偏好干净极简风格，喜欢浅绿色与蓝色系配色，倾向使用柔和圆角和轻阴影。喜欢的布局是居中 Hero + 卡片网格，组件偏好导航栏和文章卡片。交互动效保持克制，避免浮夸放大。',
    '["minimal", "modern", "clean", "tech"]',
    '["corporate", "playful", "maximalist"]',
    '["light-green", "blue", "slate-gray", "warm-white"]',
    '["neon", "bright-yellow", "hot-pink"]',
    '["centered-hero", "card-grid", "sticky-nav"]',
    '["sidebar-heavy", "masonry"]',
    '["nav-bar", "article-card", "feature-grid", "light-footer"]',
    '["mega-menu", "carousel", "popup-modal"]',
    '["subtle-hover", "fade-transition"]',
    '["large-scale-hover", "bounce", "auto-scroll"]',
    NOW(), NOW()
) ON DUPLICATE KEY UPDATE updateTime = NOW();

-- ===================== 2. 用户偏好权重 =====================

-- 风格偏好
INSERT INTO user_preference_weight (userId, preferenceType, preferenceKey, weight, evidenceCount, lastEvidenceAt, createTime, updateTime) VALUES
(@fish_user_id, 'style', 'minimal', 0.85, 3, NOW(), NOW(), NOW()),
(@fish_user_id, 'style', 'modern', 0.70, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'style', 'clean', 0.80, 3, NOW(), NOW(), NOW()),
(@fish_user_id, 'style', 'tech', 0.60, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'style', 'corporate', -0.30, 1, NOW(), NOW(), NOW()),
(@fish_user_id, 'style', 'playful', -0.50, 1, NOW(), NOW(), NOW()),
(@fish_user_id, 'style', 'dark-mode', 0.40, 1, NOW(), NOW(), NOW()),
(@fish_user_id, 'style', 'warm', 0.55, 2, NOW(), NOW(), NOW());

-- 颜色偏好
INSERT INTO user_preference_weight (userId, preferenceType, preferenceKey, weight, evidenceCount, lastEvidenceAt, createTime, updateTime) VALUES
(@fish_user_id, 'color', 'light-green', 0.90, 4, NOW(), NOW(), NOW()),
(@fish_user_id, 'color', 'blue', 0.75, 3, NOW(), NOW(), NOW()),
(@fish_user_id, 'color', 'slate-gray', 0.65, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'color', 'emerald', 0.70, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'color', 'warm-white', 0.60, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'color', 'purple', 0.45, 1, NOW(), NOW(), NOW()),
(@fish_user_id, 'color', 'neon', -0.80, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'color', 'hot-pink', -0.70, 1, NOW(), NOW(), NOW()),
(@fish_user_id, 'color', 'bright-yellow', -0.60, 1, NOW(), NOW(), NOW()),
(@fish_user_id, 'color', 'dark-bg', -0.30, 1, NOW(), NOW(), NOW());

-- 布局偏好
INSERT INTO user_preference_weight (userId, preferenceType, preferenceKey, weight, evidenceCount, lastEvidenceAt, createTime, updateTime) VALUES
(@fish_user_id, 'layout', 'centered-hero', 0.85, 4, NOW(), NOW(), NOW()),
(@fish_user_id, 'layout', 'card-grid', 0.80, 3, NOW(), NOW(), NOW()),
(@fish_user_id, 'layout', 'sticky-nav', 0.70, 3, NOW(), NOW(), NOW()),
(@fish_user_id, 'layout', 'three-column', 0.50, 1, NOW(), NOW(), NOW()),
(@fish_user_id, 'layout', 'single-column', 0.55, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'layout', 'sidebar-heavy', -0.55, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'layout', 'masonry', -0.40, 1, NOW(), NOW(), NOW());

-- 组件偏好
INSERT INTO user_preference_weight (userId, preferenceType, preferenceKey, weight, evidenceCount, lastEvidenceAt, createTime, updateTime) VALUES
(@fish_user_id, 'component', 'nav-bar', 0.75, 3, NOW(), NOW(), NOW()),
(@fish_user_id, 'component', 'article-card', 0.80, 3, NOW(), NOW(), NOW()),
(@fish_user_id, 'component', 'feature-grid', 0.65, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'component', 'light-footer', 0.55, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'component', 'tag-pills', 0.60, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'component', 'hero-cta', 0.50, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'component', 'mega-menu', -0.60, 1, NOW(), NOW(), NOW()),
(@fish_user_id, 'component', 'carousel', -0.70, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'component', 'popup-modal', -0.80, 2, NOW(), NOW(), NOW());

-- 交互偏好
INSERT INTO user_preference_weight (userId, preferenceType, preferenceKey, weight, evidenceCount, lastEvidenceAt, createTime, updateTime) VALUES
(@fish_user_id, 'interaction', 'subtle-hover', 0.85, 3, NOW(), NOW(), NOW()),
(@fish_user_id, 'interaction', 'fade-transition', 0.70, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'interaction', 'smooth-scroll', 0.65, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'interaction', '200ms-ease', 0.60, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'interaction', 'large-scale-hover', -0.75, 2, NOW(), NOW(), NOW()),
(@fish_user_id, 'interaction', 'bounce', -0.65, 1, NOW(), NOW(), NOW()),
(@fish_user_id, 'interaction', 'auto-scroll', -0.70, 1, NOW(), NOW(), NOW());

-- ===================== 3. 用户行为事件（模拟历史记录） =====================

INSERT INTO user_design_event (userId, generationId, eventType, metadata, createTime) VALUES
(@fish_user_id, @fish_user_id, 'page_saved', '{"page":"personal-blog","style":"minimal"}', DATE_SUB(NOW(), INTERVAL 5 DAY)),
(@fish_user_id, @fish_user_id, 'color_changed', '{"from":"dark","to":"light-green","component":"hero"}', DATE_SUB(NOW(), INTERVAL 4 DAY)),
(@fish_user_id, @fish_user_id, 'layout_changed', '{"from":"sidebar","to":"centered-hero","page":"home"}', DATE_SUB(NOW(), INTERVAL 3 DAY)),
(@fish_user_id, @fish_user_id, 'component_added', '{"component":"article-card","page":"blog-list"}', DATE_SUB(NOW(), INTERVAL 3 DAY)),
(@fish_user_id, @fish_user_id, 'color_changed', '{"from":"gray","to":"emerald","component":"button"}', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(@fish_user_id, @fish_user_id, 'component_removed', '{"component":"carousel","reason":"too-distracting"}', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(@fish_user_id, @fish_user_id, 'page_exported', '{"page":"personal-blog","format":"zip"}', DATE_SUB(NOW(), INTERVAL 1 DAY)),
(@fish_user_id, @fish_user_id, 'color_changed', '{"from":"default","to":"light-green","component":"nav-bar"}', NOW()),
(@fish_user_id, @fish_user_id, 'layout_changed', '{"from":"grid-4","to":"card-grid","component":"features"}', NOW()),
(@fish_user_id, @fish_user_id, 'component_removed', '{"component":"popup-modal","reason":"bad-ux"}', NOW());
