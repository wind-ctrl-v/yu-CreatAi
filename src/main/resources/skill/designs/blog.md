# Blog 个人博客设计规范

> 面向个人博客/技术博客/独立写作站。温暖、阅读感优先、排版精致。

priority: 65

## tone

- 温暖
- 书香
- 阅读友好

## tokens

```css
:root {
  --color-primary-50: #f0fdf4;
  --color-primary-500: #16a34a;
  --color-primary-600: #15803d;
  --color-secondary-500: #d4a574;
  --color-bg: #fefdfb;
  --color-surface: #ffffff;
  --color-text: #1c1917;
  --color-text-muted: #78716c;
  --color-border: #e7e5e4;

  --font-family-base: "Noto Serif SC", "Source Han Serif SC", Georgia, "PingFang SC", serif;
  --font-family-heading: "Noto Serif SC", "Source Han Serif SC", Georgia, serif;
  --font-size-xs: 12px;
  --font-size-sm: 14px;
  --font-size-base: 17px;
  --font-size-lg: 19px;
  --font-size-xl: 22px;
  --font-size-2xl: 28px;
  --font-size-3xl: 36px;

  --leading-tight: 1.25;
  --leading-normal: 1.75;
  --leading-relaxed: 1.9;

  --tracking-tight: -0.01em;
  --tracking-normal: 0;
  --tracking-wide: 0.04em;

  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-6: 28px;
  --space-8: 40px;
  --space-12: 64px;

  --radius-sm: 3px;
  --radius-md: 6px;
  --radius-lg: 12px;
  --radius-full: 9999px;

  --shadow-sm: 0 1px 2px rgba(28,25,23,.04);
  --shadow-md: 0 4px 16px rgba(28,25,23,.06);
}
```

## layout

- 文章内 max-width: 680px 居中，保证最佳阅读行宽
- 首页以文章卡片列表为主，按时间倒序
- 文章详情页：大标题 + 元信息（日期/分类/标签）+ 正文 + 评论区
- 侧边栏（可选）：作者简介、分类标签云、热门文章

## preferred

- 正文用 serif 字体（--font-family-base），标题可保持 serif 或混用 sans-serif
- 行高用 --leading-relaxed（1.8-1.9），保证长文可读性
- 浅暖色作为主色（如翠绿 --color-primary-500），点缀暖棕（--color-secondary-500）
- 卡片：--color-surface 背景 + --shadow-sm 微阴影 + 1px --color-border
- 代码块用浅灰背景，保持与正文字体区分

## interaction

- 文章卡片 hover 轻微上移 2px + 阴影加深，200ms ease
- 链接 hover 颜色过渡 150ms
- 整体克制，以阅读体验为主

## forbidden

- 禁止 backdrop-filter 毛玻璃效果
- 禁止大面积装饰性渐变
- 禁止正文使用过小字号（< 16px）
- 禁止硬编码字号/间距/圆角 px（1px 边框除外），必须用 token
- 禁止硬编码颜色，必须用 var(--color-*)
- 禁止 hover scale 超过 1.02
