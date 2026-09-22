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

export default function ReportBody({ body }: { body: string }) {
  return <div className="report-body">{body.split(/\n\n─{3,}\n\n/).map((block, index) =>
    <section className={index ? 'report-body-card' : 'report-body-overview'} key={index}>
      {block.split('\n').map((line, i) => line.startsWith('▸ ')
        ? <h3 key={i}>{line.slice(2)}</h3>
        : <div className={line ? 'report-body-line' : 'report-body-space'} key={i}>{linkedText(line)}</div>)}
    </section>)}</div>
}
