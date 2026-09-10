import { useEffect, useRef, useState } from 'react'
import { Check, Copy, FilePenLine, RefreshCw, Sparkles, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { ProposalDraftState, ProposalSource, SavedProposalDraft } from '../api/types'

const sourceKey = (source: { attachmentId: number; partIndex: number }) => `${source.attachmentId}:${source.partIndex}`
const sourceName = (source: { attachmentName: string; fileName: string }) =>
  source.attachmentName !== source.fileName ? `${source.attachmentName} › ${source.fileName}` : source.fileName

export function ProposalWriter({ detectionId, active, expired }: { detectionId: number; active: boolean; expired: boolean }) {
  const [sources, setSources] = useState<ProposalSource[]>([])
  const [savedDrafts, setSavedDrafts] = useState<SavedProposalDraft[]>([])
  const [selected, setSelected] = useState('')
  const [loading, setLoading] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [reload, setReload] = useState(0)
  const [sourceError, setSourceError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [running, setRunning] = useState<ProposalDraftState['running']>([])
  const [checking, setChecking] = useState(false)
  const [progressError, setProgressError] = useState('')
  const awaitingSource = useRef('')
  const awaitingOperation = useRef<string | null>(null)
  const [operationKind, setOperationKind] = useState<'GENERATE' | 'REGENERATE' | 'RESTORE'>('GENERATE')
  const [rewriteOpen, setRewriteOpen] = useState(false)
  const [feedback, setFeedback] = useState('')
  const needsStatus = running.length > 0 || checking
  const writing = submitting || needsStatus
  const [selectionError, setSelectionError] = useState('')
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  const mounted = useRef(true)
  const selectionRevision = useRef(0)
  const viewQueue = useRef<Promise<void>>(Promise.resolve())
  const resultHeading = useRef<HTMLDivElement>(null)
  const focusResult = useRef(false)
  const savedByKey = new Map(savedDrafts.map((item) => [sourceKey(item), item]))
  const allSources: ProposalSource[] = [...sources, ...savedDrafts
    .filter((item) => !sources.some((source) => sourceKey(source) === sourceKey(item)))
    .map((item) => ({ attachmentId: item.attachmentId, partIndex: item.partIndex,
      fileName: item.result.fileName, attachmentName: item.attachmentName, available: false, reason: '' }))]
  const selectableSources = allSources.filter((source) => source.available || savedByKey.has(sourceKey(source)))
  const unavailableSources = allSources.filter((source) => !source.available && !savedByKey.has(sourceKey(source)))
  const current = allSources.find((source) => sourceKey(source) === selected)
  const saved = savedByKey.get(selected)
  const draft = saved?.result

  useEffect(() => {
    if (draft && focusResult.current) {
      resultHeading.current?.focus({ preventScroll: true })
      focusResult.current = false
    }
  }, [draft])

  useEffect(() => {
    mounted.current = true
    // Leaving the detail view must not cancel the server's generation request.
    return () => { mounted.current = false }
  }, [])

  useEffect(() => {
    if (!active || loaded) return
    const controller = new AbortController()
    setLoading(true)
    setSourceError('')
    Promise.all([
      api.getProposalSources(detectionId, controller.signal),
      api.getProposalDraftState(detectionId, controller.signal),
    ]).then(([items, state]) => {
      if (controller.signal.aborted) return
      setSources(items)
      setSavedDrafts(state.drafts)
      setRunning(state.running)
      awaitingSource.current = state.running.length ? sourceKey(state.running[0]) : ''
      awaitingOperation.current = state.running[0]?.operationId ?? null
      setOperationKind(state.running[0]?.kind ?? 'GENERATE')
      setSelected(awaitingSource.current || (state.drafts.length ? sourceKey(state.drafts[0]) : ''))
      setLoaded(true)
    }).catch(() => {
      if (!controller.signal.aborted) setSourceError('첨부 양식과 작성한 초안을 불러오지 못했습니다.')
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [active, detectionId, loaded, reload])

  useEffect(() => {
    if (!active || !loaded || !needsStatus) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function poll() {
      try {
        const state = await api.getProposalDraftState(detectionId, controller.signal)
        if (controller.signal.aborted) return
        const completed = state.drafts.find((item) => sourceKey(item) === awaitingSource.current
          && (!awaitingOperation.current || item.lastOperationId === awaitingOperation.current))
        setSavedDrafts(state.drafts)
        setRunning(state.running)
        setProgressError('')
        if (state.running.length) {
          if (!state.running.some((item) => sourceKey(item) === awaitingSource.current)) {
            awaitingSource.current = sourceKey(state.running[0])
            awaitingOperation.current = state.running[0].operationId
            setOperationKind(state.running[0].kind)
          }
          setSelected(awaitingSource.current)
          timer = setTimeout(poll, 1500)
        } else {
          setChecking(false)
          if (completed) {
            focusResult.current = true
            setSelected(sourceKey(completed))
            setError('')
            setRewriteOpen(false)
            setFeedback('')
          } else {
            setError((previous) => previous || '요청이 완료되지 않았습니다. 기존 초안은 유지됩니다. 다시 시도해 주세요.')
          }
          awaitingSource.current = ''
          awaitingOperation.current = null
        }
      } catch {
        if (controller.signal.aborted) return
        // An unreachable status endpoint is not evidence that generation has stopped.
        setProgressError('생성 상태를 확인하지 못했습니다. 연결되면 자동으로 다시 확인합니다.')
        timer = setTimeout(poll, 3000)
      }
    }
    timer = setTimeout(poll, 1500)
    return () => { clearTimeout(timer); controller.abort() }
  }, [active, detectionId, loaded, needsStatus])

  function rememberSelection(source: { attachmentId: number; partIndex: number }, revision: number) {
    // Keep the last viewed draft in click order without blocking card selection.
    viewQueue.current = viewQueue.current.then(() =>
      api.rememberProposalDraft(detectionId, source.attachmentId, source.partIndex),
    ).catch(() => {
      if (mounted.current && selectionRevision.current === revision) {
        setSelectionError('최근 선택을 기억하지 못했습니다. 초안 본문은 저장되어 있어요.')
      }
    })
  }

  function selectSource(source: ProposalSource) {
    if (writing || pending.current) return
    const key = sourceKey(source)
    const next = selected === key ? '' : key
    const revision = ++selectionRevision.current
    setSelected(next)
    setRewriteOpen(false)
    setFeedback('')
    setError('')
    setSelectionError('')
    if (next && savedByKey.has(next)) rememberSelection(source, revision)
  }

  function retrySelection() {
    if (!current || !draft) return
    setSelectionError('')
    rememberSelection(current, ++selectionRevision.current)
  }

  async function submit(kind: 'GENERATE' | 'REGENERATE' | 'RESTORE') {
    if (!current?.available || writing || pending.current) return
    if (kind === 'GENERATE' ? Boolean(draft) : !saved) return
    if (kind === 'RESTORE' && !saved?.canRestorePrevious) return
    const key = sourceKey(current)
    const controller = new AbortController()
    const operationId = kind === 'GENERATE' ? null : crypto.randomUUID()
    pending.current = controller
    awaitingSource.current = key
    awaitingOperation.current = operationId
    setOperationKind(kind)
    setSubmitting(true)
    setProgressError('')
    setError('')
    try {
      const payload = { attachmentId: current.attachmentId, partIndex: current.partIndex,
        expectedRevision: saved?.revision ?? 0, operationId: operationId ?? '' }
      const response = kind === 'GENERATE'
        ? await api.writeProposal(detectionId, current.attachmentId, current.partIndex, controller.signal)
        : kind === 'REGENERATE'
          ? await api.regenerateProposal(detectionId, { ...payload, feedback: feedback.trim() }, controller.signal)
          : await api.restoreProposal(detectionId, payload, controller.signal)
      if (!mounted.current) return
      if (response.status === 'COMPLETED') {
        // Read the committed revision and operation ID; an older saved body is not a new result.
        setChecking(true)
        setSelectionError('')
        rememberSelection(current, ++selectionRevision.current)
      } else {
        setError(response.message + (draft ? ' 기존 초안은 유지됩니다.' : ''))
        awaitingSource.current = ''
        awaitingOperation.current = null
      }
    } catch (cause) {
      if (mounted.current) {
        if (cause instanceof ApiError && cause.status >= 400 && cause.status < 500) {
          setError(cause.message)
          // A different tab may have completed a newer revision. Refresh before offering retry.
          setChecking(true)
        } else {
          // A lost POST response may still have a running or committed result on the server.
          setChecking(true)
          setProgressError('처리 결과를 확인하고 있습니다. 잠시만 기다려 주세요.')
        }
      }
    } finally {
      if (pending.current === controller) {
        pending.current = null
        if (mounted.current) setSubmitting(false)
      }
    }
  }

  return (
    <section className="proposal-writer" hidden={!active} aria-labelledby="proposal-writer-title">
      <div className="proposal-writer__heading">
        <span className="proposal-writer__icon"><FilePenLine size={22} /></span>
        <div><h3 id="proposal-writer-title">제안서 초안</h3>
          <p>양식을 선택해 초안을 작성하거나 저장된 내용을 확인하세요.</p></div>
      </div>
      {expired && <p className="proposal-writer__notice">접수가 종료된 공고입니다. 생성한 초안은 향후 제안을 위한 참고 자료로 활용해 주세요.</p>}
      {loading ? <p role="status" className="proposal-writer__empty">첨부 양식과 초안을 불러오고 있습니다.</p> : sourceError ? (
        <div className="proposal-writer__notice" role="alert">{sourceError} <button type="button" onClick={() => setReload((value) => value + 1)}>다시 불러오기</button></div>
      ) : loaded && <div className="proposal-writer__picker">
        <div className="proposal-writer__picker-heading"><span>첨부 양식</span><small>{selectableSources.length}개</small></div>
        {allSources.length === 0 ? <p className="proposal-writer__empty">수집된 첨부파일이 없습니다. 첨부 양식이 있는 공고에서 작성할 수 있어요.</p> : <>
          <div className="proposal-writer__sources" role="group" aria-label="첨부 양식">
            {selectableSources.map((source) => {
              const key = sourceKey(source)
              const saved = savedByKey.get(key)
              const related = source.relatedFileNames ?? []
              return <div key={key} className="proposal-writer__source-item"><button type="button" className="proposal-writer__source-card"
                aria-label={sourceName(source)} aria-pressed={selected === key}
                data-saved={Boolean(saved)} disabled={writing}
                onClick={() => selectSource(source)}>
                <span className="proposal-writer__selection-mark" aria-hidden="true">{selected === key && <Check size={12} />}</span>
                <span className="proposal-writer__file-info">
                  <span className="proposal-writer__file-name">{source.fileName}</span>
                  {source.attachmentName !== source.fileName && <small>{source.attachmentName}</small>}
                  {saved && <span className="proposal-writer__saved-label"><Check size={12} />작성 완료</span>}
                </span>
              </button>
              {related.length > 1 && <details className="proposal-writer__related-files">
                <summary>같은 본문 파일 {related.length}개</summary>
                <ul>{related.map((name, index) => <li key={index}>{name}</li>)}</ul>
              </details>}
              </div>
            })}
          </div>
          {unavailableSources.length > 0 &&
            <details className="proposal-writer__unavailable-files">
              <summary>사용할 수 없는 파일 {unavailableSources.length}개</summary>
              <ul>{unavailableSources.map((source) =>
                <li key={sourceKey(source)}><span>{source.fileName}</span>
                  <small>{source.reason || '본문을 읽지 못한 파일입니다.'}</small></li>)}</ul>
            </details>}
          {current && !draft && <div className="proposal-writer__actions">
            <span>작성한 초안은 자동 저장됩니다.</span>
            <button type="button" className="proposal-writer__generate" disabled={!current.available || writing} onClick={() => void submit('GENERATE')}>
              {writing ? <RefreshCw size={16} className="proposal-writer__spin" /> : <Sparkles size={16} />}
              {writing ? '초안 작성 중' : '초안 생성'}
            </button>
          </div>}
        </>}
      </div>}
      {selectionError && <div role="alert" className="proposal-writer__notice">{selectionError}
        <button type="button" onClick={retrySelection}>다시 기억하기</button>
      </div>}
      {writing && <p role="status" className="proposal-writer__notice">{operationKind === 'RESTORE'
        ? '이전 초안을 복원하고 있습니다.'
        : operationKind === 'REGENERATE'
          ? '초안을 다시 작성하고 있습니다. 새 결과가 완성될 때까지 기존 초안은 유지됩니다. 최대 3분 정도 걸릴 수 있습니다.'
          : '초안을 작성하고 있습니다. 완료되면 자동으로 저장됩니다. 최대 3분 정도 걸릴 수 있습니다.'}</p>}
      {progressError && <p role="status" className="proposal-writer__notice">{progressError}</p>}
      {error && <p role="alert" className="proposal-writer__error">{error}</p>}
      {draft && <div className="proposal-writer__result" key={selected}>
        <div className="proposal-writer__result-heading" ref={resultHeading} tabIndex={-1}>
          <strong>{draft.sections.length}개 항목 초안</strong>
          <div className="proposal-writer__result-actions">
            {current?.available && <>
              {saved?.canRestorePrevious && <button type="button" disabled={writing}
                onClick={() => void submit('RESTORE')}>이전 초안으로 복원</button>}
              <button type="button" disabled={writing} aria-expanded={rewriteOpen}
                aria-controls="proposal-rewrite-form" onClick={() => setRewriteOpen((value) => !value)}>
                <RefreshCw size={15} />다시 작성
              </button>
            </>}
          <ProposalCopyButton text={draft.sections.map((item) => `${item.title}\n\n${item.body}`).join('\n\n')} label="전체 복사" />
          </div>
        </div>
        {rewriteOpen && current?.available && <form id="proposal-rewrite-form" className="proposal-writer__rewrite"
          onSubmit={(event) => { event.preventDefault(); void submit('REGENERATE') }}>
          <label htmlFor="proposal-rewrite-feedback">수정 요청 <span>(선택)</span></label>
          <textarea id="proposal-rewrite-feedback" value={feedback} maxLength={2000} rows={3} disabled={writing}
            placeholder="예: 사업 소개를 줄이고 유럽 기관과의 협력 계획을 강조해 주세요."
            aria-describedby="proposal-rewrite-help" onChange={(event) => setFeedback(event.target.value)} />
          <p id="proposal-rewrite-help">새 초안이 완성되면 자동 저장되며, 직전 초안으로 복원할 수 있습니다.</p>
          <div className="proposal-writer__rewrite-actions">
            <button type="button" disabled={writing} onClick={() => setRewriteOpen(false)}>취소</button>
            <button type="submit" className="proposal-writer__generate" disabled={writing}>
              <RefreshCw size={15} className={writing ? 'proposal-writer__spin' : undefined} />
              {writing ? '처리 중' : '다시 작성하기'}
            </button>
          </div>
        </form>}
        {draft.message && <p className="proposal-writer__notice">{draft.message}</p>}
        {draft.sections.map((section, index) => <article className="proposal-writer__section" key={section.title}>
          <div className="proposal-writer__section-heading"><div className="proposal-writer__section-title">
            <span className="proposal-writer__section-number">{index + 1}</span><h4>{section.title}</h4></div>
            <ProposalCopyButton text={section.body} label="복사" accessibleLabel={`${section.title} 본문 복사`} /></div>
          <p className="proposal-writer__body">{section.body}</p>
          {section.confirmationItems.length > 0 && <div className="proposal-writer__confirm"><strong>제출 전 확인할 내용</strong><ul>{section.confirmationItems.map((item, i) => <li key={i}>{item}</li>)}</ul></div>}
          <details className="proposal-writer__evidence"><summary>양식 원문</summary><p>{section.selectionReason}</p><blockquote>{section.sourceQuote}</blockquote></details>
        </article>)}
        <p className="proposal-writer__footnote">초안이 자동 저장되었습니다.</p>
      </div>}
    </section>
  )
}

function ProposalCopyButton({ text, label, accessibleLabel = label }: {
  text: string
  label: string
  accessibleLabel?: string
}) {
  const [status, setStatus] = useState<'idle' | 'copying' | 'copied' | 'failed'>('idle')
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  useEffect(() => {
    if (status !== 'copied' && status !== 'failed') return
    const timer = window.setTimeout(() => setStatus('idle'), 2000)
    return () => window.clearTimeout(timer)
  }, [status])

  async function copy() {
    setStatus('copying')
    try {
      await navigator.clipboard.writeText(text)
      if (mounted.current) setStatus('copied')
    } catch {
      if (mounted.current) setStatus('failed')
    }
  }

  const feedback = status === 'copied' ? '복사됨' : status === 'failed' ? '복사 실패' : status === 'copying' ? '복사 중' : label
  return (
    <button type="button" className="proposal-writer__copy-button" data-status={status}
      disabled={status === 'copying'} onClick={copy} aria-live="polite"
      aria-label={status === 'idle' ? accessibleLabel : `${accessibleLabel}: ${feedback}`}
      title={status === 'failed' ? '다시 누르거나 본문을 선택해 복사해 주세요.' : undefined}>
      {status === 'copied' ? <Check size={15} /> : status === 'failed' ? <X size={15} /> : <Copy size={15} />}
      {feedback}
    </button>
  )
}