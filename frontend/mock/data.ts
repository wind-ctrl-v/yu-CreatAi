/**
 * 离线演示 Mock 数据
 *
 * 仅用于本地无后端时的界面预览，全部为虚构数据。
 */

export interface MockUser {
  id: string
  userAccount: string
  /** 仅用于本地离线演示的固定口令，不涉及任何真实凭据 */
  password: string
  userName: string
  userAvatar?: string
  userProfile?: string
  userRole: 'user' | 'admin'
  planType: string
  createTime: string
}

export const DEMO_ACCOUNTS: MockUser[] = [
  {
    id: '1001',
    userAccount: 'demo',
    password: 'demo123456',
    userName: '演示用户',
    userProfile: '正在体验 CreatAi 的新界面，喜欢干净的排版与克制的配色。',
    userRole: 'user',
    planType: 'PRO',
    createTime: '2026-05-18 09:24:11',
  },
  {
    id: '1000',
    userAccount: 'admin',
    password: 'admin123456',
    userName: '平台管理员',
    userProfile: '负责平台内容治理与设计指纹审核。',
    userRole: 'admin',
    planType: 'ADMIN',
    createTime: '2026-01-06 10:02:35',
  },
]

const PLAN_MODES: Record<string, string[]> = {
  FREE: ['low_cost'],
  PRO: ['low_cost', 'standard'],
  PREMIUM: ['low_cost', 'standard', 'high_quality'],
  ENTERPRISE: ['low_cost', 'standard', 'high_quality'],
  ADMIN: ['low_cost', 'standard', 'high_quality'],
}

const PLAN_NAMES: Record<string, string> = {
  FREE: '免费用户',
  PRO: '普通会员',
  PREMIUM: '高级会员',
  ENTERPRISE: '企业版',
  ADMIN: '管理员',
}

export function planOf(planType: string) {
  return {
    planType,
    planName: PLAN_NAMES[planType] ?? '免费用户',
    maxMode: (PLAN_MODES[planType] ?? ['low_cost']).slice(-1)[0],
    availableModes: PLAN_MODES[planType] ?? ['low_cost'],
  }
}

export interface MockApp {
  id: string
  appName: string
  cover?: string
  initPrompt: string
  codeGenType: string
  deployKey?: string
  deployedTime?: string
  priority: number
  userId: string
  createTime: string
  updateTime: string
}

function userBrief(id: string) {
  const found = [...DEMO_ACCOUNTS, ...EXTRA_USERS].find((u) => u.id === id)
  return found
    ? {
        id: found.id,
        userAccount: found.userAccount,
        userName: found.userName,
        userAvatar: found.userAvatar,
        userRole: found.userRole,
        planType: found.planType,
      }
    : { id, userAccount: 'unknown', userName: '匿名用户', userRole: 'user' }
}

/** 额外用户（仅用于后台用户列表与作者展示） */
export const EXTRA_USERS: MockUser[] = [
  { id: '2001', userAccount: 'alex', password: '-', userName: 'Alex Chen', userRole: 'user', planType: 'PREMIUM', userProfile: '独立开发者', createTime: '2026-02-11 14:10:00' },
  { id: '2002', userAccount: 'mika', password: '-', userName: 'Mika', userRole: 'user', planType: 'PRO', userProfile: '摄影师', createTime: '2026-02-19 08:41:00' },
  { id: '2003', userAccount: 'lee', password: '-', userName: '李工', userRole: 'user', planType: 'FREE', userProfile: '前端实习生', createTime: '2026-03-02 19:33:00' },
  { id: '2004', userAccount: 'zoe', password: '-', userName: 'Zoe', userRole: 'user', planType: 'ENTERPRISE', userProfile: '品牌顾问', createTime: '2026-03-15 11:07:00' },
  { id: '2005', userAccount: 'nina', password: '-', userName: 'Nina', userRole: 'user', planType: 'FREE', userProfile: '甜品店店主', createTime: '2026-04-01 16:22:00' },
  { id: '2006', userAccount: 'kai', password: '-', userName: 'Kai', userRole: 'user', planType: 'PRO', userProfile: '开源作者', createTime: '2026-04-08 21:45:00' },
  { id: '2007', userAccount: 'sora', password: '-', userName: 'Sora', userRole: 'user', planType: 'PRO', userProfile: '插画师', createTime: '2026-04-20 10:15:00' },
  { id: '2008', userAccount: 'ryan', password: '-', userName: 'Ryan', userRole: 'user', planType: 'FREE', userProfile: '产品经理', createTime: '2026-05-06 13:52:00' },
  { id: '2009', userAccount: 'yuki', password: '-', userName: 'Yuki', userRole: 'user', planType: 'PREMIUM', userProfile: '花艺工作室主理人', createTime: '2026-05-21 09:30:00' },
  { id: '2010', userAccount: 'tao', password: '-', userName: '陶然', userRole: 'user', planType: 'FREE', userProfile: '大学生', createTime: '2026-06-11 20:05:00' },
]

