SET @uid = 425789882267344896;
DELETE FROM user_preference_weight WHERE userId = @uid;
DELETE FROM user_design_event WHERE userId = @uid;
INSERT INTO user_preference_weight (id, userId, preferenceType, preferenceKey, weight, evidenceCount, lastEvidenceAt, createTime, updateTime) VALUES
(425789882267344900, @uid, 'style', 'minimal', 0.85, 3, NOW(), NOW(), NOW()),
(425789882267344901, @uid, 'style', 'clean', 0.80, 3, NOW(), NOW(), NOW()),
(425789882267344902, @uid, 'style', 'modern', 0.70, 2, NOW(), NOW(), NOW()),
(425789882267344903, @uid, 'style', 'tech', 0.60, 2, NOW(), NOW(), NOW()),
(425789882267344904, @uid, 'style', 'playful', -0.50, 1, NOW(), NOW(), NOW()),
(425789882267344905, @uid, 'color', 'light-green', 0.90, 4, NOW(), NOW(), NOW()),
(425789882267344906, @uid, 'color', 'blue', 0.75, 3, NOW(), NOW(), NOW()),
(425789882267344907, @uid, 'color', 'emerald', 0.65, 2, NOW(), NOW(), NOW()),
(425789882267344908, @uid, 'color', 'neon', -0.80, 2, NOW(), NOW(), NOW()),
(425789882267344909, @uid, 'color', 'hot-pink', -0.70, 1, NOW(), NOW(), NOW()),
(425789882267344910, @uid, 'layout', 'centered-hero', 0.85, 4, NOW(), NOW(), NOW()),
(425789882267344911, @uid, 'layout', 'card-grid', 0.80, 3, NOW(), NOW(), NOW()),
(425789882267344912, @uid, 'layout', 'sticky-nav', 0.65, 3, NOW(), NOW(), NOW()),
(425789882267344913, @uid, 'layout', 'sidebar-heavy', -0.55, 2, NOW(), NOW(), NOW()),
(425789882267344914, @uid, 'component', 'article-card', 0.80, 3, NOW(), NOW(), NOW()),
(425789882267344915, @uid, 'component', 'nav-bar', 0.75, 3, NOW(), NOW(), NOW()),
(425789882267344916, @uid, 'component', 'feature-grid', 0.60, 2, NOW(), NOW(), NOW()),
(425789882267344917, @uid, 'component', 'carousel', -0.70, 2, NOW(), NOW(), NOW()),
(425789882267344918, @uid, 'component', 'popup-modal', -0.80, 2, NOW(), NOW(), NOW()),
(425789882267344919, @uid, 'interaction', 'subtle-hover', 0.85, 3, NOW(), NOW(), NOW()),
(425789882267344920, @uid, 'interaction', 'fade-transition', 0.70, 2, NOW(), NOW(), NOW()),
(425789882267344921, @uid, 'interaction', 'large-hover-scale', -0.75, 2, NOW(), NOW(), NOW());
INSERT INTO user_design_event (id, userId, generationId, eventType, metadata, createTime) VALUES
(425789882267344930, @uid, @uid, 'page_saved', '{"page":"personal-blog"}', DATE_SUB(NOW(), INTERVAL 5 DAY)),
(425789882267344931, @uid, @uid, 'color_changed', '{"from":"dark","to":"light-green"}', DATE_SUB(NOW(), INTERVAL 4 DAY)),
(425789882267344932, @uid, @uid, 'layout_changed', '{"from":"sidebar","to":"centered-hero"}', DATE_SUB(NOW(), INTERVAL 3 DAY)),
(425789882267344933, @uid, @uid, 'component_added', '{"component":"article-card"}', DATE_SUB(NOW(), INTERVAL 3 DAY)),
(425789882267344934, @uid, @uid, 'component_removed', '{"component":"carousel"}', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(425789882267344935, @uid, @uid, 'color_changed', '{"from":"gray","to":"emerald"}', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(425789882267344936, @uid, @uid, 'page_exported', '{}', DATE_SUB(NOW(), INTERVAL 1 DAY)),
(425789882267344937, @uid, @uid, 'layout_changed', '{"to":"card-grid"}', NOW()),
(425789882267344938, @uid, @uid, 'color_changed', '{"to":"blue"}', NOW()),
(425789882267344939, @uid, @uid, 'component_removed', '{"component":"popup-modal"}', NOW()),
(425789882267344940, @uid, @uid, 'page_saved', '{"page":"tech-blog"}', NOW()),
(425789882267344941, @uid, @uid, 'color_changed', '{"to":"slate-gray"}', NOW());
