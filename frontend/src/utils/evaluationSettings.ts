import type { EvaluationSettingsData, SelectionGroup, SettingsPreset } from '../api/evaluationSettings'

export const settingsPresets: { id: SettingsPreset; label: string; description: string; weights: number[] }[] = [
  { id: 'FIT', label: '적합도 중심', description: '우리 회사와 잘 맞는 사업', weights: [50, 20, 20, 10] },
  { id: 'GROWTH', label: '사업 성장 중심', description: '사업 가치와 성장 기회', weights: [40, 35, 20, 5] },
  { id: 'FEASIBILITY', label: '실행 가능성 중심', description: '실제로 참여할 수 있는 사업', weights: [40, 15, 40, 5] },
]

const aliases: Record<string, string> = {
  'AI 에이전트': '인공지능 ai agent 에이전트', '생성형 AI': '생성형 인공지능 generative ai',
  '대규모 언어모델': 'llm 언어 모델', '머신러닝': '기계학습 machine learning',
  '컴퓨터 비전': '영상 인식 computer vision', '자연어 처리': 'nlp',
  '예지보전': '예측 정비 예방 정비 predictive maintenance', 'IoT·센서': '사물인터넷',
  '디지털 트윈': 'digital twin', 'MES·생산관리': '생산 관리 mes',
  'ERP·경영관리': '전사적 자원 관리 erp', '반도체': 'semiconductor',
}

export function filterSettingsOptions(groups: SelectionGroup[], category: string, query: string): string[] {
  const needle = query.trim().toLocaleLowerCase().replace(/\s+/g, '')
  return groups.filter(group => needle || group.name === category)
    .flatMap(group => group.options)
    .filter(option => !needle || `${option}${aliases[option] ?? ''}`.toLocaleLowerCase().replace(/\s+/g, '').includes(needle))
}

export function validateEvaluationSettings(data: EvaluationSettingsData): string {
  if (!data.company.name.trim()) return '회사명을 입력해 주세요.'
  if (data.company.establishedOn) {
    const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
    if (data.company.establishedOn > today) return '설립일은 오늘 이후로 설정할 수 없습니다.'
  }
  const selections = [data.company.industries, data.company.technologies, data.criteria.industries,
    data.criteria.tasks, data.criteria.roles, data.criteria.businessTypes, data.criteria.goals]
  if (selections.some(values => values.length === 0)) return '각 선택 항목을 1개 이상 등록해 주세요.'
  if (data.criteria.goals.length > 3) return '기대 성과는 최대 3개까지 선택해 주세요.'
  if (data.company.projects.some(project => !project.title.trim())) return '추가한 수행 실적의 프로젝트명을 입력해 주세요.'
  if (data.company.certifications.some(cert => !cert.name.trim())) return '추가한 인증·자격의 이름을 입력해 주세요.'
  return ''
}
