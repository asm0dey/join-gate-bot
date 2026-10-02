type Btn = {
  setText(t: string): void; show(): void; hide(): void; enable(): void; disable(): void
  onClick(cb: () => void): void; offClick(cb: () => void): void
  showProgress(leaveActive?: boolean): void; hideProgress(): void
}
export type Tg = {
  initData: string
  initDataUnsafe: { user?: { language_code?: string } }
  colorScheme: 'light' | 'dark'
  ready(): void
  expand(): void
  MainButton: Btn
  BackButton: { show(): void; hide(): void; onClick(cb: () => void): void; offClick(cb: () => void): void }
  HapticFeedback: { notificationOccurred(t: 'success' | 'error' | 'warning'): void }
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

export function haptic(kind: 'success' | 'error') {
  tg?.HapticFeedback.notificationOccurred(kind)
}

export function backButton(onBack: () => void): (() => void) | undefined {
  if (!tg) return
  tg.BackButton.onClick(onBack); tg.BackButton.show()
  return () => { tg!.BackButton.offClick(onBack); tg!.BackButton.hide() }
}
