<script lang="ts">
  import type { Field } from '../editor'
  import { t } from '../i18n'
  let { welcome, field }: { welcome?: string; field?: Field } = $props()
  const btns = $derived.by(() => {
    if (!field) return []
    const skip = field.type !== 'consent' && !field.required ? [t.skip] : []
    switch (field.type) {
      case 'radio': return [...field.options, ...(field.other ? [t.other_] : []), ...skip]
      case 'multi': return [...field.options, t.done, ...skip]
      case 'consent': return [t.agree, t.disagree]
      default: return skip
    }
  })
</script>

<!-- Approximates how the bot renders the message in chat -->
<div class="rounded-box bg-base-200 p-2">
  <div class="chat-bubble max-w-full whitespace-pre-wrap bg-base-100 text-base-content">{welcome ?? field?.prompt}</div>
  {#if btns.length}
    <div class="mt-1 grid grid-cols-2 gap-1">
      {#each btns as b}<div class="btn btn-sm btn-neutral pointer-events-none truncate">{b}</div>{/each}
    </div>
  {/if}
</div>
