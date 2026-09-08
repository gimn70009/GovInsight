import { useEffect, useRef, useState } from 'react'
import { Copy, FilePenLine, RefreshCw, Sparkles } from 'lucide-react'
import { api } from '../api/client'
import type { ProposalSource, ProposalWrittenDraft } from '../api/types'

const sourceKey = (source: ProposalSource) => `${source.attachmentId}:${source.partIndex}`

export function ProposalWriter({ detectionId, active, expired }: { detectionId: number; active: boolean; expired: boolean }) {
  const [sources, setSources] = useState<ProposalSource[]>([])
  const [selected, setSelected] = useState('')
  const [loadingSources, setLoadingSources] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [reload, setReload] = useState(0)
  const [sourceError, setSourceError] = useState('')
  const [drafts, setDrafts] = useState<Record<string, ProposalWrittenDraft>>({})
  const [writing, setWriting] = useState(false)
  const [error, setError] = useState('')
  const [copied, setCopied] = useState('')
  const pending = useRef<AbortController | null>(null)
  const mounted = useRef(true)
  const current = sources.find((source) => sourceKey(source) === selected)
  const draft = drafts[selected]

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; pending.current?.abort() }
  }, [])

  useEffect(() => {
    if (!active || loaded) return
    const controller = new AbortController()
    setLoadingSources(true)
    setSourceError('')
    api.getProposalSources(detectionId, controller.signal).then((items) => {
      if (controller.signal.aborted) return
      setSources(items)
      setLoaded(true)
    }).catch(() => {
      if (!controller.signal.aborted) setSourceError('첨부 목록을 불러오지 못했습니다.')
    }).finally(() => { if (!controller.signal.aborted) setLoadingSources(false) })
    return () => controller.abort()
  }, [active, detectionId, loaded, reload])

  async function generate() {
    if (!current?.available || pending.current) return
    const key = sourceKey(current)
    const controller = new AbortController()
    pending.current = controller
    setWriting(true)
    setError('')
    setCopied('')
    try {
      const response = await api.writeProposal(detectionId, current.attachmentId, current.partIndex, controller.signal)
      if (controller.signal.aborted || !mounted.current) return
      if (response.status === 'COMPLETED') setDrafts((previous) => ({ ...previous, [key]: response }))
      else setError(response.message)
    } catch {
      if (!controller.signal.aborted && mounted.current) setError('초안을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      if (pending.current === controller) {
        pending.current = null
        if (mounted.current) setWriting(false)
      }
    }
  }

  async function copy(text: string, label: string) {
    try {
      await navigator.clipboard.writeText(text)
      if (mounted.current) setCopied(label)
    } catch {
      if (mounted.current) setCopied('복사하지 못했습니다. 본문을 선택해 복사해 주세요.')
    }
  }

  return (
    <section className="proposal-writer" hidden={!active} aria-labelledby="proposal-writer-title">
      <div className="proposal-writer__heading">
        <span className="proposal-writer__icon"><FilePenLine size={22} /></span>
        <div><h3 id="proposal-writer-title">제안서 초안 작성</h3><p>첨부 양식의 핵심 항목 4개를 골라 우리 회사의 제안 본문을 작성합니다.</p></div>
      </div>
      {expired && <p className="proposal-writer__notice">접수가 종료된 공고입니다. 생성한 초안은 향후 제안을 위한 참고 자료로 활용해 주세요.</p>}
      {loadingSources ? <p role="status">첨부파일을 확인하고 있습니다.</p> : sourceError ? (
        <div className="proposal-writer__notice" role="alert">{sourceError} <button type="button" onClick={() => setReload((value) => value + 1)}>다시 불러오기</button></div>
      ) : loaded && sources.length === 0 ? (
        <p className="proposal-writer__notice">수집된 첨부파일이 없습니다. 제안서 또는 사업계획서 양식이 있는 공고에서 사용할 수 있습니다.</p>
      ) : (
        <div className="proposal-writer__controls">
          <div><label htmlFor="proposal-template">작성할 첨부 양식</label>
            <select id="proposal-template" value={selected} disabled={writing || !loaded}
              onChange={(event) => { setSelected(event.target.value); setError(''); setCopied('') }}>
              <option value="">제안서 또는 사업계획서 양식을 선택해 주세요</option>
              {sources.map((source) => <option key={sourceKey(source)} value={sourceKey(source)} disabled={!source.available}>
                {source.attachmentName !== source.fileName ? `${source.attachmentName} › ` : ''}{source.fileName}{!source.available ? ' (본문 확인 불가)' : ''}
              </option>)}
            </select>
          </div>
          <button type="button" className="proposal-writer__generate" disabled={!current?.available || writing || Boolean(draft)}
            onClick={generate}>{writing ? <RefreshCw size={16} className="proposal-writer__spin" /> : <Sparkles size={16} />}
            {writing ? '초안 작성 중' : draft ? '작성 완료' : '초안 생성'}</button>
        </div>
      )}
      {sources.some((source) => !source.available) && <details className="proposal-writer__unavailable"><summary>사용할 수 없는 첨부파일</summary>
        <ul>{sources.filter((source) => !source.available).map((source) => <li key={sourceKey(source)}>{source.fileName} — {source.reason}</li>)}</ul>
      </details>}
      {writing && <p role="status" className="proposal-writer__notice">실제 양식의 항목을 확인하고 회사 정보와 연결해 본문을 작성하고 있습니다. 최대 3분 정도 걸릴 수 있습니다.</p>}
      {error && <p role="alert" className="proposal-writer__error">{error}</p>}
      {draft && <div className="proposal-writer__result">
        <div className="proposal-writer__result-heading"><div><strong>{draft.sections.length}개 항목 초안</strong><span>{draft.fileName}</span></div>
          <button type="button" onClick={() => copy(draft.sections.map((item) => `${item.title}\n\n${item.body}`).join('\n\n'), '전체 본문을 복사했습니다.')}><Copy size={15} /> 전체 복사</button>
        </div>
        {draft.usesDemoProfile && <p className="proposal-writer__notice">실제 회사 소개와 데모 운영 정보를 바탕으로 작성했습니다. 데모의 고객·자원·인력 정보는 가상 설정이므로 제출 전에 실제 내용으로 확인해 주세요.</p>}
        {draft.message && <p className="proposal-writer__notice">{draft.message}</p>}
        {draft.sections.map((section, index) => <article className="proposal-writer__section" key={section.title}>
          <div className="proposal-writer__section-heading"><h4><span>{String(index + 1).padStart(2, '0')}</span>{section.title}</h4>
            <button type="button" aria-label={`${section.title} 본문 복사`} onClick={() => copy(section.body, `${index + 1}번 항목을 복사했습니다.`)}><Copy size={15} /> 복사</button></div>
          <p className="proposal-writer__body">{section.body}</p>
          {section.confirmationItems.length > 0 && <div className="proposal-writer__confirm"><strong>제출 전 확인할 내용</strong><ul>{section.confirmationItems.map((item, i) => <li key={i}>{item}</li>)}</ul></div>}
          <details className="proposal-writer__evidence"><summary>양식 원문</summary><p>{section.selectionReason}</p><blockquote>{section.sourceQuote}</blockquote></details>
        </article>)}
        <p className="proposal-writer__footnote">초안은 현재 화면에서 확인할 수 있습니다. 보관하려면 본문을 복사해 주세요.</p>
      </div>}
      <p className="proposal-writer__copy-status" role="status" aria-live="polite">{copied}</p>
    </section>
  )
}
