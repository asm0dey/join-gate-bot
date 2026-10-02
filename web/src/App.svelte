<script lang="ts">
  import { onMount } from 'svelte'
  import { initTelegram } from './tg'
  import { t } from './i18n'
  import type { Group } from './api'
  import Groups from './views/Groups.svelte'
  import Editor from './views/Editor.svelte'
  import Submissions from './views/Submissions.svelte'
  import Settings from './views/Settings.svelte'

  type Tab = 'form' | 'subs' | 'settings'
  // In-memory navigation: the server has no SPA fallback, only `/` is loaded.
  let group = $state<Group | null>(null)
  let tab = $state<Tab>('form')
  onMount(initTelegram)
</script>

<main class="mx-auto max-w-xl p-3 pb-16">
  {#if !group}
    <Groups onopen={(g) => { group = g; tab = 'form' }} />
  {:else}
    <header class="mb-3 flex items-center gap-2">
      <button class="btn btn-sm btn-ghost" onclick={() => (group = null)} aria-label={t.back}>←</button>
      <h1 class="truncate text-lg font-semibold">{group.title}</h1>
    </header>
    <div role="tablist" class="tabs tabs-box mb-3">
      {#each [['form', t.form], ['subs', t.submissions], ['settings', t.settings]] as [k, label]}
        <button role="tab" class="tab flex-1" class:tab-active={tab === k} onclick={() => (tab = k as Tab)}>{label}</button>
      {/each}
    </div>
    {#key group.id}
      {#if tab === 'form'}<Editor group={group} />
      {:else if tab === 'subs'}<Submissions group={group} />
      {:else}<Settings group={group} />{/if}
    {/key}
  {/if}
</main>
