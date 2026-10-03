type Clickable = { show(): void; hide(): void; onClick(cb: () => void): void; offClick(cb: () => void): void }

export type Tg = {
  initData: string
  initDataUnsafe: { user?: { language_code?: string } }
  colorScheme: 'light' | 'dark'
  themeParams: { button_color?: string }
  ready(): void
  expand(): void
  onEvent(e: string, cb: () => void): void
  showConfirm(msg: string, cb: (ok: boolean) => void): void
  setHeaderColor(c: string): void
  setBackgroundColor(c: string): void
  isVersionAtLeast(v: string): boolean
  BackButton: Clickable
  MainButton: Clickable & {
    setParams(p: { text?: string; color?: string; text_color?: string; is_active?: boolean }): void
    showProgress(leaveActive?: boolean): void
    hideProgress(): void
  }
  HapticFeedback: {
    notificationOccurred(t: 'success' | 'error' | 'warning'): void
    selectionChanged(): void
  }
}

export const tg: Tg | undefined =
  typeof window !== 'undefined' && (window as any).Telegram?.WebApp?.initData
    ? (window as any).Telegram.WebApp
    : undefined

export function initTelegram() {
  if (!tg) return
  tg.ready()
  tg.expand()
  // the page sits on the grouped-list grey; Telegram's own bars match it
  if (tg.isVersionAtLeast('6.1')) { tg.setHeaderColor('secondary_bg_color'); tg.setBackgroundColor('secondary_bg_color') }
  const scheme = () => document.documentElement.setAttribute('data-scheme', tg!.colorScheme)
  scheme()
  tg.onEvent('themeChanged', scheme)
}

/** Haptics are a no-op outside Telegram and on clients older than 6.1. */
export const haptic = {
  ok: () => tg?.isVersionAtLeast('6.1') && tg.HapticFeedback.notificationOccurred('success'),
  error: () => tg?.isVersionAtLeast('6.1') && tg.HapticFeedback.notificationOccurred('error'),
  tick: () => tg?.isVersionAtLeast('6.1') && tg.HapticFeedback.selectionChanged(),
}

/** Telegram's confirm sheet; the browser's own outside Telegram (local dev). */
export function confirmed(msg: string): Promise<boolean> {
  return new Promise((done) => (tg ? tg.showConfirm(msg, done) : done(window.confirm(msg))))
}
