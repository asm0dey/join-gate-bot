export type Tg = {
  initData: string
  initDataUnsafe: { user?: { language_code?: string } }
  colorScheme: 'light' | 'dark'
  ready(): void
  expand(): void
  onEvent(e: string, cb: () => void): void
  showConfirm(msg: string, cb: (ok: boolean) => void): void
}

export const tg: Tg | undefined =
  typeof window !== 'undefined' && (window as any).Telegram?.WebApp?.initData
    ? (window as any).Telegram.WebApp
    : undefined

export function initTelegram() {
  if (!tg) return
  tg.ready()
  tg.expand()
  const scheme = () => document.documentElement.setAttribute('data-scheme', tg!.colorScheme)
  scheme()
  tg.onEvent('themeChanged', scheme)
}
