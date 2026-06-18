# Landing 营销落地页设计规范

> 面向单页营销 / 活动落地页。高对比、强引导、突出主 CTA。

priority: 60

## tone

- 高对比
- 醒目
- 强引导

## tokens

```css
:root {
  --color-primary-50: #fef2f2;
  --color-primary-500: #e11d48;
  --color-primary-600: #be123c;
  --color-secondary-500: #f59e0b;
  --color-bg: #ffffff;
  --color-surface: #fff7f8;
  --color-text: #0a0a0a;
  --color-text-muted: #6b6b6b;
  --color-border: #f0e3e5;

  --font-family-base: "Inter", -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif;
  --font-size-xs: 13px;
  --font-size-sm: 15px;
  --font-size-base: 17px;
  --font-size-lg: 20px;
  --font-size-xl: 26px;
  --font-size-2xl: 36px;
  --font-size-3xl: 52px;

  --leading-tight: 1.1;
  --leading-normal: 1.55;
  --leading-relaxed: 1.8;

  --tracking-tight: -0.025em;
  --tracking-normal: 0;
  --tracking-wide: 0.04em;

  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-6: 28px;
  --space-8: 44px;
  --space-12: 72px;

  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 18px;
  --radius-full: 9999px;

  --shadow-sm: 0 1px 3px rgba(10,10,10,.08);
  --shadow-md: 0 10px 30px rgba(225,29,72,.14);
}
```

## layout

- 首屏巨型标题（--font-size-3xl）+ 强 CTA，单一主行动召唤
- 区块依「痛点 → 方案 → 证言 → 价格 → 再次 CTA」的转化漏斗排列
- 关键转化点重复出现 CTA 按钮

## preferred

- 主 CTA 用 --color-primary-500 实色 + --font-size-lg + --radius-md，视觉最强
- 标题用 --leading-tight + --tracking-tight 增强冲击力
- 强调数字/卖点用主色或 --color-secondary-500

## interaction

- 主 CTA hover 用 --color-primary-600 加深 + --shadow-md 抬升，180ms ease
- 滚动进入区块可做轻微淡入，但不堆砌动效

## forbidden

- 禁止 backdrop-filter 毛玻璃效果
- 禁止整屏花哨装饰性渐变（主 CTA/强调块用纯色）
- 禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token
- 禁止硬编码颜色，必须用 var(--color-*)
- 禁止 hover scale 超过 1.04 的浮夸放大
