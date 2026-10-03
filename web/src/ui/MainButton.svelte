<script lang="ts">
  import { tg } from '../tg'
  import { t } from '../i18n'
  import Icon from './Icon.svelte'
  let { text, onclick, disabled = false, busy = false, done = false }:
    { text: string; onclick: () => void; disabled?: boolean; busy?: boolean; done?: boolean } = $props()

  // Telegram's own bottom button; an in-page one outside Telegram (local dev)
  const b = tg?.MainButton
  const click = () => { if (!disabled && !busy) onclick() }
  // in an effect, not at init: a view switch tears the old one down first, so the new one stays shown
  $effect(() => {
    if (!b) return
    b.onClick(click); b.show()
    return () => { b.offClick(click); b.hide() }
  })
  $effect(() => {
    if (!b) return
    b.setParams({ text: done ? `✓ ${t.saved}` : text, color: done ? '#2e9e4f' : tg!.themeParams.button_color, is_active: !disabled })
    if (busy) b.showProgress(false); else b.hideProgress()
  })
</script>

{#if !b}
  <!-- same column as <main> in App.svelte, so it lines up with the page at any width -->
  <div class="fixed inset-x-0 bottom-0 bg-base-200 pt-2.5 pb-[calc(0.875rem+env(safe-area-inset-bottom))]"><div class="mx-auto max-w-xl px-3">
    <button class="btn btn-block {done ? 'btn-success' : 'btn-primary'}" {disabled} onclick={click}>
      {#if busy}<span class="loading loading-spinner loading-sm"></span>
      {:else if done}<Icon name="check" size={18} />{t.saved}
      {:else}{text}{/if}
    </button>
  </div></div>
{/if}
