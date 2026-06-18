# Startup 创业科技产品设计规范

> 面向 SaaS / 创业产品官网与控制台。年轻、现代、有科技感但不浮夸。

priority: 60

## tone

- 现代
- 科技
- 年轻有活力

## tokens

```css
:root {
  --color-primary-50: #f5f3ff;
  --color-primary-500: #7c3aed;
  --color-primary-600: #6d28d9;
  --color-secondary-500: #06b6d4;
  --color-bg: #ffffff;
  --color-surface: #faf9ff;
  --color-text: #18181b;
  --color-text-muted: #71717a;
  --color-border: #ece9f5;

  --font-family-base: "Inter", -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif;
  --font-size-xs: 12px;
  --font-size-sm: 14px;
  --font-size-base: 16px;
  --font-size-lg: 19px;
  --font-size-xl: 24px;
  --font-size-2xl: 32px;
  --font-size-3xl: 44px;

  --leading-tight: 1.2;
  --leading-normal: 1.55;
  --leading-relaxed: 1.8;

  --tracking-tight: -0.02em;
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
  --radius-md: 12px;
  --radius-lg: 20px;
  --radius-full: 9999px;

  --shadow-sm: 0 1px 3px rgba(24,24,27,.06);
  --shadow-md: 0 8px 24px rgba(124,58,237,.10);
}
```

## layout

- Hero 大标题 + 副标题 + 主 CTA，留白充足，视觉重心在 CTA
- 三栏特性卡片展示核心卖点
- 定价区用对比卡片，推荐档用主色描边突出

## preferred

- 标题用 --font-size-3xl / --font-size-2xl + --leading-tight + --tracking-tight
- 主按钮用 --color-primary-500 实色背景 + --radius-md
- 卡片用 --color-surface 背景 + 1px --color-border + --shadow-sm

## interaction

- 按钮 hover 用 --color-primary-600 加深，过渡 160ms ease
- 卡片 hover 仅抬升 --shadow-md，不放大不位移

## forbidden

- 禁止 backdrop-filter 毛玻璃效果
- 禁止整页装饰性渐变背景（主色块可用纯色）
- 禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token
- 禁止硬编码颜色，必须用 var(--color-*)
- 禁止 hover scale 超过 1.03 的浮夸放大
