import React, { useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

const labels = {
  SUBMITTED: '待领取', IN_REVIEW: '处理中', APPROVED: '已批准',
  REJECTED: '已拒绝', CLOSED: '已关闭',
  PENDING: '等待分析', RUNNING: '分析中', DONE: '分析完成', FAILED: '分析失败',
  RETURN: '退货退款', REFUND: '仅退款', LOGISTICS: '物流异常',
  DELIVERED: '已签收', IN_TRANSIT: '运输中',
}

function App() {
  const [auth, setAuth] = useState(sessionStorage.getItem('demo-auth') || '')
  const [loginName, setLoginName] = useState('customer')
  const [loginPassword, setLoginPassword] = useState('customer123')
  const [me, setMe] = useState(null)
  const [orders, setOrders] = useState([])
  const [cases, setCases] = useState([])
  const [selected, setSelected] = useState(null)
  const [selectedId, setSelectedId] = useState(null)
  const [type, setType] = useState('RETURN')
  const [orderId, setOrderId] = useState('')
  const [reason, setReason] = useState('耳机签收后发现破损，右耳无法正常使用。')
  const [note, setNote] = useState('已核对订单及售后规则。')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function api(path, options = {}, token = auth) {
    const response = await fetch(path, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Basic ${token}`,
        ...(options.headers || {}),
      },
    })
    const body = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(body.error || `请求失败（${response.status}）`)
    return body
  }

  async function refresh(token = auth, id = selectedId) {
    const [profile, orderList, caseList] = await Promise.all([
      api('/api/me', {}, token), api('/api/orders', {}, token), api('/api/cases', {}, token),
    ])
    setMe(profile)
    setOrders(orderList)
    setCases(caseList)
    if (!orderId && orderList.length) {
      const suggestedOrder = orderList.find(order => order.logisticsStatus === 'DELIVERED') || orderList[0]
      setOrderId(String(suggestedOrder.id))
    }
    if (id) {
      try { setSelected(await api(`/api/cases/${id}`, {}, token)) }
      catch { setSelected(null); setSelectedId(null) }
    }
  }

  useEffect(() => {
    if (!auth) return
    refresh().catch(() => { setAuth(''); sessionStorage.removeItem('demo-auth') })
  }, [auth])

  useEffect(() => {
    if (!auth || !selectedId) return
    const timer = setInterval(() => refresh(auth, selectedId).catch(() => {}), 5000)
    return () => clearInterval(timer)
  }, [auth, selectedId])

  async function login(event) {
    event.preventDefault()
    setError('')
    const token = btoa(`${loginName}:${loginPassword}`)
    try {
      await api('/api/me', {}, token)
      sessionStorage.setItem('demo-auth', token)
      setAuth(token)
      setSelected(null)
      setSelectedId(null)
    } catch (err) { setError(err.message) }
  }

  async function action(path, body) {
    setBusy(true); setError('')
    try {
      const detail = await api(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined })
      setSelected(detail)
      setSelectedId(detail.ticket.id)
      await refresh(auth, detail.ticket.id)
    } catch (err) { setError(err.message) }
    finally { setBusy(false) }
  }

  async function createCase(event) {
    event.preventDefault()
    if (!orderId) return setError('请选择订单')
    await action('/api/cases', {
      orderId: Number(orderId), caseType: type, reason,
      idempotencyKey: crypto.randomUUID(),
    })
  }

  async function openCase(id) {
    setError('')
    try { setSelected(await api(`/api/cases/${id}`)); setSelectedId(id) }
    catch (err) { setError(err.message) }
  }

  if (!auth || !me) return <div className="login-shell">
    <div className="login-card">
      <div className="brand-mark">A</div>
      <p className="eyebrow">AFTERCARE · DEMO</p>
      <h1>售后异常处理台</h1>
      <p className="muted">从订单到处理决定，让每一步都有记录。</p>
      <form onSubmit={login}>
        <label>体验身份</label>
        <select value={loginName} onChange={e => {
          setLoginName(e.target.value)
          setLoginPassword(e.target.value === 'agent' ? 'agent123' : 'customer123')
        }}>
          <option value="customer">顾客 · 提交申请</option>
          <option value="agent">客服 · 处理申请</option>
        </select>
        <label>用户名</label>
        <input value={loginName} onChange={e => setLoginName(e.target.value)} />
        <label>密码</label>
        <input type="password" value={loginPassword} onChange={e => setLoginPassword(e.target.value)} />
        {error && <p className="error">{error}</p>}
        <button className="primary" type="submit">进入工作台</button>
      </form>
      <p className="hint">默认账号仅用于本地演示，可在部署时通过环境变量修改。</p>
    </div>
  </div>

  const agent = me.role === 'AGENT'
  return <div className="app-shell">
    <aside className="sidebar">
      <div className="sidebar-brand"><span className="brand-mark small">A</span><span>Aftercare<br /><strong>工作台</strong></span></div>
      <div className="sidebar-group">工作空间</div>
      <div className="nav-active">◉ <span>售后工单</span></div>
      <div className="sidebar-bottom"><div className="avatar">{me.username[0].toUpperCase()}</div><div><strong>{me.username}</strong><small>{agent ? '客服' : '顾客'}</small></div><button className="text-btn" onClick={() => { sessionStorage.removeItem('demo-auth'); setAuth(''); setMe(null) }}>退出</button></div>
    </aside>

    <main className="content">
      <header className="topbar"><div><p className="eyebrow">CASE MANAGEMENT</p><h1>{agent ? '售后处理中心' : '我的售后申请'}</h1></div><span className="live-dot">系统运行中</span></header>
      {error && <div className="error-banner">{error}<button onClick={() => setError('')}>×</button></div>}
      <div className="stats">
        <div className="stat"><span>全部工单</span><strong>{cases.length}</strong><small>当前可见</small></div>
        <div className="stat"><span>待处理</span><strong>{cases.filter(c => ['SUBMITTED', 'IN_REVIEW'].includes(c.status)).length}</strong><small>需要跟进</small></div>
        <div className="stat"><span>已完成</span><strong>{cases.filter(c => ['APPROVED', 'REJECTED', 'CLOSED'].includes(c.status)).length}</strong><small>有处理结论</small></div>
      </div>

      <div className="workspace">
        <section className="list-panel">
          <div className="panel-heading"><div><h2>工单列表</h2><p>选择一条工单查看处理过程</p></div><button className="ghost" onClick={() => refresh().catch(err => setError(err.message))}>刷新</button></div>
          {cases.length === 0 && <div className="empty">还没有售后工单。{agent ? '等待顾客提交申请。' : '在下方创建第一条申请。'}</div>}
          {cases.map(ticket => <button key={ticket.id} className={`case-row ${selectedId === ticket.id ? 'selected' : ''}`} onClick={() => openCase(ticket.id)}>
            <div className="case-row-top"><strong>#{ticket.id} · {labels[ticket.caseType]}</strong><span className={`badge ${ticket.status.toLowerCase()}`}>{labels[ticket.status]}</span></div>
            <p>{ticket.reason}</p><small>{new Date(ticket.createdAt).toLocaleString('zh-CN')}</small>
          </button>)}
        </section>

        <section className="detail-panel">
          {!selected && <div className="detail-placeholder"><div className="placeholder-icon">◎</div><h2>选择一条工单</h2><p>订单信息、AI 建议和处理记录会显示在这里。</p></div>}
          {selected && <>
            <div className="panel-heading"><div><p className="eyebrow">CASE #{selected.ticket.id}</p><h2>{labels[selected.ticket.caseType]}</h2></div><span className={`badge ${selected.ticket.status.toLowerCase()}`}>{labels[selected.ticket.status]}</span></div>
            <div className="info-grid"><div><span>订单商品</span><strong>{selected.order.itemName}</strong></div><div><span>订单金额</span><strong>¥{(selected.order.amountCents / 100).toFixed(2)}</strong></div><div><span>物流状态</span><strong>{labels[selected.order.logisticsStatus]}</strong></div><div><span>工单版本</span><strong>v{selected.ticket.version}</strong></div></div>
            <div className="section"><h3>问题描述</h3><p>{selected.ticket.reason}</p></div>
            <div className="section ai-section"><div className="ai-heading"><span className="ai-icon">✦</span><h3>AI 辅助分析</h3><span className="badge neutral">{labels[selected.analysisTask?.status] || '未启动'}</span></div>
              {selected.analysis ? <><strong>{selected.analysis.category}</strong><p>{selected.analysis.recommendation}</p><details><summary>查看查询依据</summary><pre>{JSON.stringify(JSON.parse(selected.analysis.evidenceJson), null, 2)}</pre></details><small>分析来源：{selected.analysis.modelName}</small></>
                : <p className="muted">{selected.analysisTask?.lastError || '分析任务正在排队，页面会自动更新。'}</p>}
              {agent && selected.analysisTask?.status === 'FAILED' && <button className="ghost" disabled={busy} onClick={() => action(`/api/cases/${selected.ticket.id}/analysis/retry`)}>重试分析</button>}
            </div>
            {agent && <div className="section"><h3>客服操作</h3>
              {selected.ticket.status === 'SUBMITTED' && <button className="primary" disabled={busy} onClick={() => action(`/api/cases/${selected.ticket.id}/take`)}>领取工单</button>}
              {selected.ticket.status === 'IN_REVIEW' && <><textarea value={note} onChange={e => setNote(e.target.value)} placeholder="填写处理理由" /><div className="button-row"><button className="primary" disabled={busy} onClick={() => action(`/api/cases/${selected.ticket.id}/decision`, { decision: 'APPROVED', note, expectedVersion: selected.ticket.version })}>批准申请</button><button className="danger" disabled={busy} onClick={() => action(`/api/cases/${selected.ticket.id}/decision`, { decision: 'REJECTED', note, expectedVersion: selected.ticket.version })}>拒绝申请</button></div></>}
              {['APPROVED', 'REJECTED'].includes(selected.ticket.status) && <button className="ghost" disabled={busy} onClick={() => action(`/api/cases/${selected.ticket.id}/close`)}>关闭工单</button>}
              {selected.ticket.status === 'CLOSED' && <p className="muted">此工单已经结束。</p>}
            </div>}
            <div className="section"><h3>处理记录</h3><div className="timeline">{selected.events.map(event => <div key={event.id} className="timeline-item"><strong>{labels[event.toStatus] || event.eventType}</strong><small>{new Date(event.createdAt).toLocaleString('zh-CN')}</small><p>{event.note}</p></div>)}</div></div>
          </>}
        </section>
      </div>

      {!agent && <section className="create-panel"><div><p className="eyebrow">NEW REQUEST</p><h2>创建售后申请</h2><p className="muted">选择订单并描述问题，系统会自动安排辅助分析。</p></div><form onSubmit={createCase}><div className="form-row"><div><label>订单</label><select value={orderId} onChange={e => setOrderId(e.target.value)}>{orders.map(order => <option value={order.id} key={order.id}>#{order.id} · {order.itemName} · {labels[order.logisticsStatus]}</option>)}</select></div><div><label>申请类型</label><select value={type} onChange={e => setType(e.target.value)}><option value="RETURN">退货退款</option><option value="REFUND">仅退款</option><option value="LOGISTICS">物流异常</option></select></div></div><label>问题描述</label><textarea value={reason} onChange={e => setReason(e.target.value)} maxLength={1000} required /><button className="primary" disabled={busy} type="submit">提交申请</button></form></section>}
    </main>
  </div>
}

createRoot(document.getElementById('root')).render(<App />)