/** 我的应用（demo 用户所属） */
export const MY_APPS: MockApp[] = [
  { id: '9001', appName: '清言咖啡馆官网', initPrompt: '帮我做一个现代风格的咖啡馆官网，包含首页、菜单和联系方式', codeGenType: 'vue_project', deployKey: 'coffee-1a2b', deployedTime: '2026-07-29 10:02:00', priority: 0, userId: '1001', createTime: '2026-07-28 09:12:00', updateTime: '2026-07-29 10:02:00' },
  { id: '9002', appName: '个人作品集v3', initPrompt: '极简黑白作品集，衬线标题，大量留白', codeGenType: 'html', priority: 0, userId: '1001', createTime: '2026-07-22 15:30:00', updateTime: '2026-07-22 15:30:00' },
  { id: '9003', appName: '团队周报看板', initPrompt: '做一个数据看板，展示团队本周进展与风险项', codeGenType: 'multi_file', priority: 0, userId: '1001', createTime: '2026-07-19 11:02:00', updateTime: '2026-07-19 11:02:00' },
  { id: '9004', appName: '花艺预约页', initPrompt: '奶油色调的花艺工作室预约页，含课程时间表', codeGenType: 'vue_project', deployKey: 'flower-77xz', deployedTime: '2026-07-12 09:00:00', priority: 0, userId: '1001', createTime: '2026-07-11 20:41:00', updateTime: '2026-07-12 09:00:00' },
  { id: '9005', appName: 'SaaS 定价页', initPrompt: '三档定价对比，突出中间档，带常见问题', codeGenType: 'html', priority: 0, userId: '1001', createTime: '2026-07-03 08:15:00', updateTime: '2026-07-03 08:15:00' },
  { id: '9006', appName: '在线课程详情页', initPrompt: '课程详情页，含讲师介绍、大纲与报名按钮', codeGenType: 'vue_project', priority: 0, userId: '1001', createTime: '2026-06-28 17:55:00', updateTime: '2026-06-28 17:55:00' },
  { id: '9007', appName: '读书笔记博客', initPrompt: '简洁的读书笔记博客，支持标签分类', codeGenType: 'html', priority: 0, userId: '1001', createTime: '2026-06-14 12:20:00', updateTime: '2026-06-14 12:20:00' },
  { id: '9008', appName: '活动报名落地页', initPrompt: '线下活动报名页，含倒计时与嘉宾列表', codeGenType: 'multi_file', priority: 0, userId: '1001', createTime: '2026-06-02 18:36:00', updateTime: '2026-06-02 18:36:00' },
]

/** 精选应用 */
export const GOOD_APPS: MockApp[] = [
  { id: '8001', appName: '极简博客模板', initPrompt: '黑白衬线博客，正文阅读优先', codeGenType: 'vue_project', deployKey: 'blog-01', priority: 99, userId: '2001', createTime: '2026-06-20 10:00:00', updateTime: '2026-06-20 10:00:00' },
  { id: '8002', appName: '摄影展示站', initPrompt: '大图排版的摄影作品展示', codeGenType: 'html', deployKey: 'photo-02', priority: 99, userId: '2002', createTime: '2026-06-18 10:00:00', updateTime: '2026-06-18 10:00:00' },
  { id: '8003', appName: '招聘落地页', initPrompt: '含职位列表与团队介绍的招聘页', codeGenType: 'multi_file', priority: 99, userId: '2003', createTime: '2026-06-15 10:00:00', updateTime: '2026-06-15 10:00:00' },
  { id: '8004', appName: '健身房官网', initPrompt: '力量感排版，含课程表与教练介绍', codeGenType: 'vue_project', deployKey: 'gym-04', priority: 99, userId: '2004', createTime: '2026-06-12 10:00:00', updateTime: '2026-06-12 10:00:00' },
  { id: '8005', appName: '甜品店菜单', initPrompt: '奶油色菜单页，突出招牌产品', codeGenType: 'html', priority: 99, userId: '2005', createTime: '2026-06-08 10:00:00', updateTime: '2026-06-08 10:00:00' },
  { id: '8006', appName: '开源项目主页', initPrompt: '文档风格的开源项目介绍页', codeGenType: 'vue_project', deployKey: 'oss-06', priority: 99, userId: '2006', createTime: '2026-06-01 10:00:00', updateTime: '2026-06-01 10:00:00' },
  { id: '8007', appName: '插画接稿页', initPrompt: '插画师接稿说明与报价表', codeGenType: 'html', priority: 99, userId: '2007', createTime: '2026-05-26 10:00:00', updateTime: '2026-05-26 10:00:00' },
  { id: '8008', appName: '产品路线图', initPrompt: '按季度展示产品路线图', codeGenType: 'multi_file', priority: 99, userId: '2008', createTime: '2026-05-20 10:00:00', updateTime: '2026-05-20 10:00:00' },
  { id: '8009', appName: '婚礼邀请函', initPrompt: '温柔米白色婚礼邀请函，含时间地点', codeGenType: 'html', deployKey: 'wed-09', priority: 99, userId: '2009', createTime: '2026-05-12 10:00:00', updateTime: '2026-05-12 10:00:00' },
]

