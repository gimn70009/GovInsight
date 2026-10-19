export type SettingsPreset = 'FIT' | 'GROWTH' | 'FEASIBILITY'
export type CompanyType = 'UNKNOWN' | 'SME' | 'MID_SIZED' | 'LARGE' | 'SOLE_PROPRIETOR' | 'RESEARCH' | 'NONPROFIT'
export type ProjectStatus = 'COMPLETED' | 'IN_PROGRESS' | 'PLANNED'
export type CertificationStatus = 'HELD' | 'NEEDS_VERIFICATION' | 'EXPIRED'
export type CatalogKey = 'industries' | 'technologies' | 'roles' | 'businessTypes' | 'goals'

export interface RegisteredCompany {
  name: string
  type: CompanyType
  location: string
  establishedOn: string | null
  description: string
  industries: string[]
  technologies: string[]
  projects: { title: string; description: string; status: ProjectStatus }[]
  certifications: { name: string; expiresOn: string | null; status: CertificationStatus }[]
}

export interface EvaluationCriteria {
  industries: string[]
  tasks: string[]
  roles: string[]
  businessTypes: string[]
  goals: string[]
  preparationDays: number | null
  participationMode: 'ANY' | 'SOLO' | 'CONSORTIUM'
  urgencyDays: number
  preset: SettingsPreset
}

export interface EvaluationSettingsData {
  company: RegisteredCompany
  criteria: EvaluationCriteria
}

export interface SelectionGroup { name: string; options: string[] }
export interface EvaluationSettings {
  revision: number
  data: EvaluationSettingsData
  catalog: Record<CatalogKey, SelectionGroup[]>
  updatedAt: string | null
}

export interface EvaluationSettingsPayload { revision: number; data: EvaluationSettingsData }
