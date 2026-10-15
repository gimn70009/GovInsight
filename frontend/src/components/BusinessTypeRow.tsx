import type { BusinessTypeCode, DocumentDetail } from '../api/types'

const labels: Record<BusinessTypeCode, string> = {
  RND: '연구개발(R&D)', DEMONSTRATION: '실증·시범', COMMERCIALIZATION: '제품화·사업화',
  ADOPTION: '설비·시스템 도입', CERTIFICATION: '시험·인증', MARKET: '판로·해외진출',
  FINANCE: '자금·금융', WORKFORCE: '인력·고용·교육', ADVISORY: '조사·컨설팅·전문지원', SPACE: '공간·입주',
}

export function BusinessTypeRow({ detail }: { detail: DocumentDetail }) {
  const type = detail.businessType
  const label = type?.status === 'CLASSIFIED' && type.code ? labels[type.code] : undefined
  const notApplicable = type?.status === 'NOT_APPLICABLE'
  const pending = !label && !notApplicable && detail.analysisPending

  return <tr className="business-summary__type-row">
    <th scope="row">사업 유형</th>
    <td>
      <strong className={label ? 'business-summary__type-label' : 'business-summary__type-status'}>
        {label || (notApplicable ? '해당 없음' : pending ? '분석 중' : '확인 필요')}
      </strong>
    </td>
  </tr>
}