export function withUser(app: MockApp) {
  return { ...app, user: userBrief(app.userId) }
}

/** 某应用的对话历史 */
export function chatHistoryOf(appId: string) {
  const app = [...MY_APPS, ...GOOD_APPS].find((a) => a.id === appId)
  const prompt = app?.initPrompt ?? '帮我做一个网站'
  return [
    {
      id: `${appId}-1`,
      message: prompt,
      messageType: 'user',
      appId,
      userId: '1001',
      createTime: '2026-07-28 09:12:05',
    },
    {
      id: `${appId}-2`,
      message: [
        '已按你的描述生成首版页面，主要做了这些事：',
        '',
        '· 采用米白底+ 近黑文字的中性配色，保证长时间阅读舒适',
        '· 首屏放大标题层级，副标题限制在两行以内',
        '· 内容区按 12栅格布局，卡片统一 14px 圆角与 1px 描边',
        '· 移动端在 768px 断点下收起为单列',
        '',
        '你可以继续描述想调整的部分，例如「把主色换成墨绿」或「首屏加一个预约按钮」。',
      ].join('\n'),
      messageType: 'ai',
      appId,
      userId: '1001',
      createTime: '2026-07-28 09:12:48',
    },
    {
      id: `${appId}-3`,
      message: '标题再大一点，另外把导航栏改成半透明毛玻璃效果',
      messageType: 'user',
      appId,
      userId: '1001',
      createTime: '2026-07-28 09:20:12',
    },
    {
      id: `${appId}-4`,
      message: [
        '· 已将首屏标题从 44px 提升到 clamp(40px, 6vw, 68px)，并收紧字距到 -0.03em',
        '· 导航栏改为 rgba(255,255,255,0.82) + backdrop-filter: blur(14px)，底部保留 1px 分隔线',
        '✓ 修改完成',
      ].join('\n'),
      messageType: 'ai',
      appId,
      userId: '1001',
      createTime: '2026-07-28 09:20:55',
    },
  ]
}

/** 后台：全平台对话历史 */
export const ADMIN_CHATS = Array.from({ length: 47 }, (_, i) => {
  const isAi = i % 2 === 1
  const app = [...MY_APPS, ...GOOD_APPS][i % 17]
  return {
    id: `7${String(1000 + i)}`,
    message: isAi
      ? `已完成第 ${Math.floor(i / 2) + 1} 轮调整：统一了卡片圆角与阴影层级，并将正文行高调整为 1.6，提升可读性。若还需要修改配色或信息密度，可以继续告诉我。`
      : `把${app?.appName ?? '页面'}的首屏再简化一些，去掉多余装饰，只保留标题、说明和一个主按钮`,
    messageType: isAi ? 'ai' : 'user',
    appId: app?.id ?? '9001',
    userId: i % 3 === 0 ? '1001' : `20${String(10 + (i % 10)).slice(-2)}`,
    createTime: `2026-07-${String(28 - Math.floor(i / 3)).padStart(2, '0')} ${String(9 + (i % 10)).padStart(2, '0')}:${String((i * 7) % 60).padStart(2, '0')}:00`,
  }
})

/** 后台：设计指纹 */
const TONES = [
  '克制、留白、工程感',
  '温柔、奶油色、圆润',
  '力量、高对比、紧凑',
  '科技、冷色、网格',
  '复古、衬线、纸感',
]

