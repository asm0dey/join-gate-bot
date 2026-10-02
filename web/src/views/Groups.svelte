<script lang="ts">
  import { onMount } from 'svelte'
  import { api, ApiError, type Group } from '../api'
  import { t } from '../i18n'
  let { onopen }: { onopen: (g: Group) => void } = $props()
  let groups = $state<Group[] | null>(null)
  let err = $state('')
  async function load() {
    err = ''
    try { groups = await api.groups() } catch (e) { err = e instanceof ApiError && e.status === 401 ? t.noAuth : t.loadFail }
  }
  onMount(load)
</script>

<h1 class="mb-3 text-lg font-semibold">{t.groups}</h1>
{#if err}
  <div class="alert alert-error"><span>{err}</span><button class="btn btn-sm" onclick={load}>{t.retry}</button></div>
{:else if !groups}
  <span class="loading loading-spinner"></span>
{:else if groups.length === 0}
  <p class="text-neutral">{t.noGroups}</p>
{:else}
  <ul class="flex flex-col gap-2">
    {#each groups as g}
      <li><button class="btn btn-block justify-start bg-base-100" onclick={() => onopen(g)}>{g.title}{#if !g.active}<span class="badge badge-ghost badge-sm">{t.inactive}</span>{/if}</button></li>
    {/each}
  </ul>
{/if}
