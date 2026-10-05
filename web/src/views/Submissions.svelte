<script lang="ts">
  import { onMount } from 'svelte'
  import { api, ApiError, STATUSES, type Group, type Kind, type Status, type SubmissionDetail, type SubmissionRow } from '../api'
  import { t } from '../i18n'
  import { confirmed, haptic } from '../tg'
  import Avatar from '../ui/Avatar.svelte'
  import BackButton from '../ui/BackButton.svelte'
  import MainButton from '../ui/MainButton.svelte'
  let { group }: { group: Group } = $props()
  let status = $state<Status | ''>('')
  let rows = $state<SubmissionRow[] | null>(null)
  let detail = $state<SubmissionDetail | null>(null)
  let msg = $state('')
  let busy = $state(false)

  async function load() {
    msg = ''
    try { rows = await api.submissions(group.id, status || undefined) } catch { msg = t.loadFail }
  }
  onMount(load)
  const filter = (s: Status | '') => { if (s !== status) { haptic.tick(); status = s; rows = null; load() } }
  async function open(r: SubmissionRow) {
    try { detail = await api.submission(group.id, r.id) } catch { msg = t.loadFail }
  }
  async function del() {
    if (!(await confirmed(t.confirmDelete))) return
    try { await api.remove(group.id, detail!.row.id); haptic.ok(); detail = null; await load() } catch { haptic.error(); msg = t.saveFail }
  }
  async function exportCsv() {
    busy = true; msg = ''
    try { await api.exportCsv(group.id); haptic.ok(); msg = t.exported }
    catch (e) { haptic.error(); msg = e instanceof ApiError && e.status === 409 ? t.exportNoBot : t.saveFail }
    finally { busy = false }
  }
  const date = (s: string) => new Date(s).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
  const badge: Record<Status, string> = { PENDING: 'badge-info', APPROVED: 'badge-success', REJECTED: 'badge-error', WITHDRAWN: 'badge-ghost', EXPIRED: 'badge-ghost', REMOVED: 'badge-ghost' }
</script>

{#snippet pill(s: Status, k: Kind)}<span class="flex flex-none gap-1"><span class="badge badge-soft badge-sm badge-neutral font-semibold">{t[k]}</span><span class="badge badge-soft badge-sm font-semibold {badge[s]}">{t[s]}</span></span>{/snippet}

{#if detail}
  {@const r = detail.row}
  <div class="fixed inset-0 z-10 overflow-y-auto bg-base-200"><div class="mx-auto max-w-xl px-3 pt-2 pb-8">
  <BackButton onclick={() => (detail = null)} />
  <div class="flex flex-col items-center gap-1 pt-3 pb-2 text-center">
    <Avatar id={r.userId} name={r.name} size={72} />
    <div class="mt-1 text-[19px] font-semibold">{r.name}</div>
    <div class="text-hint">{r.username ? `@${r.username} · ` : ''}{date(r.createdAt)}</div>
    {@render pill(r.status, r.kind)}
  </div>
  {#if detail.partial}<div role="alert" class="alert alert-warning alert-soft mb-2 text-sm">{t.partial}</div>{/if}
  {#if detail.answers}
    <div class="list rounded-box bg-base-100">
      {#each detail.answers as a}
        <div class="list-row block py-2.5">
          <div class="text-[13px] text-hint">{a.prompt}</div>
          {#if a.value}<div class="break-words whitespace-pre-wrap">{a.value}</div>{:else}<div class="text-hint">{t.skipped}</div>{/if}
        </div>
      {/each}
    </div>
  {:else}<div class="rounded-box bg-base-100 px-4 py-3 text-hint">{t.noAnswers}</div>{/if}
  {#if r.status === 'PENDING'}<p class="foot">{t.decideInDm}</p>{/if}
  <div class="mt-4 rounded-box bg-base-100">
    <button class="flex min-h-11 w-full items-center rounded-box px-3.5 text-error active:bg-base-200" onclick={del}>{t.deleteSub}</button>
  </div>
  </div></div>
{:else}
  <div class="-mx-3 flex gap-1.5 overflow-x-auto px-3 py-1 [scrollbar-width:none]">
    {#each [['', t.all] as const, ...STATUSES.map((s) => [s, t[s]] as const)] as [s, label]}
      <button class="btn btn-sm flex-none rounded-full border-0 font-normal shadow-none {status === s ? 'btn-primary font-semibold' : 'bg-base-100'}" onclick={() => filter(s)}>{label}</button>
    {/each}
  </div>
  {#if rows === null}<div class="flex justify-center py-6"><span class="loading loading-spinner text-primary"></span></div>
  {:else if rows.length === 0}<div class="mt-2 rounded-box bg-base-100 px-4 py-3 text-hint">{t.empty}</div>
  {:else}
    <div class="list mt-2 rounded-box bg-base-100">
      {#each rows as r (r.id)}
        <button class="list-row items-center py-2.5 text-left active:bg-base-200" onclick={() => open(r)}>
          <Avatar id={r.userId} name={r.name} />
          <div class="min-w-0">
            <div class="truncate">{r.name}{#if r.username}<span class="ml-1 text-hint">@{r.username}</span>{/if}</div>
            <div class="truncate text-[13px] text-hint">{date(r.createdAt)}</div>
          </div>
          {@render pill(r.status, r.kind)}
        </button>
      {/each}
    </div>
  {/if}
  <MainButton text={t.exportCsv} {busy} onclick={exportCsv} />
{/if}
{#if msg}<p class="foot">{msg}</p>{/if}
