# Enterprise 企业级应用设计规范

> 面向 B 端企业官网 / 后台。稳重、专业、可信，克制保守。

priority: 60

## tone

- 稳重
- 专业
- 可信赖

## tokens

```css
:root {
  --color-primary-50: #eff6ff;
  --color-primary-500: #2563eb;
  --color-primary-600: #1d4ed8;
  --color-secondary-500: #0891b2;
  --color-bg: #ffffff;
  --color-surface: #f8fafc;
  --color-text: #1e293b;
  --color-text-muted: #64748b;
  --color-border: #e2e8f0;

  --font-family-base: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  --font-size-xs: 12px;
  --font-size-sm: 14px;
  --font-size-base: 15px;
  --font-size-lg: 18px;
  --font-size-xl: 22px;
  --font-size-2xl: 28px;
  --font-size-3xl: 34px;

  --leading-tight: 1.3;
  --leading-normal: 1.6;
  --leading-relaxed: 1.8;

  --tracking-tight: -0.005em;
  --tracking-normal: 0;
  --tracking-wide: 0.01em;

  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-6: 24px;
  --space-8: 32px;
  --space-12: 48px;

  --radius-sm: 3px;
  --radius-md: 6px;
  --radius-lg: 10px;
  --radius-full: 9999px;

  --shadow-sm: 0 1px 2px rgba(30,41,59,.05);
  --shadow-md: 0 2px 8px rgba(30,41,59,.08);
}
```

## layout

- 信息密度适中，模块化分区，强调清晰的信息层级
- 顶部导航含完整产品/解决方案/资源菜单，宽容器 1200px 居中
- 表格、表单等数据型组件对齐严谨，标签与控件留白一致

## preferred

- 配色以中性色为主，主色仅用于关键操作与强调
- 圆角偏小（--radius-sm/--radius-md），传达稳重感
- 阴影克制，仅用 --shadow-sm 区分卡片层级

## interaction

- hover 仅做背景/边框颜色过渡，180ms ease
- 不使用弹跳、缩放等活泼动效

## forbidden

- 禁止 backdrop-filter 毛玻璃效果
- 禁止装饰性渐变与霓虹光效
- 禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token
- 禁止硬编码颜色，必须用 var(--color-*)
- 禁止浮夸的 hover 放大与位移
