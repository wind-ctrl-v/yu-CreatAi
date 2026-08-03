/**
 * 离线演示 Mock 插件（仅开发服务器生效）
 *
 * 用途：在不启动后端的情况下预览完整前端页面。
 * 启用方式：`npm run dev:mock`（等价于 VITE_MOCK=1 vite）
 *
 * 安全说明：
 * - `apply: 'serve'` 保证该插件永不进入生产构建产物；
 * - 仅在显式设置 VITE_MOCK=1 时装载，默认开发流程不受影响；
 * - 会话仅使用一个不含敏感信息的本地 Cookie 标记，不存在真实凭据。
 */
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { Plugin } from 'vite'
import {
  ADMIN_CHATS,
  DEMO_ACCOUNTS,
  EXTRA_USERS,
  FINGERPRINTS,
  GOOD_APPS,
  MY_APPS,
  chatHistoryOf,
  demoPreviewHtml,
  planOf,
  withUser,
  type MockApp,
  type MockUser,
} from './data'

const SESSION_COOKIE = 'creatai_mock_session'

/* ── 可变的内存数据（模拟增删改） ── */
const myApps: MockApp[] = [...MY_APPS]
const goodApps: MockApp[] = [...GOOD_APPS]
const allUsers = [...DEMO_ACCOUNTS, ...EXTRA_USERS]
const fingerprints = [...FINGERPRINTS]
let seq = 0

/* ── 工具 ── */

function json(res: ServerResponse, data: unknown, headers: Record<string, string> = {}) {
  const body = JSON.stringify(data)
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    ...headers,
  })
  res.end(body)
}

function ok<T>(res: ServerResponse, data: T, headers?: Record<string, string>) {
  json(res, { code: 0, data, message: 'ok' }, headers)
}

function fail(res: ServerResponse, code: number, message: string) {
  json(res, { code, data: null, message })
}

function readBody(req: IncomingMessage): Promise<Record<string, any>> {
  return new Promise((resolve) => {
    let raw = ''
    req.on('data', (chunk) => {
      raw += chunk
      if (raw.length > 1_000_000) raw = raw.slice(0, 1_000_000)
    })
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {})
      } catch {
        resolve({})
      }
    })
    req.on('error', () => resolve({}))
  })
}

function getCookie(req: IncomingMessage, name: string): string | undefined {
  const raw = req.headers.cookie
  if (!raw) return undefined
  for (const part of raw.split(';')) {
    const [k, ...v] = part.trim().split('=')
    if (k === name) return decodeURIComponent(v.join('='))
  }
  return undefined
}

function currentUser(req: IncomingMessage) {
  const account = getCookie(req, SESSION_COOKIE)
  if (!account) return null
  return DEMO_ACCOUNTS.find((u) => u.userAccount === account) ?? null
}

function publicUser(u: MockUser) {
  const { password, ...rest } = u
  void password
  return rest
}

function paginate<T>(list: T[], pageNum = 1, pageSize = 10) {
  const p = Math.max(1, Number(pageNum) || 1)
  const s = Math.max(1, Number(pageSize) || 10)
  const start = (p - 1) * s
  return {
    records: list.slice(start, start + s),
    pageNumber: p,
    pageSize: s,
    totalRow: list.length,
    totalPage: Math.max(1, Math.ceil(list.length / s)),
  }
}

function sseHead(res: ServerResponse) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  })
}

/** 按节奏推送若干帧，最后发送 done 事件 */
function streamFrames(
  res: ServerResponse,
  frames: string[],
  intervalMs = 90,
  eventName?: string,
) {
  let i = 0
  const timer = setInterval(() => {
    if (i >= frames.length) {
      clearInterval(timer)
      res.write('event: done\ndata: {}\n\n')
      res.end()
      return
    }
    const payload = JSON.stringify({ d: frames[i++] })
    if (eventName) res.write(`event: ${eventName}\n`)
    res.write(`data: ${payload}\n\n`)
  }, intervalMs)

  res.on('close', () => clearInterval(timer))
}

/** 把长文本切成流式片段 */
function chunkText(text: string, size = 14): string[] {
  const out: string[] = []
  for (let i = 0; i < text.length; i += size) out.push(text.slice(i, i + size))
  return out
}

function appById(id: string) {
  return [...myApps, ...goodApps].find((a) => a.id === id)
}

/* ── 生成流文案 ── */

