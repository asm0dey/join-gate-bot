<script lang="ts">
  import { api } from '../api'
  import { t } from '../i18n'
  import { haptic } from '../tg'
  import Icon from '../ui/Icon.svelte'
  import MainButton from '../ui/MainButton.svelte'
  let { group = $bindable() }: { group: Group } = $props()
  import type { Group } from '../api'
  // svelte-ignore state_referenced_locally -- App keys this view on group.id
  let days = $state(group.retentionDays)
  let msg = $state('')
  let busy = $state(false)
  let done = $state(false)
  const ok = $derived(Number.isInteger(days) && days >= 1 && days <= 3650)
  async function save() {
    busy = true; msg = ''
    try {
      await api.retention(group.id, days); group.retentionDays = days
      haptic.ok(); done = true; setTimeout(() => (done = false), 1500)
    } catch { haptic.error(); msg = t.saveFail } finally { busy = false }
  }
</script>

<div class="cap">{t.retentionCap}</div>
<div class="rounded-box bg-base-100">
  <label class="flex min-h-11 items-center gap-3 px-3.5">
    <span class="grid size-7 flex-none place-items-center rounded-lg bg-[#faa774] text-white"><Icon name="clock" size={17} /></span>
    <span class="grow truncate">{t.keepFor}</span>
    <input type="number" inputmode="numeric" min="1" max="3650" class="input input-sm w-16 border-0 bg-base-200 text-right tabular-nums" class:input-error={!ok} class:border={!ok} bind:value={days} />
    <span class="text-hint">{t.days}</span>
  </label>
</div>
<p class="foot">{t.retentionFoot}</p>
{#if msg}<p class="foot text-error">{msg}</p>{/if}
<MainButton text={t.save} disabled={!ok} {busy} {done} onclick={save} />
