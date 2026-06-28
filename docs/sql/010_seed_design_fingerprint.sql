-- ============================================================
-- 设计指纹种子数据
-- 用途：为知识库提供初始 APPROVED 指纹，供向量检索使用
-- 执行：将本文件导入 MySQL yu_ai_code_mother 数据库
-- ============================================================

-- 注意：id 使用雪花算法，此处使用占位符，实际执行时由应用层生成
-- 以下 INSERT 展示六层指纹 JSON 结构，用于理解数据格式和人工审核

-- 场景 1：现代科技博客（源：精选页面）
INSERT INTO design_fingerprint (
    style_profile, status, confidence, priority, source_scope, source_type,
    fingerprint_json, signature, created_at, updated_at
) VALUES (
    'blog', 'APPROVED', 0.88, 35, 'GLOBAL', 'EXTRACTED_FINGERPRINT',
    '{
        "schemaVersion": "v1",
        "styleProfile": "blog",
        "sourceType": "EXTRACTED_FINGERPRINT",
        "tokens": {
            "colorRatio": "暖色为主，主色翠绿占15%，暖棕点缀5%",
            "fontScale": ["36/28/17/14"],
            "radii": "3px-6px 小圆角，柔和阅读感",
            "shadow": "极轻阴影，0-4px 范围"
        },
        "layout": {
            "skeleton": "Header + 文章卡片列表 + 侧边栏（可选）+ Footer",
            "whitespace": "宽松，正文区 max-width 680px",
            "focus": "文章标题与首图"
        },
        "components": ["article-card", "responsive-nav", "tag-pills", "light-code-block"],
        "interaction": {
            "motion": "克制",
            "transition": "150-200ms ease"
        },
        "antiPatterns": ["no-backdrop-filter", "no-decorative-gradient", "no-large-hover-scale"],
        "tone": ["温暖", "书香", "阅读优先"]
    }',
    MD5('blog_seed_001'), NOW(), NOW()
);

-- 场景 2：SaaS 启动页（源：精选页面）
INSERT INTO design_fingerprint (
    style_profile, status, confidence, priority, source_scope, source_type,
    fingerprint_json, signature, created_at, updated_at
) VALUES (
    'startup', 'APPROVED', 0.92, 40, 'GLOBAL', 'EXTRACTED_FINGERPRINT',
    '{
        "schemaVersion": "v1",
        "styleProfile": "startup",
        "sourceType": "EXTRACTED_FINGERPRINT",
        "tokens": {
            "colorRatio": "紫主色70% + 青辅助色20% + 中性白10%",
            "fontScale": ["44/32/24/16"],
            "radii": "12px-20px 大圆角",
            "shadow": "紫调阴影，0 8px 24px rgba(124,58,237,.10)"
        },
        "layout": {
            "skeleton": "Hero大标题 + 三栏特性卡片 + 定价对比表",
            "whitespace": "充足，区块间距 36-56px",
            "focus": "中央定价卡，推荐档主色描边"
        },
        "components": ["hero-cta", "three-col-feature-card", "pricing-comparison-card", "sticky-nav"],
        "interaction": {
            "motion": "现代流畅",
            "transition": "160-200ms ease"
        },
        "antiPatterns": ["no-backdrop-filter", "no-fullpage-gradient-bg", "no-hover-scale-above-1.03"],
        "tone": ["现代", "科技感", "年轻"]
    }',
    MD5('startup_seed_001'), NOW(), NOW()
);

-- 场景 3：电商产品页（源：精选页面）
INSERT INTO design_fingerprint (
    style_profile, status, confidence, priority, source_scope, source_type,
    fingerprint_json, signature, created_at, updated_at
) VALUES (
    'ecommerce', 'APPROVED', 0.85, 35, 'GLOBAL', 'EXTRACTED_FINGERPRINT',
    '{
        "schemaVersion": "v1",
        "styleProfile": "ecommerce",
        "sourceType": "EXTRACTED_FINGERPRINT",
        "tokens": {
            "colorRatio": "品牌色橙主色25% + 中性白灰75%",
            "fontScale": ["32/22/18/14"],
            "radii": "8px-12px 适中圆角",
            "shadow": "多层卡片阴影，hover 加深"
        },
        "layout": {
            "skeleton": "TopNav + Hero大图 + 商品网格 + 筛选侧边栏 + Footer",
            "whitespace": "紧凑高效，商品之间 16px",
            "focus": "商品主图居中偏上"
        },
        "components": ["product-grid", "price-tag", "rating-stars", "cart-button", "category-filter"],
        "interaction": {
            "motion": "流畅响应",
            "transition": "200ms ease-in-out"
        },
        "antiPatterns": ["no-auto-play-carousel", "no-popup-overlay-mobile", "no-dark-bg"],
        "tone": ["活力", "可信", "购物友好"]
    }',
    MD5('ecommerce_seed_001'), NOW(), NOW()
);

