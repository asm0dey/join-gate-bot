<script lang="ts" module>
  // One Telegram Back button for nested screens: the innermost mounted handler wins.
  const stack = $state<{ id: symbol; go: () => void }[]>([])
  const top = () => stack.at(-1)?.go()
  let bound = false
</script>

<script lang="ts">
  import { untrack } from 'svelte'
  import { tg } from '../tg'
  import { t } from '../i18n'
  import Icon from './Icon.svelte'
  let { onclick }: { onclick: () => void } = $props()
  const id = Symbol()

  $effect(() => {
    untrack(() => stack.push({ id, go: () => onclick() })) // writes only: reading the stack here would re-run this
    if (tg) {
      if (!bound) { tg.BackButton.onClick(top); bound = true }
      tg.BackButton.show()
    }
    return () => {
      untrack(() => {
        stack.splice(stack.findIndex((e) => e.id === id), 1)
        if (tg && stack.length === 0) tg.BackButton.hide()
      })
    }
  })
</script>

<!-- outside Telegram (local dev) the innermost screen draws its own -->
{#if !tg && stack.at(-1)?.id === id}
  <button class="btn btn-ghost btn-sm -ml-2 gap-0.5 px-2 font-normal text-info" {onclick}><Icon name="back" size={18} />{t.back}</button>
{/if}
