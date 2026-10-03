<script lang="ts">
  import { multiRange, type Field } from '../editor'
  import { t } from '../i18n'
  let { welcome, field }: { welcome?: string; field?: Field } = $props()
  // a multiple choice also says how many to pick (ApplicantFlow.question)
  const hint = $derived.by(() => {
    if (field?.type !== 'multi') return ''
    const { min, max } = multiRange(field)
    return min === max ? t.chooseExact(max) : min === 0 ? t.chooseUpTo(max) : t.chooseRange(min, max)
  })
  const text = $derived(welcome ?? (field ? (hint ? `${field.prompt}\n\n${hint}` : field.prompt) : ''))
  // the bot's inline keyboard: one button per row (ApplicantFlow.keyboard)
  const btns = $derived.by(() => {
    if (!field) return []
    // an optional multiple choice skips through Done (ApplicantFlow.keyboard)
    const skip = field.type !== 'consent' && field.type !== 'multi' && !field.required ? [t.skip] : []
    switch (field.type) {
      case 'radio': return [...field.options, ...(field.other ? [t.other_] : []), ...skip]
      case 'multi': return [...field.options, t.done, ...skip]
      case 'consent': return [t.agree, t.disagree]
      default: return skip
    }
  })
</script>

{#if text.trim() || btns.length}
  <div class="flex flex-col gap-1 rounded-box bg-primary/10 p-2.5">
    {#if text.trim()}
      <div class="chat chat-start p-0"><div class="chat-bubble min-h-0 bg-base-100 py-1.5 text-[14px] whitespace-pre-wrap text-base-content">{text}</div></div>
    {/if}
    {#each btns as b}
      <div class="w-[88%] truncate rounded-field bg-base-100/60 px-2 py-1.5 text-center text-[13px]">{b}</div>
    {/each}
  </div>
{/if}
