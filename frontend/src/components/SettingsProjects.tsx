import { useEffect, useId, useRef, useState } from 'react'
import { BriefcaseBusiness, Check, ChevronDown, ChevronUp, CircleCheck, Clock3, Pencil, Plus, Trash2 } from 'lucide-react'
import type { ProjectStatus, RegisteredCompany } from '../api/evaluationSettings'
import './SettingsRecordCards.css'

type Project = RegisteredCompany['projects'][number]
const statuses = {
  COMPLETED: { label: '완료', icon: CircleCheck },
  IN_PROGRESS: { label: '진행 중', icon: Clock3 },
  PLANNED: { label: '계획 단계', icon: BriefcaseBusiness },
} as const

export default function SettingsProjects({ projects, onChange }: {
  projects: Project[]
  onChange: (projects: Project[]) => void
}) {
  const [editingIndex, setEditingIndex] = useState<number | null>(null)
  const editingToggle = useRef<HTMLButtonElement>(null)
  const titleInput = useRef<HTMLInputElement>(null)
  const addButton = useRef<HTMLButtonElement>(null)
  const sectionId = useId()

  useEffect(() => {
    if (editingIndex !== null) titleInput.current?.focus()
  }, [editingIndex])

  const update = (index: number, change: Partial<Project>) => {
    onChange(projects.map((project, current) => current === index ? { ...project, ...change } : project))
  }
  const add = () => {
    if (projects.length >= 20) return
    onChange([...projects, { title: '', description: '', status: 'IN_PROGRESS' }])
    setEditingIndex(projects.length)
  }
  const closeEditor = () => {
    editingToggle.current?.focus()
    setEditingIndex(null)
  }
  const remove = (index: number) => {
    onChange(projects.filter((_, current) => current !== index))
    setEditingIndex(null)
    addButton.current?.focus()
  }

  return <section className="settings-surface settings-optional settings-record-section settings-projects">
    <details>
      <summary>
        <span>
          <span className="settings-section-icon"><BriefcaseBusiness size={19} /></span>
          <strong>수행 실적</strong>
          <b className="settings-record-section__count">{projects.length}건</b>
        </span>
        <ChevronDown size={18} />
      </summary>
      <div className="settings-record-section__body">
        <div className="settings-record-section__toolbar">
          <div className="settings-record-section__totals" aria-label="수행 상태별 실적 수">
            {(Object.keys(statuses) as ProjectStatus[]).map(status => {
              const count = projects.filter(project => project.status === status).length
              return count > 0 && <span key={status} data-status={status}><i aria-hidden="true" />{statuses[status].label}<b>{count}</b></span>
            })}
            {projects.length === 0 && <p>프로젝트의 수행 내용과 성과를 등록하세요.</p>}
          </div>
          <button ref={addButton} type="button" className="settings-record-section__add" disabled={projects.length >= 20} onClick={add}>
            <Plus size={16} />실적 추가
          </button>
        </div>
        <div className="settings-record-section__grid">
          {projects.map((project, index) => {
            const editing = editingIndex === index
            const StatusIcon = statuses[project.status].icon
            const titleId = `${sectionId}-title-${index}`
            const editorId = `${sectionId}-editor-${index}`
            const name = project.title.trim() || '새 수행 실적'
            return <article key={index} className={`settings-record-card${editing ? ' is-editing' : ''}`} data-status={project.status} aria-labelledby={titleId}>
              <div className="settings-record-card__top">
                <span className="settings-record-status"><StatusIcon size={14} aria-hidden="true" />{statuses[project.status].label}</span>
                <button ref={editing ? editingToggle : undefined} type="button" className="settings-record-card__edit" aria-label={`${name} ${editing ? '편집 접기' : '수정'}`}
                  aria-expanded={editing} aria-controls={editorId} onClick={() => editing ? closeEditor() : setEditingIndex(index)}>
                  {editing ? <ChevronUp size={15} /> : <Pencil size={14} />}{editing ? '접기' : '수정'}
                </button>
              </div>
              <h3 id={titleId}>{name}</h3>
              {editing ? <div id={editorId} className="settings-record-card__editor">
                <div className="settings-form-grid">
                  <label>프로젝트명 <span className="settings-required">*</span>
                    <input ref={titleInput} value={project.title} maxLength={150} placeholder="프로젝트명을 입력해 주세요"
                      onChange={event => update(index, { title: event.target.value })} />
                  </label>
                  <label>수행 상태
                    <select value={project.status} onChange={event => update(index, { status: event.target.value as ProjectStatus })}>
                      <option value="IN_PROGRESS">진행 중</option><option value="COMPLETED">완료</option><option value="PLANNED">계획 단계</option>
                    </select>
                  </label>
                  <label className="settings-form-wide">수행 내용
                    <textarea value={project.description} rows={3} maxLength={1000} placeholder="수행한 일과 성과를 적어 주세요."
                      onChange={event => update(index, { description: event.target.value })} />
                  </label>
                </div>
                <div className="settings-record-card__footer">
                  <button type="button" className="settings-record-card__delete" onClick={() => remove(index)}><Trash2 size={15} />실적 삭제</button>
                  <button type="button" className="settings-edit-button" onClick={closeEditor}><Check size={15} />입력 접기</button>
                </div>
              </div> : <p className={`settings-record-card__description${!project.description.trim() ? ' is-empty' : ''}`}>
                {project.description.trim() || '수정 버튼을 눌러 수행 내용과 성과를 추가하세요.'}
              </p>}
            </article>
          })}
        </div>
      </div>
    </details>
  </section>
}
