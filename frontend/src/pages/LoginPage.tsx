import { useState, type FormEvent } from 'react'
import { ArrowRight, Building2, Eye, EyeOff, ShieldCheck, Sparkles } from 'lucide-react'
import { Navigate, useNavigate } from 'react-router-dom'
import { api, authStore } from '../api/client'
import { InlineError } from '../components/ui'

export default function LoginPage() {
  const navigate = useNavigate()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  if (authStore.get()) return <Navigate to="/monitoring" replace />

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    setLoading(true)
    try {
      const result = await api.login(loginId, password)
      authStore.set(result.accessToken)
      navigate('/monitoring', { replace: true })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '로그인 정보를 다시 확인해 주세요.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="login-page">
      <section className="login-story">
        <div className="brand brand--light"><span className="brand__mark"><ShieldCheck size={20} /></span>GovInsight</div>
        <div className="login-story__copy">
          <span className="eyebrow eyebrow--light">PUBLIC SIGNAL INTELLIGENCE</span>
          <h1>흩어진 공공기관 공고를<br />한곳에서 확인하세요.</h1>
          <p>변화를 놓치지 않도록 수집부터 분석까지 이어집니다.</p>
          <div className="login-preview">
            <div className="login-preview__header"><div><span className="live-dot" />오늘의 모니터링</div><small>방금 업데이트됨</small></div>
            <div className="login-preview__metrics"><div><small>확인 기관</small><strong>5</strong></div><div><small>감지 문서</small><strong>12</strong></div><div><small>중요 문서</small><strong className="accent">2</strong></div></div>
            <div className="login-preview__signals">
              <div><span className="preview-icon"><Building2 size={15} /></span><span><b>과학기술정보통신부</b><small>AI 공동활용 데이터 지원사업 공고</small></span><em>중요</em></div>
              <div><span className="preview-icon"><Building2 size={15} /></span><span><b>산업통상부</b><small>국가첨단전략산업 지원사업 수정</small></span><em className="updated">수정</em></div>
            </div>
            <div className="login-preview__insight"><Sparkles size={15} /><span><b>회사 맞춤 분석 완료</b><small>지원 가능성과 대응 방향을 확인할 수 있어요.</small></span><ArrowRight size={15} /></div>
          </div>
        </div>
        <p className="login-story__footer">GovInsight · For better decisions</p>
      </section>
      <section className="login-form-wrap">
        <form className="login-card" onSubmit={submit}>
          <div className="login-card__header"><span className="mobile-logo"><ShieldCheck size={20} /></span><h2>관리자 로그인</h2><p>안전한 운영을 위해 관리자 계정으로 로그인해 주세요.</p></div>
          <label className="field"><span>아이디</span><input autoFocus autoComplete="username" value={loginId} onChange={(e) => setLoginId(e.target.value)} placeholder="관리자 아이디" required /></label>
          <label className="field"><span>비밀번호</span><div className="input-with-action"><input type={showPassword ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="비밀번호" required /><button type="button" onClick={() => setShowPassword((value) => !value)} aria-label="비밀번호 표시 전환">{showPassword ? <EyeOff size={18} /> : <Eye size={18} />}</button></div></label>
          {error && <InlineError message={error} />}
          <button className="button button--primary button--large" disabled={loading}>{loading ? '확인하고 있어요...' : <>로그인 <ArrowRight size={18} /></>}</button>
          <p className="login-help">계정에 문제가 있다면 시스템 담당자에게 문의해 주세요.</p>
        </form>
      </section>
    </main>
  )
}
