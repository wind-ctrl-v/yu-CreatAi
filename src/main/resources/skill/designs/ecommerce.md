# Ecommerce 电商购物设计规范

> 面向电商 / 零售。强调商品视觉、转化引导，活力但不杂乱。

priority: 60

## tone

- 活力
- 清晰
- 转化导向

## tokens

```css
:root {
  --color-primary-50: #fff7ed;
  --color-primary-500: #ea580c;
  --color-primary-600: #c2410c;
  --color-secondary-500: #059669;
  --color-bg: #ffffff;
  --color-surface: #fafafa;
  --color-text: #1c1917;
  --color-text-muted: #78716c;
  --color-border: #e7e5e4;

  --font-family-base: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
  --font-size-xs: 12px;
  --font-size-sm: 13px;
  --font-size-base: 15px;
  --font-size-lg: 18px;
  --font-size-xl: 22px;
  --font-size-2xl: 28px;
  --font-size-3xl: 36px;

  --leading-tight: 1.25;
  --leading-normal: 1.5;
  --leading-relaxed: 1.7;

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
  --radius-lg: 14px;
  --radius-full: 9999px;

  --shadow-sm: 0 1px 2px rgba(28,25,23,.06);
  --shadow-md: 0 4px 14px rgba(28,25,23,.10);
}
```

## layout

- 商品网格：等高卡片，图片占主导，价格与 CTA 固定在卡片底部
- 顶部含搜索框、分类导航与购物车入口
- 商品详情左图右信息，加购 CTA 用主色高亮

## preferred

- 价格用 --font-size-xl + 主色或强调色突出
- 加入购物车按钮用 --color-primary-500 实色 + --radius-md
- 商品卡片 hover 仅抬升 --shadow-md，图片不变形

## interaction

- 卡片 hover 抬升阴影 + 边框点亮，过渡 150ms ease
- 加购按钮 active 轻微下压反馈，不用夸张动效

## forbidden

- 禁止 backdrop-filter 毛玻璃效果
- 禁止整屏装饰性渐变背景
- 禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token
- 禁止硬编码颜色，必须用 var(--color-*)
- 禁止 hover scale 超过 1.05 导致网格抖动