-- 场景 4：营销活动落地页（源：精选页面）
INSERT INTO design_fingerprint (
    style_profile, status, confidence, priority, source_scope, source_type,
    fingerprint_json, signature, created_at, updated_at
) VALUES (
    'default', 'APPROVED', 0.80, 30, 'GLOBAL', 'EXTRACTED_FINGERPRINT',
    '{
        "schemaVersion": "v1",
        "styleProfile": "default",
        "sourceType": "EXTRACTED_FINGERPRINT",
        "tokens": {
            "colorRatio": "红主色60% + 白背景30% + 点缀10%",
            "fontScale": ["56/40/28/16"],
            "radii": "14px 胶囊形按钮，24px 大半径区",
            "shadow": "红色调深阴影，0 12px 40px rgba..."
        },
        "layout": {
            "skeleton": "Hero超大标题 + 三栏卖点 + Logo墙 + 双CTA收尾",
            "whitespace": "区块间距 72-96px，制造视觉节奏",
            "focus": "CTA 按钮区域"
        },
        "components": ["hero-big-title", "feature-icon-blocks", "logo-wall", "double-cta-section"],
        "interaction": {
            "motion": "醒目有力",
            "transition": "200-250ms ease"
        },
        "antiPatterns": ["no-backdrop-filter", "no-contrast-too-low", "no-title-below-2xl"],
        "tone": ["高转化", "动感", "冲击力"]
    }',
    MD5('marketing_seed_001'), NOW(), NOW()
);

-- 场景 5：企业后台仪表盘（源：精选页面）
INSERT INTO design_fingerprint (
    style_profile, status, confidence, priority, source_scope, source_type,
    fingerprint_json, signature, created_at, updated_at
) VALUES (
    'dashboard', 'APPROVED', 0.90, 38, 'GLOBAL', 'EXTRACTED_FINGERPRINT',
    '{
        "schemaVersion": "v1",
        "styleProfile": "dashboard",
        "sourceType": "EXTRACTED_FINGERPRINT",
        "tokens": {
            "colorRatio": "深蓝主色10% + 白灰90%",
            "fontScale": ["22/16/14/12"],
            "radii": "6px-10px 统一圆角",
            "shadow": "极轻卡片阴影，信息层级区分"
        },
        "layout": {
            "skeleton": "左侧导航 + 顶栏 + 内容区（统计卡片 + 图表 + 表格）",
            "whitespace": "紧凑高效，卡片间距 16px",
            "focus": "顶部 KPI 统计卡片"
        },
        "components": ["sidebar-nav", "stats-card", "data-table", "chart-container", "breadcrumb"],
        "interaction": {
            "motion": "极简克制",
            "transition": "150ms ease"
        },
        "antiPatterns": ["no-dark-mode-only", "no-auto-refresh-without-toggle", "no-3d-effects"],
        "tone": ["专业", "高效", "数据清晰"]
    }',
    MD5('dashboard_seed_001'), NOW(), NOW()
);

-- 场景 6：教育课程平台（源：精选页面）
INSERT INTO design_fingerprint (
    style_profile, status, confidence, priority, source_scope, source_type,
    fingerprint_json, signature, created_at, updated_at
) VALUES (
    'default', 'APPROVED', 0.82, 32, 'GLOBAL', 'EXTRACTED_FINGERPRINT',
    '{
        "schemaVersion": "v1",
        "styleProfile": "default",
        "sourceType": "EXTRACTED_FINGERPRINT",
        "tokens": {
            "colorRatio": "蓝主色20% + 白背景70% + 琥珀高亮10%",
            "fontScale": ["38/28/18/14"],
            "radii": "6px-10px 统一",
            "shadow": "轻阴影，0 4px 14px rgba(15,23,42,.06)"
        },
        "layout": {
            "skeleton": "Hero Banner + 分类Tab + 课程网格(3-4列) + 名师介绍",
            "whitespace": "适中，课程卡片间距 16px",
            "focus": "推荐课程首屏居中"
        },
        "components": ["course-card", "progress-bar", "rating-stars", "category-tabs", "teacher-avatar"],
        "interaction": {
            "motion": "温和流畅",
            "transition": "200ms ease"
        },
        "antiPatterns": ["no-dark-bg", "no-auto-play-video", "no-distracting-animation"],
        "tone": ["专业可信", "清晰有条理", "学习友好"]
    }',
    MD5('education_seed_001'), NOW(), NOW()
);
