<script lang="ts">
  import { onMount } from 'svelte'
  import { api, ApiError, STATUSES, type Group, type Status, type SubmissionDetail, type SubmissionRow } from '../api'
  import { t } from '../i18n'
  import { tg } from '../tg'
  let { group }: { group: Group } = $props()
  let status = $state<Status | ''>('')
  let rows = $state<SubmissionRow[] | null>(null)
  let detail = $state<SubmissionDetail | null>(null)
  let msg = $state('')
  let confirming = $state(false)

  async function load() {
    msg = ''
    try { rows = await api.submissions(group.id, status || undefined) } catch { msg = t.loadFail }
  }
  onMount(load)
  async function open(r: SubmissionRow) {
    try { detail = await api.submission(group.id, r.id) } catch { msg = t.loadFail }
  }
  async function del() {
    confirming = false
    try { await api.remove(group.id, detail!.row.id); detail = null; await load() } catch { msg = t.saveFail }
  }
  const ask = () => (tg ? tg.showConfirm(t.confirmDelete, (ok) => ok && del()) : (confirming = true))
  async function exportCsv() {
    try { await api.exportCsv(group.id); msg = t.exported }
    catch (e) { msg = e instanceof ApiError && e.status === 409 ? t.exportNoBot : t.saveFail }
  }
  const who = (r: SubmissionRow) => r.username ? `${r.name} @${r.username}` : r.name
  const date = (s: string) => new Date(s).toLocaleString()
</script>

{#if detail}
  <button class="btn btn-sm btn-ghost mb-2" onclick={() => (detail = null)}>← {t.back}</button>
  <div class="card bg-base-100 p-3">
    <div class="font-semibold">{who(detail.row)}</div>
    <div class="text-sm text-neutral">{t[detail.row.status]} · {date(detail.row.createdAt)}</div>
    {#if detail.partial}<div role="alert" class="alert alert-warning mt-2 py-2 text-sm">{t.partial}</div>{/if}
    {#if detail.answers}
      <dl class="mt-3 flex flex-col gap-2">
        {#each detail.answers as a}
          <div><dt class="text-xs text-neutral">{a.prompt}</dt><dd class="whitespace-pre-wrap break-words">{a.value || '—'}</dd></div>
        {/each}
      </dl>
    {:else}<p class="mt-3 text-neutral">{t.noAnswers}</p>{/if}
  </div>
  <button class="btn btn-error btn-outline mt-3" onclick={ask}>{t.deleteSub}</button>
  {#if confirming}
    <dialog class="modal modal-open">
      <div class="modal-box">
        <p>{t.confirmDelete}</p>
        <div class="modal-action">
          <button class="btn" onclick={() => (confirming = false)}>{t.cancel}</button>
          <button class="btn btn-error" onclick={del}>{t.del}</button>
        </div>
      </div>
    </dialog>
  {/if}
{:else}
  <div class="mb-3 flex gap-2">
    <select class="select select-sm flex-1" bind:value={status} onchange={load}>
      <option value="">{t.all}</option>
      {#each STATUSES as s}<option value={s}>{t[s]}</option>{/each}
    </select>
    <button class="btn btn-sm btn-primary" onclick={exportCsv}>{t.exportCsv}</button>
  </div>
  {#if rows === null}<span class="loading loading-spinner"></span>
  {:else if rows.length === 0}<p class="text-neutral">{t.empty}</p>
  {:else}
    <ul class="flex flex-col gap-2">
      {#each rows as r (r.id)}
        <li><button class="card w-full bg-base-100 p-3 text-left" onclick={() => open(r)}>
          <span class="truncate font-medium">{who(r)}</span>
          <span class="text-xs text-neutral">{t[r.status]} · {date(r.createdAt)}</span>
        </button></li>
      {/each}
    </ul>
  {/if}
{/if}
{#if msg}<p class="mt-3 text-sm">{msg}</p>{/if}
