import type { ReactNode } from 'react'
import './ReportBody.css'

function linkedText(text: string): ReactNode[] {
  const pattern = /\[([^\]\r\n[]+)\]\((https?:\/\/[^\s()<>"]+)\)/g
  const parts: ReactNode[] = []
  let end = 0
  for (const match of text.matchAll(pattern)) {
    parts.push(text.slice(end, match.index))
    let safe = false
    try {
      const url = new URL(match[2])
      safe = ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && !match[2].includes('\\')
    } catch { /* Keep invalid links as plain text. */ }
    parts.push(safe ? <a key={match.index} href={match[2]} target="_blank" rel="noopener noreferrer">{match[1]}</a> : match[0])
    end = match.index + match[0].length
  }
  parts.push(text.slice(end))
  return parts
}

function reportLines(block: string): ReactNode[] {
  const lines = block.split('\n')
  const rendered: ReactNode[] = []
  let submissionSection = false
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]
    if (line.startsWith('원문: ')) submissionSection = false
    if (line.startsWith('◆ ')) {
      const details: string[] = []
      const key = i
      while (i + 1 < lines.length && /^(제출 주체:|준비 담당\(권장\):|양식:|ZIP 내부 파일:)/.test(lines[i + 1])) {
        details.push(lines[++i])
      }
      rendered.push(<article className="report-submission-card" key={key}>
        <h4>{line.slice(2)}</h4>
        <dl>{details.map((detail, index) => {
          const colon = detail.indexOf(': ')
          return <div key={index}><dt>{detail.slice(0, colon)}</dt><dd>{linkedText(detail.slice(colon + 2))}</dd></div>
        })}</dl>
      </article>)
    } else if (submissionSection && line.startsWith('• ')) {
      rendered.push(<div className="report-submission-link" key={i}>{linkedText(line.slice(2))}</div>)
    } else if (line === '제출 준비 서류 ↓') {
      submissionSection = true
      rendered.push(<h4 className="report-submission-heading" key={i}>제출 준비 서류</h4>)
    } else if (line.startsWith('▸ ')) {
      rendered.push(<h3 key={i}>{line.slice(2)}</h3>)
    } else {
      rendered.push(<div className={line ? 'report-body-line' : 'report-body-space'} key={i}>{linkedText(line)}</div>)
    }
  }
  return rendered
}

export default function ReportBody({ body }: { body: string }) {
  return <div className="report-body">{body.split(/\n\n─{3,}\n\n/).map((block, index) =>
    <section className={index ? 'report-body-card' : 'report-body-overview'} key={index}>
      {reportLines(block)}
    </section>)}</div>
}
