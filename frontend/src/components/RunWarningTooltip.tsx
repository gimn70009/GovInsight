import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { CircleHelp } from 'lucide-react'
import { api } from '../api/client'
import type { MonitoringRunWarnings, RunStatus } from '../api/types'
import { Badge } from './ui'
import './RunWarningTooltip.css'

export function RunWarningTooltip({ runId, warningCount, status }: {
  runId: number; warningCount: number; status: RunStatus
}) {
  const [open, setOpen] = useState(false)
  const [details, setDetails] = useState<MonitoringRunWarnings | null>(null)
  const [error, setError] = useState('')
  const [position, setPosition] = useState({ top: 0, left: 0 })
  const trigger = useRef<HTMLButtonElement>(null)
  const popup = useRef<HTMLDivElement>(null)
  const closeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const hovering = useRef(false)
  const id = useId()

  function reveal() { clearTimeout(closeTimer.current); setOpen(true) }
  function leave() {
    if (document.activeElement === trigger.current) return
    clearTimeout(closeTimer.current)
    closeTimer.current = setTimeout(() => setOpen(false), 150)
  }

  useEffect(() => () => clearTimeout(closeTimer.current), [])

  useEffect(() => {
    if (!open || warningCount === 0) return
    const controller = new AbortController()
    async function load() {
      setDetails(null); setError('')
      try {
        const response = await api.getRunWarnings(runId, controller.signal)
        if (!controller.signal.aborted) setDetails(response)
      } catch {
        if (!controller.signal.aborted) setError('경고 내용을 불러오지 못했어요. 다시 열면 재시도합니다.')
      }
    }
    void load()
    return () => controller.abort()
  }, [open, runId, warningCount, status])

  useLayoutEffect(() => {
    if (!open) return
    function place() {
      const anchor = trigger.current?.getBoundingClientRect()
      const box = popup.current?.getBoundingClientRect()
      if (!anchor || !box) return
      const below = anchor.bottom + 8
      const top = below + box.height <= window.innerHeight - 12 ? below : anchor.top - box.height - 8
      setPosition({
        top: Math.max(12, Math.min(top, window.innerHeight - box.height - 12)),
        left: Math.max(12, Math.min(anchor.right - box.width, window.innerWidth - box.width - 12)),
      })
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true) }
  }, [open, details, error])

  useEffect(() => {
    if (!open) return
    function outside(event: PointerEvent) {
      const target = event.target as Node
      if (!trigger.current?.contains(target) && !popup.current?.contains(target)) setOpen(false)
    }
    function escape(event: KeyboardEvent) { if (event.key === 'Escape') setOpen(false) }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('keydown', escape)
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape) }
  }, [open])

  if (warningCount === 0) return <span className="muted">없음</span>
  return <>
    <button ref={trigger} type="button" className="run-warning-trigger" aria-label={`경고 ${warningCount}건 상세 보기`}
      aria-expanded={open} aria-describedby={open ? id : undefined}
      onPointerEnter={(event) => { if (event.pointerType !== 'touch') { hovering.current = true; reveal() } }}
      onPointerLeave={() => { hovering.current = false; leave() }}
      onFocus={reveal} onBlur={() => { if (!hovering.current) leave() }} onClick={reveal}
      onKeyDown={(event) => {
        if (open && ['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp'].includes(event.key)) {
          event.preventDefault()
          popup.current?.scrollBy({ top: (event.key.endsWith('Down') ? 1 : -1) * (event.key.startsWith('Page') ? 200 : 48) })
        }
      }}>
      <Badge tone="warning">{warningCount}건<CircleHelp size={13} aria-hidden="true" /></Badge>
    </button>
    {open && createPortal(<div ref={popup} id={id} role="tooltip" className="run-warning-tooltip" style={position}
      onPointerEnter={() => clearTimeout(closeTimer.current)} onPointerLeave={leave}>
      <strong className="run-warning-tooltip__heading">경고 {details?.warningCount ?? warningCount}건</strong>
      {error ? <p>{error}</p> : !details ? <p role="status">경고 내용을 확인하고 있어요.</p>
        : details.warnings.length === 0 ? <p>기록된 경고가 없어요.</p>
          : <ul>{details.warnings.map((warning, index) => <li key={index}>
            <div className="run-warning-tooltip__source">
              <strong>{[warning.organizationName, warning.boardName].filter(Boolean).join(' · ') || '실행 경고'}</strong>
              <span>{warning.stage === 'SOURCE_COLLECTION' ? '게시판 수집' : warning.stage === 'ATTACHMENT_READ' ? '첨부파일 읽기' : '이전 기록'}{warning.count > 1 ? ` · ${warning.count}건` : ''}</span>
            </div>
            {warning.documentTitle && <p className="run-warning-tooltip__document">{warning.documentTitle}</p>}
            {warning.fileName && <p className="run-warning-tooltip__file">{warning.fileName}</p>}
            <p>{warning.message}</p>
          </li>)}</ul>}
    </div>, document.body)}
  </>
}
