<script lang="ts">
  import { onMount } from 'svelte'
  import { api, ApiError, type Group } from '../api'
  import { t } from '../i18n'
  import Avatar from '../ui/Avatar.svelte'
  import Icon from '../ui/Icon.svelte'
  let { onopen }: { onopen: (g: Group) => void } = $props()
  let groups = $state<Group[] | null>(null)
  let err = $state('')
  async function load() {
    err = ''
    try { groups = await api.groups() } catch (e) { err = e instanceof ApiError && e.status === 401 ? t.noAuth : t.loadFail }
  }
  onMount(load)
  const status = (g: Group) => (!g.active ? t.inactive : g.hasForm ? t.formLive : t.noFormYet)
</script>

<div class="cap">{t.yourGroups}</div>
{#if err}
  <div class="rounded-box bg-base-100 px-4 py-3">{err}</div>
  {#if err !== t.noAuth}<button class="btn btn-ghost btn-sm mt-1 text-info" onclick={load}>{t.retry}</button>{/if}
{:else if !groups}
  <div class="flex justify-center py-6"><span class="loading loading-spinner text-primary"></span></div>
{:else if groups.length === 0}
  <div class="rounded-box bg-base-100 px-4 py-3">{t.noGroups}</div>
{:else}
  <div class="list rounded-box bg-base-100">
    {#each groups as g (g.id)}
      <button class="list-row items-center py-2.5 text-left active:bg-base-200" onclick={() => onopen(g)}>
        <Avatar id={g.id} name={g.title} />
        <div class="min-w-0">
          <div class="truncate">{g.title}</div>
          <div class="truncate text-[13px] text-hint">{status(g)}</div>
        </div>
        <span class="text-hint"><Icon name="chevron" size={16} /></span>
      </button>
    {/each}
  </div>
{/if}
<p class="foot">{t.missingGroup}</p>
