import type { ReactNode } from 'react'
import { AlertCircle, CheckCircle2, LoaderCircle, X } from 'lucide-react'

export function Badge({ tone = 'neutral', children }: { tone?: string; children: ReactNode }) {
  return <span className={`badge badge--${tone}`}>{children}</span>
}

export function EmptyState({ icon, title, description }: { icon: ReactNode; title: string; description: string }) {
  return (
    <div className="empty-state">
      <div className="empty-state__icon">{icon}</div>
      <strong>{title}</strong>
      <p>{description}</p>
    </div>
  )
}

export function Loading({ label = '불러오는 중입니다' }: { label?: string }) {
  return <div className="loading"><LoaderCircle size={18} className="spin" />{label}</div>
}

export function InlineError({ message }: { message: string }) {
  return <div className="inline-message inline-message--error"><AlertCircle size={17} />{message}</div>
}

export function Toast({ message, onClose }: { message: string; onClose: () => void }) {
  return (
    <div className="toast"><CheckCircle2 size={18} /><span>{message}</span><button onClick={onClose} aria-label="알림 닫기"><X size={16} /></button></div>
  )
}

export function Pagination({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (page: number) => void }) {
  if (totalPages <= 1) return null
  return (
    <div className="pagination">
      <button className="button button--subtle" disabled={page === 0} onClick={() => onChange(page - 1)}>이전</button>
      <span><b>{page + 1}</b> / {totalPages}</span>
      <button className="button button--subtle" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>다음</button>
    </div>
  )
}
