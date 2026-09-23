import type { StrategyCapabilityMatch } from '../api/types'

export function companyCapabilities(items?: StrategyCapabilityMatch[] | null): StrategyCapabilityMatch[] {
  return (items ?? []).filter((item) =>
    /^(business|service|technology|case):[1-9]\d*$/u.test(item.companyEvidenceId ?? '')
    && Boolean(item.confirmedFact?.trim())
    && Boolean(item.strategicInterpretation?.trim()),
  )
}
