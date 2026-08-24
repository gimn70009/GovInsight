import { FileSearch2, LogOut, Radar, ShieldCheck } from 'lucide-react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { authStore } from '../api/client'

export default function AppShell() {
  const navigate = useNavigate()
  const logout = () => {
    authStore.clear()
    navigate('/login', { replace: true })
  }
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand__mark"><ShieldCheck size={19} /></span><span>GovInsight</span></div>
        <nav className="side-nav" aria-label="주요 메뉴">
          <NavLink to="/monitoring"><Radar size={18} /><span>모니터링</span></NavLink>
          <NavLink to="/documents"><FileSearch2 size={18} /><span>감지된 게시글</span></NavLink>
        </nav>
        <div className="sidebar__footer">
          <div className="admin-profile"><span className="avatar">A</span><div><strong>관리자</strong><small>운영 계정</small></div></div>
          <button className="icon-button" onClick={logout} title="로그아웃"><LogOut size={18} /></button>
        </div>
      </aside>
      <main className="main-content"><Outlet /></main>
    </div>
  )
}
