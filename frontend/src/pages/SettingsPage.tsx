import './SettingsPage.css'
import { useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { ArrowRight, Building2, Check, ChevronDown, CircleCheck, Clock3, Compass, Gauge, Info, Layers3, Lightbulb, Plus, RefreshCw, Save, SlidersHorizontal, Sparkles, Target, Trash2, TrendingUp } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { CatalogKey, CertificationStatus, CompanyType, EvaluationCriteria, EvaluationSettings, EvaluationSettingsData, ProjectStatus, RegisteredCompany, SettingsPreset } from '../api/evaluationSettings'
import SettingsKeywordPicker from '../components/SettingsKeywordPicker'
import SettingsUrgency from '../components/SettingsUrgency'
import SettingsFeasibilityOptions from '../components/SettingsFeasibilityOptions'
import { InlineError, Loading, Toast } from '../components/ui'
import { useToast } from '../hooks/useToast'
import { parsePreparationDays, parseUrgencyDays, settingsPresets, validateEvaluationSettings } from '../utils/evaluationSettings'

type Picker = { title: string; catalog: CatalogKey; selected: string[]; limit: number; apply: (values: string[]) => void }
type Criterion = 'fit' | 'value' | 'feasibility' | 'urgency'
const companyTypes: [CompanyType, string][] = [['UNKNOWN', '아직 확인하지 않음'], ['SME', '중소기업'], ['MID_SIZED', '중견기업'], ['LARGE', '대기업'], ['SOLE_PROPRIETOR', '개인사업자'], ['RESEARCH', '대학·연구기관'], ['NONPROFIT', '비영리기관']]

function ChoiceRow({ title, hint, values, onEdit }: { title: string; hint?: string; values: string[]; onEdit: () => void }) {
  return <div className="settings-choice-row">
    <div className="settings-choice-label"><strong>{title}</strong>{hint && <span className="settings-choice-hint">{hint}</span>}</div>
    <div className="settings-choice-value">
      <div className="settings-chips">{values.slice(0, 5).map(value => <span className="settings-chip" key={value}>{value}</span>)}{values.length > 5 && <span className="settings-muted">외 {values.length - 5}개</span>}</div>
      <button type="button" className="settings-edit-button" onClick={onEdit} aria-label={`${title} 선택 변경`}><SlidersHorizontal size={14} />선택 변경</button>
    </div>
  </div>
}

type GuideItem = [label: string, detail: string, source?: 'selection']

function GuideCard({ items, note }: { items: GuideItem[]; note: string }) {
  const [expanded, setExpanded] = useState(false)
  const detailsId = useId()

  return <aside className={`settings-guide${expanded ? ' is-expanded' : ''}`} aria-label="평가 기준 안내" style={{ '--guide-columns': items.length } as CSSProperties}>
    <div className="settings-guide__overview">
      <div className="settings-guide__heading"><Lightbulb size={18} aria-hidden="true" /><h4>평가 기준</h4></div>
      <div className="settings-guide__items" hidden={expanded}>{items.map(([label]) => {
        const [name, score] = label.split(' · ')
        return <div className="settings-guide__item" key={label}>
          <strong>{name}</strong>{score && <span>{score}</span>}
        </div>
      })}</div>
      <button type="button" className="settings-guide__toggle" aria-expanded={expanded} aria-controls={detailsId} onClick={() => setExpanded(value => !value)}>
        <span>{expanded ? '설명 접기' : '설명 보기'}</span><ChevronDown size={16} aria-hidden="true" />
      </button>
    </div>
    <div id={detailsId} className="settings-guide__details" hidden={!expanded}>
      <dl>{items.map(([label, detail, source]) => {
        const [name, score] = label.split(' · ')
        return <div className="settings-guide__detail-card" key={label}>
          <dt><span>{name}</span>{score && <span className="settings-guide__score">{score}</span>}</dt>
          <dd>
            <p>{detail}</p>
            {source && <span className="settings-guide__source">
              <SlidersHorizontal size={12} aria-hidden="true" />직접 선택
            </span>}
          </dd>
        </div>
      })}</dl>
      <div className="settings-guide__note"><Info size={14} aria-hidden="true" /><span>{note}</span></div>
    </div>
  </aside>
}

function CriterionSection({ id, icon, title, description, open, onToggle, children }: { id: Criterion; icon: ReactNode; title: string; description: string; open: boolean; onToggle: () => void; children: ReactNode }) {
  return <section className={`settings-criterion${open ? ' is-open' : ''}`} data-criterion={id}>
    <button type="button" className="settings-criterion__header" onClick={onToggle} aria-expanded={open} aria-controls={`criterion-${id}`}>
      <span className="settings-criterion__icon">{icon}</span>
      <span className="settings-criterion__heading"><strong>{title}</strong><span>{description}</span></span>
      <ChevronDown size={18} />
    </button>
    <div id={`criterion-${id}`} hidden={!open} className="settings-criterion__body">{children}</div>
  </section>
}

export default function SettingsPage() {
  const [saved, setSaved] = useState<EvaluationSettings | null>(null)
  const [draft, setDraft] = useState<EvaluationSettingsData | null>(null)
  const [preparationDaysText, setPreparationDaysText] = useState('')
  const [urgencyDaysText, setUrgencyDaysText] = useState('')
  const [tab, setTab] = useState<'company' | 'criteria'>('company')
  const [open, setOpen] = useState<Criterion | null>('fit')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [conflict, setConflict] = useState(false)
  const [picker, setPicker] = useState<Picker | null>(null)
  const [showWeights, setShowWeights] = useState(false)
  const [loadSequence, setLoadSequence] = useState(0)
  const [toast, setToast] = useToast()
  const action = useRef(false)
  const edited = !!saved && !!draft && (JSON.stringify(saved.data) !== JSON.stringify(draft) || preparationDaysText !== String(saved.data.criteria.preparationDays ?? '') || urgencyDaysText !== String(saved.data.criteria.urgencyDays))
  const dirty = !!saved && (saved.revision === 0 || edited)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    api.getEvaluationSettings(controller.signal).then(data => {
      if (controller.signal.aborted) return
      setSaved(data); setDraft(structuredClone(data.data)); setPreparationDaysText(String(data.data.criteria.preparationDays ?? '')); setUrgencyDaysText(String(data.data.criteria.urgencyDays)); setError(''); setConflict(false)
    }).catch(reason => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '설정을 불러오지 못했습니다.')
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [loadSequence])

  useEffect(() => {
    if (!edited) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    const guardNavigation = (event: MouseEvent) => {
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      const link = (event.target as Element).closest<HTMLAnchorElement>('a[href]')
      if (!link || link.target === '_blank' || link.origin !== location.origin || link.pathname === location.pathname) return
      if (!window.confirm('저장하지 않은 변경사항이 있습니다. 이 화면을 나갈까요?')) {
        event.preventDefault(); event.stopPropagation()
      }
    }
    window.addEventListener('beforeunload', warn)
    document.addEventListener('click', guardNavigation, true)
    return () => {
      window.removeEventListener('beforeunload', warn)
      document.removeEventListener('click', guardNavigation, true)
    }
  }, [edited])

  const companyChange = <K extends keyof RegisteredCompany>(key: K, value: RegisteredCompany[K]) => setDraft(current => current ? { ...current, company: { ...current.company, [key]: value } } : current)
  const criteriaChange = <K extends keyof EvaluationCriteria>(key: K, value: EvaluationCriteria[K]) => setDraft(current => current ? { ...current, criteria: { ...current.criteria, [key]: value } } : current)
  const choose = (title: string, catalog: CatalogKey, selected: string[], limit: number, apply: (values: string[]) => void) => setPicker({ title, catalog, selected, limit, apply })

  const save = async () => {
    if (!draft || !saved || action.current || !dirty) return
    const invalid = validateEvaluationSettings(draft)
    if (invalid) { setError(invalid); return }
    action.current = true; setSaving(true); setError(''); setConflict(false)
    try {
      const response = await api.updateEvaluationSettings({ revision: saved.revision, data: draft })
      setSaved(response); setDraft(structuredClone(response.data)); setPreparationDaysText(String(response.data.criteria.preparationDays ?? '')); setUrgencyDaysText(String(response.data.criteria.urgencyDays)); setToast('설정을 저장했습니다. 다음 모니터링부터 적용됩니다.')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '설정을 저장하지 못했습니다.')
      setConflict(reason instanceof ApiError && reason.status === 409)
    } finally { action.current = false; setSaving(false) }
  }

  if (loading && !saved) return <div className="page settings-page"><Loading label="회사 정보와 평가 기준을 불러오고 있습니다" /></div>
  if (!saved || !draft) return <div className="page settings-page"><InlineError message={error || '설정을 불러오지 못했습니다.'} /><button className="button button--secondary" onClick={() => setLoadSequence(value => value + 1)}><RefreshCw size={16} />다시 불러오기</button></div>

  const c = draft.company, criteria = draft.criteria
  const preset = settingsPresets.find(item => item.id === criteria.preset) ?? settingsPresets[0]
  const toggle = (id: Criterion) => setOpen(current => current === id ? null : id)

  return <div className="page settings-page">
    <header className="page-header settings-page__header"><div><span className="eyebrow">WORKSPACE SETTINGS</span><h1>우리 회사에 맞는 기회의 기준<span className="settings-heading-dot">.</span></h1><p>회사 정보를 등록하고, 원하는 사업 기회의 기준을 설정하세요.</p></div></header>
    <div className="settings-topline"><div className="settings-tabs" role="group" aria-label="설정 화면"><button type="button" aria-pressed={tab === 'company'} onClick={() => setTab('company')}><Building2 size={20} />우리 회사 정보</button><button type="button" aria-pressed={tab === 'criteria'} onClick={() => setTab('criteria')}><Compass size={20} />기회점수 기준</button></div><div className="settings-save">{dirty && <span className="has-changes">{edited ? '저장하지 않은 변경사항' : '기본 설정 · 저장 후 적용'}</span>}<button type="button" className="button button--primary" disabled={saving || loading || !dirty || conflict} onClick={() => void save()}><Save size={16} />{saving ? '저장 중…' : '변경사항 저장'}</button></div></div>
    {error && <div className="settings-error"><InlineError message={error} />{conflict && <button type="button" className="button button--secondary" disabled={loading} onClick={() => { if (window.confirm('편집한 내용을 버리고 최신 설정을 불러올까요?')) setLoadSequence(value => value + 1) }}>최신 설정으로 다시 시작</button>}</div>}
    <fieldset className="settings-editor" disabled={saving || loading}>
      {tab === 'company' ? <div className="settings-company">
        <section className="settings-surface"><div className="settings-section-heading"><span className="settings-section-icon"><Building2 size={19} /></span><div><h2>우리 회사 소개</h2><p>공고의 신청 조건과 회사 역량을 비교할 때 사용합니다.</p></div></div><div className="settings-form-grid">
          <label>회사명 <span className="settings-required">*</span><input value={c.name} maxLength={100} onChange={e => companyChange('name', e.target.value)} placeholder="회사명을 입력해 주세요" /></label>
          <label>기업 유형<select value={c.type} onChange={e => companyChange('type', e.target.value as CompanyType)}>{companyTypes.map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label>
          <label>소재지<input value={c.location} maxLength={200} onChange={e => companyChange('location', e.target.value)} placeholder="예: 서울특별시 서초구" /></label>
          <label>설립일<input type="date" value={c.establishedOn ?? ''} onChange={e => companyChange('establishedOn', e.target.value || null)} /></label>
          <label className="settings-form-wide">회사 소개<textarea value={c.description} maxLength={2000} rows={3} onChange={e => companyChange('description', e.target.value)} placeholder="주요 제품과 서비스, 회사의 강점을 소개해 주세요." /><span className="settings-input-note">{c.description.length.toLocaleString()} / 2,000</span></label>
        </div></section>
        <section className="settings-surface"><div className="settings-section-heading"><span className="settings-section-icon"><Layers3 size={19} /></span><div><h2>사업 분야와 보유 역량</h2><p>현재 실제로 수행하는 분야와 제공할 수 있는 기술을 선택하세요.</p></div></div><ChoiceRow title="현재 사업 분야" values={c.industries} onEdit={() => choose('사업 분야 선택', 'industries', c.industries, 16, value => companyChange('industries', value))} /><ChoiceRow title="보유 기술·서비스" values={c.technologies} onEdit={() => choose('보유 기술·서비스 선택', 'technologies', c.technologies, 46, value => companyChange('technologies', value))} /><div className="settings-inline-guide"><Info size={15} /><span>앞으로 진출하고 싶은 분야는 <button type="button" onClick={() => setTab('criteria')}>기회점수 기준 <ArrowRight size={12} /></button>에서 선택하세요.</span></div></section>
        <section className="settings-surface settings-optional"><details><summary><span><span className="settings-section-icon"><Gauge size={18} /></span><strong>수행 실적</strong><small>선택</small>{c.projects.length > 0 && <b>{c.projects.length}건</b>}</span><ChevronDown size={18} /></summary><div className="settings-records"><p>진행 중인 과제와 완료 실적을 구분해서 등록하세요.</p>{c.projects.map((project, index) => <div className="settings-record" key={index}><div className="settings-record__header"><strong>프로젝트 {index + 1}</strong><button type="button" className="settings-icon-button" aria-label={`프로젝트 ${index + 1} 삭제`} onClick={() => companyChange('projects', c.projects.filter((_, i) => i !== index))}><Trash2 size={16} /></button></div><div className="settings-form-grid"><label>프로젝트명<input maxLength={150} value={project.title} onChange={e => companyChange('projects', c.projects.map((item, i) => i === index ? { ...item, title: e.target.value } : item))} /></label><label>수행 상태<select value={project.status} onChange={e => companyChange('projects', c.projects.map((item, i) => i === index ? { ...item, status: e.target.value as ProjectStatus } : item))}><option value="IN_PROGRESS">진행 중</option><option value="COMPLETED">완료</option><option value="PLANNED">계획 단계</option></select></label><label className="settings-form-wide">수행 내용<textarea rows={2} maxLength={1000} value={project.description} onChange={e => companyChange('projects', c.projects.map((item, i) => i === index ? { ...item, description: e.target.value } : item))} /></label></div></div>)}<button type="button" className="settings-add-button" disabled={c.projects.length >= 20} onClick={() => companyChange('projects', [...c.projects, { title: '', description: '', status: 'IN_PROGRESS' }])}><Plus size={15} />수행 실적 추가</button></div></details></section>
        <section className="settings-surface settings-optional"><details><summary><span><span className="settings-section-icon"><CircleCheck size={18} /></span><strong>인증·자격</strong><small>선택</small>{c.certifications.length > 0 && <b>{c.certifications.length}건</b>}</span><ChevronDown size={18} /></summary><div className="settings-records"><p>등록한 정보는 사용자 입력으로 관리하며, 공고별 공식 증빙은 별도로 확인합니다.</p>{c.certifications.map((cert, index) => <div className="settings-record" key={index}><div className="settings-record__header"><strong>인증·자격 {index + 1}</strong><button type="button" className="settings-icon-button" aria-label={`인증·자격 ${index + 1} 삭제`} onClick={() => companyChange('certifications', c.certifications.filter((_, i) => i !== index))}><Trash2 size={16} /></button></div><div className="settings-form-grid"><label className="settings-form-wide">인증·자격명<input maxLength={150} value={cert.name} onChange={e => companyChange('certifications', c.certifications.map((item, i) => i === index ? { ...item, name: e.target.value } : item))} /></label><label>상태<select value={cert.status} onChange={e => companyChange('certifications', c.certifications.map((item, i) => i === index ? { ...item, status: e.target.value as CertificationStatus } : item))}><option value="HELD">보유 · 사용자 입력</option><option value="NEEDS_VERIFICATION">확인 필요</option><option value="EXPIRED">만료</option></select></label><label>유효기간<input type="date" value={cert.expiresOn ?? ''} onChange={e => companyChange('certifications', c.certifications.map((item, i) => i === index ? { ...item, expiresOn: e.target.value || null } : item))} /></label></div></div>)}<button type="button" className="settings-add-button" disabled={c.certifications.length >= 20} onClick={() => companyChange('certifications', [...c.certifications, { name: '', expiresOn: null, status: 'NEEDS_VERIFICATION' }])}><Plus size={15} />인증·자격 추가</button></div></details></section>
      </div> : <div className="settings-criteria">
        <div className="settings-criteria-list">
          <CriterionSection id="fit" icon={<Target size={20} />} title="회사 적합도" description="관심 분야와의 일치도" open={open === 'fit'} onToggle={() => toggle('fit')}><div className="settings-criterion-layout"><GuideCard items={[["산업 일치 · 최대 40점", '공고의 적용 산업과 관심 분야'], ['과업 일치 · 최대 40점', '실제 수행 과업과 선택한 기술'], ['역할 일치 · 최대 20점', '공급·도입·연구 등 모집 역할']]} note="단순 키워드 언급에는 가점을 주지 않으며, 같은 뜻의 표현은 한 번만 반영합니다." /><div className="settings-criterion-fields"><ChoiceRow title="관심 산업" values={criteria.industries} onEdit={() => choose('관심 산업 선택', 'industries', criteria.industries, 16, value => criteriaChange('industries', value))} /><ChoiceRow title="찾는 기술·과업" values={criteria.tasks} onEdit={() => choose('기술·과업 선택', 'technologies', criteria.tasks, 46, value => criteriaChange('tasks', value))} /><ChoiceRow title="희망 참여 역할" values={criteria.roles} onEdit={() => choose('참여 역할 선택', 'roles', criteria.roles, 6, value => criteriaChange('roles', value))} /></div></div></CriterionSection>
          <CriterionSection id="value" icon={<TrendingUp size={20} />} title="사업 매력도" description="지원과 기대 성과" open={open === 'value'} onToggle={() => toggle('value')}><div className="settings-criterion-layout"><GuideCard items={[["사업 유형 · 최대 30점", '선택한 유형과 맞는 사업인지', 'selection'], ['기대 성과 · 최대 70점', '원하는 성과를 얻을 수 있는지', 'selection']]} note="미확인 지원금·후속 계약은 반영하지 않아요." /><div className="settings-criterion-fields"><ChoiceRow title="사업 유형" values={criteria.businessTypes} onEdit={() => choose('사업 유형 선택', 'businessTypes', criteria.businessTypes, 12, value => criteriaChange('businessTypes', value))} /><ChoiceRow title="기대 성과" hint="최대 3개" values={criteria.goals} onEdit={() => choose('기대 성과 선택', 'goals', criteria.goals, 3, value => criteriaChange('goals', value))} /></div></div></CriterionSection>
          <CriterionSection id="feasibility" icon={<Gauge size={20} />} title="실행 가능성" description="회사 역량과 참여 조건" open={open === 'feasibility'} onToggle={() => toggle('feasibility')}><div className="settings-criterion-layout"><GuideCard items={[["자격·기술 · 최대 55점", '신청 자격과 보유 기술이 맞는지'], ['실적·일정 · 최대 35점', '인정 가능한 실적과 준비 시간이 있는지'], ['필수 요건 · 최대 10점', '필수 인증·서류·파트너가 준비됐는지']]} note="미등록 정보는 확인 필요로 표시합니다. 공동 참여 의향을 파트너 확보로 판단하지 않습니다." /><div className="settings-criterion-fields"><div className="settings-profile-reference"><Building2 size={20} /><div><strong>{c.name || '우리 회사'}</strong><span>등록한 기술·실적·인증</span></div><button type="button" className="settings-edit-button" onClick={() => setTab('company')}>회사 정보<ArrowRight size={13} /></button></div><SettingsFeasibilityOptions days={preparationDaysText} mode={criteria.participationMode} onDaysChange={value => { setPreparationDaysText(value); criteriaChange('preparationDays', parsePreparationDays(value)) }} onModeChange={value => criteriaChange('participationMode', value)} /></div></div></CriterionSection>
          <CriterionSection id="urgency" icon={<Clock3 size={20} />} title="긴급도" description="마감이 가까운 공고를 먼저 확인해요" open={open === 'urgency'} onToggle={() => toggle('urgency')}>
            <SettingsUrgency value={urgencyDaysText} onChange={value => { setUrgencyDaysText(value); criteriaChange('urgencyDays', parseUrgencyDays(value)) }} />
          </CriterionSection>
        </div>
        <section className="settings-weight-panel"><button type="button" className="settings-weight-toggle" aria-expanded={showWeights} aria-controls="settings-weights" onClick={() => setShowWeights(value => !value)}><span><SlidersHorizontal size={17} /><strong>점수 반영 비율</strong><small>{preset.label}</small></span><ChevronDown size={16} className={showWeights ? 'is-open' : ''} /></button><div id="settings-weights" hidden={!showWeights} className="settings-weight-content"><div className="settings-presets">{settingsPresets.map(item => <button type="button" key={item.id} aria-pressed={criteria.preset === item.id} onClick={() => criteriaChange('preset', item.id as SettingsPreset)}><span>{item.label}{criteria.preset === item.id && <Check size={15} />}</span><small>{item.description}</small></button>)}</div><div className="settings-weight-values">{['회사 적합도', '사업 매력도', '실행 가능성', '긴급도'].map((label, index) => <div key={label}><span>{label}</span><strong>{preset.weights[index]}<small>%</small></strong><div><i style={{ width: `${preset.weights[index]}%` }} /></div></div>)}</div></div></section>
      </div>}
    </fieldset>
    <div className="settings-application-note"><Sparkles size={16} /><p>저장한 설정은 <strong>다음 모니터링부터</strong> 적용됩니다. 기존 공고는 다시 확인할 때 갱신하며, 이미 발송한 보고서는 보존합니다.</p></div>
    {picker && <SettingsKeywordPicker title={picker.title} groups={saved.catalog[picker.catalog]} selected={picker.selected} limit={picker.limit} onClose={() => setPicker(null)} onApply={values => { picker.apply(values); setPicker(null) }} />}
    {toast && <Toast message={toast} onClose={() => setToast('')} />}
  </div>
}
