import brandMark from '../assets/govinsight-mark.svg'

export default function Brand({ className = '' }: { className?: string }) {
  return (
    <div className={`brand ${className}`.trim()} role="img" aria-label="GovInsight · Public Notice Intelligence">
      <img className="brand__mark" src={brandMark} alt="" width="54" height="54" />
      <span className="brand__type" aria-hidden="true">
        <span className="brand__name">Gov<span>Insight</span></span>
        <span className="brand__tagline">PUBLIC NOTICE INTELLIGENCE</span>
      </span>
    </div>
  )
}