function generationScript(prompt: string) {
  return [
    `正在理解需求：${prompt}\n\n`,
    '【规划】\n',
    '· 页面结构：顶部导航 / 首屏主张 / 能力说明 / 内容网格 / 页脚\n',
    '· 视觉基调：米白底 + 近黑文字，仅用一处暖橙作为点缀\n',
    '· 排版节奏：标题 clamp(40px,6vw,68px)，正文 15px / 1.6\n\n',
    '【生成中】\n',
    '· 写入 index.html —— 语义化结构与无障碍属性\n',
    '· 写入 styles.css —— 设计变量、栅格、断点\n',
    '· 写入 main.js —— 滚动显现与交互细节\n\n',
    '【完成】\n',
    '首版已生成，右侧可查看实时预览。你可以继续描述想调整的地方，例如：\n',
    '· 「主色换成墨绿，按钮改成方角」\n',
    '· 「首屏加一个预约表单」\n',
    '· 「信息密度再高一些，卡片改成四列」\n',
  ]
}

const EDIT_TOOL_SEQUENCE = [
  { type: 'ai_response', data: '收到，我先看一下当前项目结构。\n' },
  { type: 'tool_request', id: 't1', name: 'readDir', arguments: '{"path":"src"}' },
  { type: 'tool_executed', id: 't1', name: 'readDir', arguments: '{"path":"src"}', result: 'App.vue, main.ts, styles/global.css, components/Hero.vue' },
  { type: 'tool_request', id: 't2', name: 'readFile', arguments: '{"path":"src/styles/global.css"}' },
  { type: 'tool_executed', id: 't2', name: 'readFile', arguments: '{"path":"src/styles/global.css"}', result: '读取 128 行，定位到 :root 设计变量区块' },
  { type: 'ai_response', data: '已定位到设计变量，开始调整。\n' },
  { type: 'tool_request', id: 't3', name: 'modifyFile', arguments: '{"path":"src/styles/global.css"}' },
  { type: 'tool_executed', id: 't3', name: 'modifyFile', arguments: '{"path":"src/styles/global.css"}', result: '更新 --accent 与 --r-lg，共 6 处变更' },
  { type: 'tool_request', id: 't4', name: 'modifyFile', arguments: '{"path":"src/components/Hero.vue"}' },
  { type: 'tool_executed', id: 't4', name: 'modifyFile', arguments: '{"path":"src/components/Hero.vue"}', result: '标题字号与字距已调整，新增毛玻璃导航栏' },
]

/* ── 插件 ── */

