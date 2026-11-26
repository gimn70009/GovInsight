import { FileSearch, LoaderCircle } from 'lucide-react'
import './SectionStatus.css'

type SectionStatusProps = {
  state: 'loading' | 'empty' | 'unavailable'
  title: string
  description: string
  compact?: boolean
}

export function SectionStatus({ state, title, description, compact = false }: SectionStatusProps) {
  const loading = state === 'loading'
  return <div className={`section-status section-status--${state}${compact ? ' section-status--compact' : ''}`}
    role={loading ? 'status' : undefined} aria-live={loading ? 'polite' : undefined} aria-atomic={loading ? true : undefined}>
    <span className="section-status__icon" aria-hidden="true">
      {loading ? <LoaderCircle className="section-status__spinner" size={22} /> : <FileSearch size={22} />}
    </span>
    <div className="section-status__content">
      <strong className="section-status__title">{title}</strong>
      <p>{description}</p>
    </div>
  </div>
}