export const FINGERPRINTS = Array.from({ length: 23 }, (_, i) => {
  const status = ['CANDIDATE', 'CANDIDATE', 'APPROVED', 'REJECTED', 'ARCHIVED'][i % 5]
  const profile = ['startup', 'enterprise', 'default'][i % 3]
  const confidence = Math.round((0.58 + ((i * 7) % 40) / 100) * 100) / 100
  return {
    id: `6${String(2000 + i)}`,
    styleProfile: profile,
    schemaVersion: 'v1.3',
    sourceType: i % 3 === 0 ? 'SELF_FED_FINGERPRINT' : 'EXTRACTED_FINGERPRINT',
    sourceId: `app_${8000 + (i % 9) + 1}`,
    status,
    signature: `sig_${(i * 8887).toString(16)}`,
    confidence,
    priority: i % 4,
    tone: TONES[i % TONES.length],
    reviewRemark:
      status === 'REJECTED'
        ? '色彩层级混乱，正文对比度不足'
        : status === 'ARCHIVED'
          ? '风格已被新版本覆盖'
          : '',
    fingerprintJson: JSON.stringify({
      layout: { grid: 12, maxWidth: 1200, rhythm: [4, 8, 12, 16, 24, 32, 48] },
      color: {
        background: '#fafafa',
        surface: '#ffffff',
        text: ['#0f0f0e', '#5f5f5a', '#82827c'],
        accent: '#1a1a18',
        spark: '#e0603c',
      },
      typography: {
        family: 'Inter, PingFang SC',
        display: { size: 'clamp(40px,6vw,68px)', weight: 600, tracking: '-0.03em' },
        body: { size: 15, lineHeight: 1.6 },
      },
      shape: { radius: [6, 8, 10, 14, 18, 24], border: '1px solid #e0e0dc' },
      depth: { shadow: ['0 1px 2px rgba(26,26,24,.05)', '0 18px 40px -12px rgba(26,26,24,.14)'] },
      motion: { easing: 'cubic-bezier(.22,1,.36,1)', duration: [140, 220, 360] },
    }),
    createTime: `2026-07-${String(26 - Math.floor(i / 2)).padStart(2, '0')} 1${i % 10}:0${i % 6}:00`,
    updateTime: `2026-07-${String(27 - Math.floor(i / 2)).padStart(2, '0')} 1${i % 10}:3${i % 6}:00`,
  }
})

/** 预览 iframe 的演示页面 */
export function demoPreviewHtml(title: string) {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>${title}</title>
<style>
  :root { --ink:#1a1a18; --muted:#5f5f5a; --line:#e6e6e2; --bg:#fbfbfa; }
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--ink);
    font-family:Inter,-apple-system,'PingFang SC',sans-serif;-webkit-font-smoothing:antialiased}
  header{position:sticky;top:0;display:flex;align-items:center;gap:24px;height:56px;padding:0 32px;
    background:rgba(255,255,255,.82);backdrop-filter:blur(12px);border-bottom:1px solid var(--line)}
  .logo{display:flex;align-items:center;gap:8px;font-weight:600;letter-spacing:-.02em}
  .logo i{width:22px;height:22px;border-radius:6px;background:var(--ink)}
  nav{display:flex;gap:18px;font-size:13px;color:var(--muted)}
  .btn{margin-left:auto;height:32px;padding:0 14px;display:inline-flex;align-items:center;
    border-radius:999px;background:var(--ink);color:#fff;font-size:13px}
  main{max-width:920px;margin:0 auto;padding:72px 32px 96px}
  h1{font-size:clamp(34px,5vw,54px);line-height:1.08;letter-spacing:-.03em;margin:0}
  h1 span{color:#a6a6a0}
  p.lead{margin:20px 0 0;font-size:17px;line-height:1.65;color:var(--muted);max-width:520px}
  .cta{display:flex;gap:10px;margin-top:32px}
  .cta a{height:42px;padding:0 20px;display:inline-flex;align-items:center;border-radius:12px;
    font-size:14px;text-decoration:none}
  .cta a.primary{background:var(--ink);color:#fff}
  .cta a.ghost{border:1px solid var(--line);color:var(--ink);background:#fff}
  .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-top:64px}
  .card{background:#fff;border:1px solid var(--line);border-radius:16px;padding:20px}
  .card h3{margin:0 0 6px;font-size:15px}
  .card p{margin:0;font-size:13px;line-height:1.6;color:var(--muted)}
  .note{margin-top:64px;padding-top:24px;border-top:1px solid var(--line);font-size:12px;color:#a6a6a0}
</style>
</head>
<body>
  <header>
    <div class="logo"><i></i>${title}</div>
    <nav><span>首页</span><span>产品</span><span>价格</span><span>关于</span></nav>
    <span class="btn">立即体验</span>
  </header>
  <main>
    <h1>把想法做成产品，<br /><span>今天就能上线。</span></h1>
    <p class="lead">这是离线演示模式下的示例预览页面，用于展示对话页右侧的实时预览区域效果。</p>
    <div class="cta"><a class="primary" href="#">开始使用</a><a class="ghost" href="#">查看文档</a></div>
    <div class="grid">
      <div class="card"><h3>结构清晰</h3><p>12 栅格布局，统一的间距节奏与圆角层级。</p></div>
      <div class="card"><h3>阅读优先</h3><p>正文 15px / 1.6 行高，长文本也不易疲劳。</p></div>
      <div class="card"><h3>响应式</h3><p>768px 断点下自动收起为单列布局。</p></div>
    </div>
    <p class="note">Demo preview ·由 CreatAi 离线演示 mock 生成</p>
  </main>
</body>
</html>`
}
