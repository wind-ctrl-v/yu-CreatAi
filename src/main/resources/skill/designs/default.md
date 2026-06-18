# Default 中性通用设计规范

> 兜底规范：当 styleProfile 无法识别、置信度过低或对应规范加载失败时使用。中性、克制、跨行业普适。

priority: 50

## tone

- 克制
- 中性
- 通用

## tokens

```css
:root {
  --color-primary-50: #eef2ff;
  --color-primary-500: #4f46e5;
  --color-primary-600: #4338ca;
  --color-secondary-500: #0ea5e9;
  --color-bg: #ffffff;
  --color-surface: #f8fafc;
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
  --font-size-3xl: 36px;

  --leading-tight: 1.25;
  --leading-normal: 1.5;
  --leading-relaxed: 1.75;

  --tracking-tight: -0.01em;
  --tracking-normal: 0;
  --tracking-wide: 0.02em;

  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-6: 24px;
  --space-8: 32px;
  --space-12: 48px;

  --radius-sm: 4px;
  --radius-md: 8px;
  --radius-lg: 16px;
  --radius-full: 9999px;

  --shadow-sm: 0 1px 2px rgba(15,23,42,.06);
  --shadow-md: 0 4px 12px rgba(15,23,42,.08);
}
```

## layout

- 顶部固定导航 + 主内容区 + 页脚的经典三段式
- 12 栅格弹性布局，区块之间用 --space-8/--space-12 拉开节奏
- 充足留白，单屏聚焦一个主要信息层级

## preferred

- 所有颜色/字号/间距/圆角/阴影一律引用 var(--xxx) token
- 卡片用 1px --color-border 描边 + --shadow-sm 轻阴影区分层级
- 正文行高用 --leading-normal，标题用 --leading-tight

## interaction

- hover 仅做轻微颜色/阴影变化，过渡 150-200ms ease
- 避免大幅缩放与位移，交互克制

## forbidden

- 禁止 backdrop-filter 毛玻璃效果
- 禁止纯装饰性的 linear-gradient / radial-gradient 背景
- 禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token
- 禁止硬编码十六进制/rgb 颜色，必须用 var(--color-*)
- 禁止 hover 时 transform: scale() 超过 1.05 的浮夸放大
