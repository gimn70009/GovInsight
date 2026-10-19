import type { DocumentAnalysisStatus, DocumentDetail } from '../api/types'

export function analysisStatusPresentation(status: DocumentAnalysisStatus) {
  switch (status) {
    case 'FAILED': return { label: '분석 실패', tone: 'danger' }
    case 'COMPLETED': return { label: '분석 완료', tone: 'success' }
    case 'PENDING': return { label: '분석 중', tone: 'neutral' }
    default: return { label: '확인 필요', tone: 'neutral' }
  }
}

export function missingAnalysisNotice(detail: Pick<DocumentDetail, 'analysisStatus' | 'analysisPending' | 'analysisFailureReason'>) {
  const pending = detail.analysisStatus === 'PENDING' || (!detail.analysisStatus && detail.analysisPending)
  return {
    pending,
    title: pending ? 'AI 분석을 준비하고 있어요' : '분석 실패',
    description: pending
      ? '분석이 완료되면 이 화면이 자동으로 갱신됩니다.'
      : detail.analysisFailureReason || '분석을 완료하지 못했어요. 원문을 확인하거나 다시 모니터링해 주세요.',
  }
}
