import { useCallback, useEffect, useRef, useState } from 'react'

export function useToast() {
  const [message, setMessage] = useState('')
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const showToast = useCallback((next: string) => {
    clearTimeout(timer.current)
    setMessage(next)
    // Reset on every notification, including a repeated message that is still visible.
    if (next) timer.current = setTimeout(() => setMessage(''), 3000)
  }, [])

  useEffect(() => () => clearTimeout(timer.current), [])
  return [message, showToast] as const
}
