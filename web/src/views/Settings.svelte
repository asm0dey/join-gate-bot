<script lang="ts">
  import { api } from '../api'
  import { t } from '../i18n'
  import type { Group } from '../api'
  let { group }: { group: Group } = $props()
  // svelte-ignore state_referenced_locally -- App keys this view on group.id
  let days = $state(group.retentionDays)
  let msg = $state('')
  const ok = $derived(Number.isInteger(days) && days >= 1 && days <= 3650)
  async function save() {
    try { await api.retention(group.id, days); group.retentionDays = days; msg = t.saved } catch { msg = t.saveFail }
  }
</script>

<label class="form-control">
  <span class="label">{t.retention}</span>
  <input type="number" min="1" max="3650" class="input w-full" bind:value={days} />
  <span class="label text-xs">{t.retentionHint}</span>
</label>
<button class="btn btn-primary mt-2" disabled={!ok} onclick={save}>{t.save}</button>
{#if msg}<p class="mt-2 text-sm">{msg}</p>{/if}