export function devMockPlugin(): Plugin {
  return {
    name: 'creatai-dev-mock',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const rawUrl = req.url || ''
        if (!rawUrl.startsWith('/api')) return next()

        const url = new URL(rawUrl, 'http://localhost')
        const path = url.pathname.replace(/^\/api/, '') || '/'
        const method = (req.method || 'GET').toUpperCase()
        const query = url.searchParams
        const body = method === 'POST' ? await readBody(req) : {}
        const me = currentUser(req)

        /* ── 静态预览页面 ── */
        if (path.startsWith('/static/')) {
          const slug = path.replace('/static/', '').replace(/\/$/, '')
          const app = appById(slug.split('_').pop() || '')
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
          res.end(demoPreviewHtml(app?.appName || '演示页面'))
          return
        }

        /* ── 下载 ── */
        if (path.startsWith('/app/download/')) {
          res.writeHead(200, {
            'Content-Type': 'text/plain; charset=utf-8',
            'Content-Disposition': 'attachment; filename="demo-source.txt"',
          })
          res.end('离线演示模式下不提供真实源码包。\n')
          return
        }

        /* ── 用户与会话 ── */
        if (path === '/user/login' && method === 'POST') {
          const account = String(body.userAccount ?? '')
          const password = String(body.userPassword ?? '')
          const hit = DEMO_ACCOUNTS.find(
            (u) => u.userAccount === account && u.password === password,
          )
          if (!hit) {
            return fail(res, 40000, '账号或密码错误（演示账号：demo / demo123456，admin / admin123456）')
          }
          return ok(res, publicUser(hit), {
            'Set-Cookie': `${SESSION_COOKIE}=${encodeURIComponent(hit.userAccount)}; Path=/; SameSite=Lax; Max-Age=86400`,
          })
        }

        if (path === '/user/logout' && method === 'POST') {
          return ok(res, true, {
            'Set-Cookie': `${SESSION_COOKIE}=; Path=/; SameSite=Lax; Max-Age=0`,
          })
        }

        if (path === '/user/register' && method === 'POST') {
          if (!body.userAccount || !body.userPassword) return fail(res, 40000, '请填写完整信息')
          if (body.userPassword !== body.checkPassword) return fail(res, 40000, '两次输入的密码不一致')
          return ok(res, '3001')
        }

        if (path === '/user/get/login') {
          if (!me) return fail(res, 40100, '未登录')
          return ok(res, publicUser(me))
        }

        if (path === '/user/plan') {
          if (!me) return fail(res, 40100, '未登录')
          return ok(res, planOf(me.planType))
        }

        if (path === '/user/update/me' && method === 'POST') {
          if (!me) return fail(res, 40100, '未登录')
          if (typeof body.userName === 'string') me.userName = body.userName
          if (typeof body.userProfile === 'string') me.userProfile = body.userProfile
          return ok(res, true)
        }

        /* ── 应用 ── */
        if (path === '/app/my/list/page/vo' && method === 'POST') {
          if (!me) return fail(res, 40100, '未登录')
          let list = myApps
          if (body.appName) list = list.filter((a) => a.appName.includes(String(body.appName)))
          if (body.codeGenType) list = list.filter((a) => a.codeGenType === body.codeGenType)
          const page = paginate(list, body.pageNum, body.pageSize)
          return ok(res, { ...page, records: page.records.map(withUser) })
        }

        if (path === '/app/good/list/page/vo' && method === 'POST') {
          const page = paginate(goodApps, body.pageNum, body.pageSize)
          return ok(res, { ...page, records: page.records.map(withUser) })
        }

        if (path === '/app/admin/list/page/vo' && method === 'POST') {
          let list = [...myApps, ...goodApps]
          if (body.appName) list = list.filter((a) => a.appName.includes(String(body.appName)))
          if (body.codeGenType) list = list.filter((a) => a.codeGenType === body.codeGenType)
          if (body.userId) list = list.filter((a) => a.userId === String(body.userId))
          const page = paginate(list, body.pageNum, body.pageSize)
          return ok(res, { ...page, records: page.records.map(withUser) })
        }

        if (path === '/app/get/vo' || path === '/app/admin/get/vo') {
          const app = appById(String(query.get('id') ?? ''))
          if (!app) return fail(res, 40400, '应用不存在')
          return ok(res, withUser(app))
        }

        if (path === '/app/add' && method === 'POST') {
          if (!me) return fail(res, 40100, '未登录')
          const prompt = String(body.initPrompt ?? '新的应用')
          const id = String(9500 + ++seq)
          const now = new Date().toISOString().slice(0, 19).replace('T', ' ')
          myApps.unshift({
            id,
            appName: prompt.slice(0, 12) || '未命名应用',
            initPrompt: prompt,
            codeGenType: 'vue_project',
            priority: 0,
            userId: me.id,
            createTime: now,
            updateTime: now,
          })
          return ok(res, id)
        }

        if ((path === '/app/update' || path === '/app/admin/update') && method === 'POST') {
          const app = appById(String(body.id ?? ''))
          if (!app) return fail(res, 40400, '应用不存在')
          if (body.appName) app.appName = String(body.appName)
          if (body.priority !== undefined) app.priority = Number(body.priority) || 0
          return ok(res, true)
        }

        if ((path === '/app/delete' || path === '/app/admin/delete') && method === 'POST') {
          const id = String(body.id ?? '')
          for (const list of [myApps, goodApps]) {
            const idx = list.findIndex((a) => a.id === id)
            if (idx > -1) list.splice(idx, 1)
          }
          return ok(res, true)
        }

        if (path === '/app/deploy' && method === 'POST') {
          const id = String(body.appId ?? '')
          const app = appById(id)
          if (app) {
            app.deployKey = `demo-${id}`
            app.deployedTime = new Date().toISOString().slice(0, 19).replace('T', ' ')
          }
          const origin = `http://${req.headers.host ?? 'localhost:5173'}`
          return ok(res, `${origin}/api/static/${app?.codeGenType ?? 'vue_project'}_${id}/`)
        }

        /* ── 对话历史 ── */
        if (path.startsWith('/chatHistory/app/')) {
          const appId = path.replace('/chatHistory/app/', '')
          const list = [...chatHistoryOf(appId)].reverse()
          return ok(res, paginate(list, 1, Number(query.get('pageSize')) || 20))
        }

        if (path === '/chatHistory/admin/list/page/vo' && method === 'POST') {
          let list = ADMIN_CHATS
          if (body.appId) list = list.filter((c) => c.appId === String(body.appId))
          if (body.userId) list = list.filter((c) => c.userId === String(body.userId))
          if (body.messageType) list = list.filter((c) => c.messageType === body.messageType)
          return ok(res, paginate(list, body.pageNum, body.pageSize))
        }

        /* ── 后台用户管理 ── */
        if (path === '/user/list/page/vo' && method === 'POST') {
          let list = allUsers.map(publicUser)
          if (body.userAccount) list = list.filter((u) => u.userAccount.includes(String(body.userAccount)))
          if (body.userName) list = list.filter((u) => (u.userName ?? '').includes(String(body.userName)))
          if (body.userRole) list = list.filter((u) => u.userRole === body.userRole)
          return ok(res, paginate(list, body.pageNum, body.pageSize))
        }

        if (path === '/user/add' && method === 'POST') {
          if (!body.userAccount) return fail(res, 40000, '账号不能为空')
          allUsers.push({
            id: String(4000 + ++seq),
            userAccount: String(body.userAccount),
            password: '-',
            userName: String(body.userName ?? body.userAccount),
            userAvatar: body.userAvatar ? String(body.userAvatar) : undefined,
            userProfile: String(body.userProfile ?? ''),
            userRole: body.userRole === 'admin' ? 'admin' : 'user',
            planType: String(body.planType ?? 'FREE'),
            createTime: new Date().toISOString().slice(0, 19).replace('T', ' '),
          })
          return ok(res, 'created')
        }

        if (path === '/user/update' && method === 'POST') {
          const target = allUsers.find((u) => u.id === String(body.id))
          if (!target) return fail(res, 40400, '用户不存在')
          if (body.userName !== undefined) target.userName = String(body.userName)
          if (body.userProfile !== undefined) target.userProfile = String(body.userProfile)
          if (body.userAvatar !== undefined) target.userAvatar = String(body.userAvatar)
          if (body.userRole) target.userRole = body.userRole === 'admin' ? 'admin' : 'user'
          if (body.planType) target.planType = String(body.planType)
          return ok(res, true)
        }

        if (path === '/user/delete' && method === 'POST') {
          const idx = allUsers.findIndex((u) => u.id === String(body.id))
          if (idx > -1) allUsers.splice(idx, 1)
          return ok(res, true)
        }

        /* ── 指纹审核 ── */
        if (path === '/fingerprint/admin/list/page' && method === 'POST') {
          let list = fingerprints
          if (body.status) list = list.filter((f) => f.status === body.status)
          if (body.styleProfile) list = list.filter((f) => f.styleProfile === body.styleProfile)
          if (body.sourceType) list = list.filter((f) => f.sourceType === body.sourceType)
          if (body.tone) list = list.filter((f) => (f.tone ?? '').includes(String(body.tone)))
          return ok(res, paginate(list, body.pageNum, body.pageSize))
        }

        if (path === '/fingerprint/admin/get') {
          const f = fingerprints.find((x) => x.id === String(query.get('id')))
          if (!f) return fail(res, 40400, '指纹不存在')
          return ok(res, f)
        }

        if (path.startsWith('/fingerprint/admin/') && method === 'POST') {
          const action = path.split('/').pop()
          const f = fingerprints.find((x) => x.id === String(body.id))
          if (!f) return fail(res, 40400, '指纹不存在')
          if (action === 'approve') f.status = 'APPROVED'
          if (action === 'reject') f.status = 'REJECTED'
          if (action === 'archive') f.status = 'ARCHIVED'
          if (body.remark) f.reviewRemark = String(body.remark)
          return ok(res, true)
        }

        /* ── 偏好埋点 ── */
        if (path.startsWith('/preference/')) return ok(res, null)

        /* ── SSE：完整生成 ── */
        if (path === '/app/chat/gen/code') {
          if (!me) {
            sseHead(res)
            res.write(`event: business-error\ndata: ${JSON.stringify({ code: 40100, message: '未登录' })}\n\n`)
            res.end()
            return
          }
          const prompt = query.get('message') ?? '新的页面'
          const mode = query.get('mode') ?? 'standard'
          sseHead(res)
          const started = JSON.stringify({
            event: 'generation_started',
            generationId: `gen_${Date.now()}`,
            pipeline: 'V2',
            mode,
            fallbackEnabled: true,
          })
          res.write(`data: ${JSON.stringify({ d: `\n[生成] ${started}\n` })}\n\n`)
          const frames = generationScript(prompt).flatMap((seg) => chunkText(seg, 12))
          streamFrames(res, frames, 60)
          return
        }

        /* ── SSE：增量编辑 ── */
        if (path === '/app/incremental-edit-stream') {
          sseHead(res)
          const instruction = query.get('instruction') ?? ''
          const script = [
            ...EDIT_TOOL_SEQUENCE,
            {
              type: 'ai_response',
              data: `已按「${instruction.slice(0, 40)}」完成调整，右侧预览已刷新。\n`,
            },
          ]
          streamFrames(res, script.map((m) => JSON.stringify(m)), 320)
          return
        }

        /* ── 兜底 ── */
        return fail(res, 40400, `离线演示未实现的接口：${method} ${path}`)
      })

      const banner = [
        '',
        '  ┌────────────────────────────────────────────────┐',
        '  │  离线演示模式已启用（无需后端）                │',
        '  │  演示账号：demo  / demo123456   （普通用户）   │',
        '  │  管理账号：admin / admin123456  （管理后台）   │',
        '  └────────────────────────────────────────────────┘',
        '',
      ].join('\n')
      server.config.logger.info(banner)
    },
  }
}
