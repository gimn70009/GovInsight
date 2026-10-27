import { useState, type FormEvent } from 'react'
import { ArrowRight, Eye, EyeOff } from 'lucide-react'
import { Navigate, useNavigate } from 'react-router-dom'
import { api, authStore } from '../api/client'
import { InlineError } from '../components/ui'
import Brand from '../components/Brand'

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
      <section className="login-story" aria-labelledby="login-story-title">
        <Brand className="brand--hero" />
        <div className="login-brand-pattern" aria-hidden="true">
          <svg viewBox="0 0 760 560" preserveAspectRatio="xMidYMid slice" fill="none" focusable="false">
            <defs>
              <linearGradient id="login-brand-line" x1="120" y1="40" x2="650" y2="540" gradientUnits="userSpaceOnUse">
                <stop stopColor="#3182f6" stopOpacity=".16" />
                <stop offset=".5" stopColor="#3182f6" stopOpacity=".7" />
                <stop offset="1" stopColor="#3182f6" stopOpacity=".25" />
              </linearGradient>
              <linearGradient id="login-brand-fill" x1="240" y1="120" x2="520" y2="440" gradientUnits="userSpaceOnUse">
                <stop stopColor="#3182f6" stopOpacity=".02" />
                <stop offset="1" stopColor="#3182f6" stopOpacity=".13" />
              </linearGradient>
            </defs>
            <g transform="rotate(-28 380 280)" stroke="url(#login-brand-line)" strokeWidth="1.2">
              <rect x="30" y="-70" width="700" height="700" rx="180" opacity=".25" />
              <rect x="70" y="-30" width="620" height="620" rx="160" opacity=".35" />
              <rect x="110" y="10" width="540" height="540" rx="140" opacity=".45" />
              <rect x="150" y="50" width="460" height="460" rx="120" opacity=".6" />
              <rect x="190" y="90" width="380" height="380" rx="100" fill="url(#login-brand-fill)" opacity=".8" />
              <rect x="230" y="130" width="300" height="300" rx="80" fill="url(#login-brand-fill)" />
              <rect x="270" y="170" width="220" height="220" rx="60" fill="url(#login-brand-fill)" />
            </g>
            <rect x="322" y="222" width="116" height="116" rx="32" transform="rotate(-28 380 280)" fill="#3182f6" fillOpacity=".11" stroke="#3182f6" strokeOpacity=".5" />
            <rect x="101" y="342" width="30" height="30" rx="9" transform="rotate(-28 116 357)" fill="#3182f6" fillOpacity=".035" stroke="#3182f6" strokeOpacity=".35" />
            <rect x="639" y="117" width="22" height="22" rx="6" transform="rotate(-28 650 128)" fill="#3182f6" fillOpacity=".24" />
            <path d="M148 128h22m-11-11v22M598 438h18m-9-9v18" stroke="#3182f6" strokeOpacity=".3" strokeLinecap="round" />
          </svg>
        </div>
        <div className="login-story__copy">
          <h1 id="login-story-title">흩어진 공공기관 공고를<br /><span>우리 회사의 기회로.</span></h1>
          <p className="login-story__caption">공고 수집부터 변화 감지, 맞춤 분석까지 한곳에서.</p>
        </div>
      </section>
      <section className="login-form-wrap">
        <form className="login-card" onSubmit={submit}>
          <div className="login-card__header"><Brand className="brand--mobile mobile-logo" /><h2>관리자 로그인</h2><p>안전한 운영을 위해 관리자 계정으로 로그인해 주세요.</p></div>
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
