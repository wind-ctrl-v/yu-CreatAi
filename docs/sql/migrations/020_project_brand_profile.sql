-- =====================================================
-- Stage 3: 项目品牌记忆 — project_brand_profile 建表 + 种子数据
-- 用于存储每个项目的独立品牌画像，生成时代替全局默认。
-- =====================================================

CREATE TABLE IF NOT EXISTS project_brand_profile (
    id BIGINT PRIMARY KEY COMMENT '雪花ID主键',
    user_id BIGINT NOT NULL COMMENT '所属用户ID',
    project_id BIGINT NOT NULL COMMENT '项目ID（关联 app 表）',

    brand_name VARCHAR(255) NULL COMMENT '品牌名称',
    industry VARCHAR(128) NULL COMMENT '所属行业',
    target_audience TEXT NULL COMMENT '目标用户描述',

    brand_tone JSON NULL COMMENT '品牌调性标签数组 ["专业","前沿","可信赖"]',
    brand_colors JSON NULL COMMENT '品牌色规范 {"primary":"#2563EB","secondary":"#7C3AED","mode":"dark"}',
    typography_preference JSON NULL COMMENT '排版偏好',
    layout_preference JSON NULL COMMENT '布局偏好标签数组',
    component_preference JSON NULL COMMENT '组件偏好标签数组',

    design_guidelines JSON NULL COMMENT '设计规范描述',
    forbidden_rules JSON NULL COMMENT '设计禁则数组 ["卡通风","低龄化","过度装饰"]',

    profile_summary TEXT NULL COMMENT '画像摘要（自动生成）',

    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_delete TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',

    UNIQUE KEY uk_project_id (project_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目品牌画像';

-- =====================================================
-- 种子数据：为 fish 用户的第一个项目创建品牌画像
-- 模拟场景：fish 正在开发一个 AI SaaS 产品（Nova AI）
-- =====================================================

-- 注意：project_id 需要关联实际存在的 app.id
-- 如果 app 表中有 fish 创建的项目则直接关联，否则用占位值
INSERT IGNORE INTO project_brand_profile (
    id, user_id, project_id, brand_name, industry, target_audience,
    brand_tone, brand_colors, layout_preference, component_preference,
    design_guidelines, forbidden_rules, profile_summary
) VALUES (
    425789882267344950,  -- 雪花ID
    425789882267344896,  -- fish 用户ID
    425789882267344896,  -- projectId（占位，后续关联实际 app.id）
    'Nova AI',
    'AI SaaS',
    '企业客户、开发者、产品经理',
    '["专业","前沿","可信赖","克制"]',
    '{"primary":"#2563EB","secondary":"#7C3AED","bg":"#0F172A","text":"#E2E8F0","mode":"dark"}',
    '["centered-hero","card-grid","sticky-nav"]',
    '["nav-bar","feature-grid","pricing-card","testimonial-card"]',
    '{"品牌调性":"专业克制的AI SaaS品牌","设计语言":"深色背景+蓝紫渐变+清晰层级","交互":"悬停微动效+平滑过渡"}',
    '["卡通风","低龄化","过度装饰","暖色电商风","大量emoji","弹窗干扰"]',
    'Nova AI 是一个面向企业客户和开发者的 AI SaaS 产品。品牌调性强调专业、前沿和可信赖。主色深蓝 #2563EB，辅色紫色 #7C3AED，深色模式。偏好居中Hero+卡片网格布局，避免卡通风格和弹窗。'
);
