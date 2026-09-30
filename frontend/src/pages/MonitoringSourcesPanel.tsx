import { type FormEvent } from 'react'
import { Building2, ExternalLink, Save } from 'lucide-react'
import type { MonitoringSource } from '../api/types'
import { sourceDraft, sourceSettingsChanges, validCollectionCount, type SourceDraft, type SourceDrafts } from '../utils/monitoringSourceSettings'
import { EmptyState, Loading } from '../components/ui'

interface Props {
  sources: MonitoringSource[]
  drafts: SourceDrafts
  loading: boolean
  saving: boolean
  error: string
  onChange: (sourceId: number, draft: SourceDraft) => void
  onSave: () => Promise<void>
}

export function MonitoringSourcesPanel({ sources, drafts, loading, saving, error, onChange, onSave }: Props) {
  const { dirtyCount, invalidSourceIds } = sourceSettingsChanges(sources, drafts)
  const disabled = loading || saving
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!disabled && dirtyCount > 0 && invalidSourceIds.length === 0) void onSave()
  }

  return (
    <section className="panel monitoring-sources" aria-labelledby="monitoring-sources-title">
      <form onSubmit={submit}>
        <div className="monitoring-sources__header">
          <div>
            <div className="monitoring-sources__title"><h2 id="monitoring-sources-title">모니터링 소스</h2><span>{sources.length || 6}개 기관</span></div>
            <p>한 번에 확인할 게시글 수와 활성 상태를 설정하세요.</p>
          </div>
          <div className="monitoring-sources__save">
            {dirtyCount > 0 && <span role="status">{dirtyCount}개 기관 변경</span>}
            <button type="submit" className="button button--primary" disabled={disabled || dirtyCount === 0 || invalidSourceIds.length > 0}><Save size={15} />{saving ? '저장 중...' : '변경사항 저장'}</button>
          </div>
        </div>
        {error && <p className="monitoring-sources__error" role="alert">{error}</p>}
        {loading && sources.length === 0 ? <Loading /> : sources.length === 0 ? (
          <EmptyState icon={<Building2 />} title="기본 소스를 불러오지 못했어요" description="새로고침 후에도 같다면 서버의 기본 소스 등록 상태를 확인해 주세요." />
        ) : (
          <>
            <div className="monitoring-sources__list">
              {sources.map((source) => {
                const draft = sourceDraft(source, drafts)
                const valid = validCollectionCount(draft.detailFetchCount)
                const count = Number(draft.detailFetchCount)
                const changed = !valid || count !== source.detailFetchCount || draft.enabled !== source.enabled
                return (
                  <article key={source.sourceId} className={`monitoring-source ${!draft.enabled ? 'monitoring-source--paused' : ''} ${changed ? 'monitoring-source--changed' : ''}`} aria-label={source.organizationName}>
                    <div className="monitoring-source__header">
                      <div className="monitoring-source__identity">
                        <span className="source-logo" aria-hidden="true">{source.organizationName.slice(0, 1)}</span>
                        <h3 title={source.description ?? undefined}>{source.organizationName}</h3>
                      </div>
                      <div className="monitoring-source__status"><span>{draft.enabled ? '활성' : '중지'}</span><button type="button" className={`toggle ${draft.enabled ? 'toggle--on' : ''}`} role="switch" aria-checked={draft.enabled} aria-label={`${source.organizationName} 활성 상태`} disabled={disabled} onClick={() => onChange(source.sourceId, { ...draft, enabled: !draft.enabled })}><span /></button></div>
                    </div>
                    <div className="monitoring-source__footer">
                      <div className="monitoring-source__count">
                        <label htmlFor={`source-count-${source.sourceId}`}>수집 건수</label>
                        <div className={`source-count-field ${valid ? '' : 'source-count-field--invalid'}`}>
                          <input id={`source-count-${source.sourceId}`} aria-label={`${source.organizationName} 수집 건수`} aria-invalid={!valid} aria-describedby={!valid ? `count-error-${source.sourceId}` : undefined} type="number" inputMode="numeric" min="1" max="2147483647" step="1" required value={draft.detailFetchCount} disabled={disabled} onChange={(event) => onChange(source.sourceId, { ...draft, detailFetchCount: event.target.value })} />
                          <span aria-hidden="true">건</span>
                        </div>
                      </div>
                      <a className="monitoring-source__board-link" href={source.listUrl} target="_blank" rel="noreferrer" aria-label={`${source.organizationName} ${source.boardName} 게시판 새 창에서 열기`}>{source.boardName} 보기<ExternalLink size={13} /></a>
                    </div>
                    {!valid && <small className="monitoring-source__count-error" id={`count-error-${source.sourceId}`} role="alert">수집 건수는 1 이상의 정수로 입력해 주세요.</small>}
                  </article>
                )
              })}
            </div>
          </>
        )}
      </form>
    </section>
  )
}
