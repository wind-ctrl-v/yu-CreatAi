# Dashboard 数据后台设计规范

> 面向数据看板 / 管理后台。信息密集、紧凑、强调可读性与对齐。

priority: 60

## tone

- 紧凑
- 高效
- 数据清晰

## tokens

```css
:root {
  --color-primary-50: #ecfeff;
  --color-primary-500: #0d9488;
  --color-primary-600: #0f766e;
  --color-secondary-500: #6366f1;
  --color-bg: #f1f5f9;
  --color-surface: #ffffff;
  --color-text: #0f172a;
  --color-text-muted: #64748b;
  --color-border: #e2e8f0;

  --font-family-base: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", sans-serif;
  --font-size-xs: 11px;
  --font-size-sm: 13px;
  --font-size-base: 14px;
  --font-size-lg: 16px;
  --font-size-xl: 20px;
  --font-size-2xl: 24px;
  --font-size-3xl: 30px;

  --leading-tight: 1.2;
  --leading-normal: 1.45;
  --leading-relaxed: 1.6;

  --tracking-tight: -0.005em;
  --tracking-normal: 0;
  --tracking-wide: 0.01em;

  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-6: 20px;
  --space-8: 28px;
  --space-12: 40px;

  --radius-sm: 4px;
  --radius-md: 6px;
  --radius-lg: 10px;
  --radius-full: 9999px;

  --shadow-sm: 0 1px 2px rgba(15,23,42,.05);
  --shadow-md: 0 2px 6px rgba(15,23,42,.07);
}
```

## layout

- 左侧固定侧边栏导航 + 顶部工具条 + 主内容网格
- 指标卡（KPI）成行排列，图表区与表格区分块
- 页面背景用 --color-bg 浅灰，卡片用 --color-surface 白色浮起

## preferred

- 紧凑间距（--space-2/--space-3 为主），提高信息密度
- 数字与指标用 --font-size-2xl 强调，标签用 --font-size-xs/--color-text-muted
- 表格行高一致，斑马纹或细分隔线用 --color-border

## interaction

- 行 hover 用 --color-surface 微调高亮，120ms ease
- 不使用大动效，保证大量数据下的流畅

## forbidden

- 禁止 backdrop-filter 毛玻璃效果
- 禁止装饰性渐变背景，背景仅用纯色 token
- 禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token
- 禁止硬编码颜色，必须用 var(--color-*)
- 禁止 hover 放大卡片导致布局抖动
