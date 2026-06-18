# Portfolio 作品集/个人站设计规范

> 面向设计师 / 摄影 / 创意个人站。极简、留白克制、内容即主角。

priority: 60

## tone

- 极简
- 留白
- 内容优先

## tokens

```css
:root {
  --color-primary-50: #f5f5f5;
  --color-primary-500: #111111;
  --color-primary-600: #000000;
  --color-secondary-500: #888888;
  --color-bg: #ffffff;
  --color-surface: #fafafa;
  --color-text: #111111;
  --color-text-muted: #8a8a8a;
  --color-border: #ededed;

  --font-family-base: "Helvetica Neue", -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif;
  --font-size-xs: 12px;
  --font-size-sm: 14px;
  --font-size-base: 16px;
  --font-size-lg: 20px;
  --font-size-xl: 26px;
  --font-size-2xl: 38px;
  --font-size-3xl: 56px;

  --leading-tight: 1.1;
  --leading-normal: 1.6;
  --leading-relaxed: 2;

  --tracking-tight: -0.03em;
  --tracking-normal: 0;
  --tracking-wide: 0.08em;

  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 20px;
  --space-6: 32px;
  --space-8: 48px;
  --space-12: 80px;

  --radius-sm: 0px;
  --radius-md: 2px;
  --radius-lg: 4px;
  --radius-full: 9999px;

  --shadow-sm: none;
  --shadow-md: 0 6px 24px rgba(0,0,0,.06);
}
```

## layout

- 超大标题（--font-size-3xl）+ 大量留白（--space-12）营造画廊感
- 作品以大图网格或瀑布流呈现，图片之间留白一致
- 极简顶部导航，仅 logo + 少量链接

## preferred

- 近乎无圆角（--radius-sm: 0），强调硬朗几何
- 黑白为主，强调字体与排版本身
- 字间距：大标题用 --tracking-tight，小标签用 --tracking-wide

## interaction

- 图片 hover 用透明度或轻微遮罩过渡，250ms ease
- 不使用彩色光效与缩放

## forbidden

- 禁止 backdrop-filter 毛玻璃效果
- 禁止任何装饰性渐变与彩色阴影
- 禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token
- 禁止硬编码颜色，必须用 var(--color-*)
- 禁止浮夸缩放，破坏极简克制感
