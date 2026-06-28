# Marketing 营销落地页设计规范

> 面向产品发布/活动推广/App 下载引导。高转化、视觉张力强、信息密度适中。

priority: 65

## tone

- 醒目
- 动感
- 转化驱动

## tokens

```css
:root {
  --color-primary-50: #fff1f2;
  --color-primary-500: #e11d48;
  --color-primary-600: #be123c;
  --color-secondary-500: #f97316;
  --color-bg: #ffffff;
  --color-surface: #fafafa;
  --color-text: #18181b;
  --color-text-muted: #71717a;
  --color-border: #f4f4f5;

  --font-family-base: "Inter", -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif;
  --font-size-xs: 12px;
  --font-size-sm: 14px;
  --font-size-base: 16px;
  --font-size-lg: 20px;
  --font-size-xl: 28px;
  --font-size-2xl: 40px;
  --font-size-3xl: 56px;

  --leading-tight: 1.15;
  --leading-normal: 1.5;
  --leading-relaxed: 1.7;

  --tracking-tight: -0.03em;
  --tracking-normal: 0;
  --tracking-wide: 0.05em;

  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-6: 24px;
  --space-8: 40px;
  --space-12: 72px;
  --space-16: 96px;

  --radius-sm: 8px;
  --radius-md: 14px;
  --radius-lg: 24px;
  --radius-full: 9999px;

  --shadow-sm: 0 2px 4px rgba(24,24,27,.05);
  --shadow-md: 0 12px 40px rgba(225,29,72,.12);
  --shadow-lg: 0 20px 60px rgba(24,24,27,.10);
}
```

## layout

- Hero 区：超大标题（--font-size-3xl）+ 副标题 + 双 CTA 按钮 + 产品预览图/视频
- 分段展示：特性亮点（图标+标题+描述）→ 社会证明（用户量/评价/Logo 墙）→ 定价 → FAQ
- 底部强 CTA 收尾，整页形成"吸引→说服→转化"漏斗
- 区块间留白充足（--space-12/--space-16），制造节奏感

## preferred

- 主色使用高饱和度暖色（红/橙），制造紧迫感与行动冲动
- 按钮用 --radius-full 胶囊形 + 实色背景，文字大写 + --tracking-wide
- Hero 区可使用纯色渐变背景（如 --color-primary-500 到 --color-primary-600）
- 卡片用 --shadow-lg 深阴影提升层次感

## interaction

- CTA 按钮 hover 时 scale(1.04) + 加深 + --shadow-md，200ms ease
- 数字滚动计数器动画（用户量/下载量）
- 卡片 hover 上移 4px + --shadow-lg，250ms ease

## forbidden

- 禁止 backdrop-filter 毛玻璃效果
- 禁止彩色阴影过度花哨
- 禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token
- 禁止硬编码颜色，必须用 var(--color-*)
- Hero 标题禁止小于 --font-size-2xl
