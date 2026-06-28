-- ============================================================
-- 知识库检索效率测试脚本
-- ============================================================

-- 测试1: 知识库概览
SELECT '=== 1.知识库概览 ===' AS '';
SELECT COUNT(*) AS 总指纹, COUNT(CASE WHEN status='APPROVED' THEN 1 END) AS 已审核 FROM design_fingerprint;
SELECT styleProfile, COUNT(*) AS num FROM design_fingerprint WHERE status='APPROVED' GROUP BY styleProfile;

-- 测试2: 用户偏好权重
SELECT '=== 2.偏好权重 ===' AS '';
SELECT preferenceType, COUNT(*) AS entries, ROUND(AVG(weight),2) AS avgWeight FROM user_preference_weight WHERE userId=425789882267344896 GROUP BY preferenceType;

-- 测试3: 用户画像
SELECT '=== 3.用户画像 ===' AS '';
SELECT profileSummary FROM user_design_profile WHERE userId=425789882267344896;

-- 测试4: STRUCTURAL_3A —— 按风格检索
SELECT '=== 4a.搜索startup ===' AS '';
SELECT id, styleProfile, confidence, JSON_EXTRACT(fingerprintJson, '$.tone') AS tone, JSON_EXTRACT(fingerprintJson, '$.layout.skeleton') AS skeleton FROM design_fingerprint WHERE status='APPROVED' AND styleProfile='startup' ORDER BY priority DESC, confidence DESC LIMIT 3;

SELECT '=== 4b.搜索landing ===' AS '';
SELECT id, styleProfile, confidence, JSON_EXTRACT(fingerprintJson, '$.layout.skeleton') AS skeleton FROM design_fingerprint WHERE status='APPROVED' AND styleProfile='landing' ORDER BY priority DESC, confidence DESC LIMIT 3;

SELECT '=== 4c.搜索default ===' AS '';
SELECT id, styleProfile, confidence, priority FROM design_fingerprint WHERE status='APPROVED' AND styleProfile='default' ORDER BY priority DESC, confidence DESC LIMIT 3;

-- 测试5: 综合检索（STRUCTURAL_3A + 评分排序模拟）
SELECT '=== 5.综合检索(default+landing+startup) ===' AS '';
SELECT id, styleProfile, confidence, priority,
  JSON_EXTRACT(fingerprintJson, '$.layout.skeleton') AS layout,
  JSON_EXTRACT(fingerprintJson, '$.tokens.colorRatio') AS colors
FROM design_fingerprint
WHERE status='APPROVED' AND styleProfile IN ('default','landing','startup')
ORDER BY priority DESC, confidence DESC LIMIT 5;
