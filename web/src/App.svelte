<script lang="ts">
  import { onMount } from 'svelte'
  import { confirmed, initTelegram } from './tg'
  import { unsaved } from './unsaved.svelte'
  import Icon from './ui/Icon.svelte'
  import { t } from './i18n'
  import type { Group } from './api'
  import BackButton from './ui/BackButton.svelte'
  import Groups from './views/Groups.svelte'
  import Editor from './views/Editor.svelte'
  import Submissions from './views/Submissions.svelte'
  import Settings from './views/Settings.svelte'

  type Tab = 'form' | 'subs' | 'settings'
  // In-memory navigation: the server has no SPA fallback, only `/` is loaded.
  let group = $state<Group | null>(null)
  let tab = $state<Tab>('form')
  const tabs: [Tab, string][] = [['form', t.form], ['subs', t.submissions], ['settings', t.settings]]
  onMount(initTelegram)
  // Telegram's own menu can't take a Reload item, so the page carries one
  async function refresh() {
    if (unsaved.form && !(await confirmed(t.discardChanges))) return
    location.reload()
  }
</script>

<main class="mx-auto max-w-xl px-3 pt-2 pb-28">
  <button class="btn btn-ghost btn-circle btn-sm float-right mt-0.5 text-info" aria-label={t.refresh} title={t.refresh} onclick={refresh}><Icon name="refresh" size={19} /></button>
  {#if !group}
    <Groups onopen={(g) => { group = g; tab = 'form' }} />
  {:else}
    <BackButton onclick={() => (group = null)} />
    <h1 class="truncate px-1 pt-1 text-lg font-semibold">{group.title}</h1>
    <div role="tablist" class="tabs tabs-box tabs-sm my-2 grid grid-cols-3 bg-neutral/15 p-0.5">
      {#each tabs as [k, label]}
        <button role="tab" class="tab" class:tab-active={tab === k} aria-selected={tab === k} onclick={() => (tab = k)}>{label}</button>
      {/each}
    </div>
    {#key group.id}
      {#if tab === 'form'}<Editor bind:group />
      {:else if tab === 'subs'}<Submissions group={group} />
      {:else}<Settings bind:group />{/if}
    {/key}
  {/if}
</main>
