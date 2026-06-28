# Education 教育/课程平台设计规范

> 面向在线教育/知识付费/培训平台。专业可信、信息结构化、学习体验优先。

priority: 60

## tone

- 专业
- 可信
- 清晰有条理

## tokens

```css
:root {
  --color-primary-50: #eff6ff;
  --color-primary-500: #2563eb;
  --color-primary-600: #1d4ed8;
  --color-secondary-500: #f59e0b;
  --color-bg: #f8fafc;
  --color-surface: #ffffff;
  --color-text: #0f172a;
  --color-text-muted: #64748b;
  --color-border: #e2e8f0;

  --font-family-base: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
  --font-size-xs: 12px;
  --font-size-sm: 14px;
  --font-size-base: 16px;
  --font-size-lg: 18px;
  --font-size-xl: 22px;
  --font-size-2xl: 28px;
  --font-size-3xl: 38px;

  --leading-tight: 1.25;
  --leading-normal: 1.55;
  --leading-relaxed: 1.75;

  --tracking-tight: -0.01em;
  --tracking-normal: 0;
  --tracking-wide: 0.03em;

  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-6: 24px;
  --space-8: 36px;
  --space-12: 56px;

  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 16px;
  --radius-full: 9999px;

  --shadow-sm: 0 1px 3px rgba(15,23,42,.04);
  --shadow-md: 0 4px 14px rgba(15,23,42,.06);
}
```

## layout

- 顶部导航：logo + 课程分类 + 搜索 + 用户头像
- 首页：Hero Banner（热门课程）→ 分类导航 → 推荐课程网格 → 名师介绍
- 课程详情：视频/图文区域 + 目录大纲 + 讲师信息 + 评价 + 侧边购买卡
- 仪表盘（学习中心）：进度条、课程列表、学习统计

## preferred

- 主色用蓝色系（--color-primary-500），传递专业与信任
- 课程卡片：封面图 + 标题 + 讲师 + 价格 + 评分星级
- 进度条/标签用 --color-secondary-500 琥珀色高亮
- 课程网格使用 3-4 列弹性布局，间距均匀
- 字体清晰易读，正文不小于 14px

## interaction

- 课程卡片 hover 轻微上移 + --shadow-md，200ms ease
- 标签页切换使用下划线滑动动画，250ms ease
- 购买按钮使用 --radius-full 胶囊形，hover 加深色

## forbidden

- 禁止 backdrop-filter 毛玻璃效果
- 禁止暗色背景（教育场景以浅色为主，保证长时间阅读舒适）
- 禁止花哨动画分散学习注意力
- 禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token
- 禁止硬编码颜色，必须用 var(--color-*)
