<script lang="ts">
  import { onMount, tick } from 'svelte'
  import { api, ApiError, type Group } from '../api'
  import { addField, errorsByField, moveField, multiRange, normalize, setMultiMin, removeField, updateField, validate, type Field, type Form } from '../editor'
  import { t } from '../i18n'
  import { haptic } from '../tg'
  import { unsaved } from '../unsaved.svelte'
  import BackButton from '../ui/BackButton.svelte'
  import Icon, { type IconName } from '../ui/Icon.svelte'
  import MainButton from '../ui/MainButton.svelte'
  import Preview from './Preview.svelte'
  let { group = $bindable() }: { group: Group } = $props()

  let form = $state<Form | null>(null)
  let version = $state(0)
  let checked = $state(false) // errors show once a save was tried, then follow every edit
  let serverErrors = $state<string[]>([])
  let conflict = $state(false)
  let msg = $state('')
  let busy = $state(false)
  let done = $state(false)
  let sheet = $state(false)
  let saved = $state('') // the form as last loaded or saved, to tell unsaved edits
  $effect(() => { unsaved.form = form !== null && JSON.stringify(form) !== saved })
  $effect(() => () => { unsaved.form = false })
  const errs = $derived(errorsByField([...(checked && form ? validate(form) : []), ...serverErrors]))

  async function load() {
    conflict = false; checked = false; serverErrors = []; msg = ''
    try {
      const d = await api.form(group.id)
      form = d.schema ? normalize(d.schema) : { welcome: '', fields: [] }
      version = d.version
      saved = JSON.stringify(form)
    } catch { msg = t.loadFail }
  }
  onMount(load)

  const patch = (i: number, p: Partial<Field>) => { form = updateField(form!, i, p); serverErrors = [] }
  const num = (e: Event) => { const v = (e.currentTarget as HTMLInputElement).value; return v === '' ? undefined : Number(v) }
  const setOpt = (i: number, f: Field & { options: string[] }, k: number, v: string) =>
    patch(i, { options: f.options.map((o, n) => (n === k ? v : o)) } as Partial<Field>)
  const move = (i: number, d: -1 | 1) => { haptic.tick(); form = moveField(form!, i, d) }

  async function add(kind: Field['type'] | 'yesno') {
    form = addField(form!, kind); sheet = false
    await tick()
    document.getElementById(`q-${form.fields.at(-1)!.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }

  async function save() {
    msg = ''; conflict = false; serverErrors = []; checked = true
    if (validate(form!).length) { haptic.error(); return }
    busy = true
    try {
      version = (await api.saveForm(group.id, form!, version)).version
      group.hasForm = true; checked = false; saved = JSON.stringify(form)
      haptic.ok(); done = true; setTimeout(() => (done = false), 1500)
    } catch (e) {
      haptic.error()
      if (e instanceof ApiError && e.status === 409) conflict = true
      else if (e instanceof ApiError && e.status === 400 && e.body?.errors) serverErrors = e.body.errors
      else msg = t.saveFail
    } finally { busy = false }
  }

  const kinds: [Field['type'] | 'yesno', IconName, string, string][] = [
    ['radio', 'radio', t.radio, t.radioD], ['yesno', 'yesno', t.yesno, t.yesnoD], ['multi', 'multi', t.multi, t.multiD],
    ['text', 'text', t.text, t.textD], ['int', 'int', t.int, t.intD], ['link', 'link', t.link, t.linkD], ['consent', 'consent', t.consent, t.consentD],
  ]
  const kindColor: Record<string, string> = { radio: '#65aadd', yesno: '#7bc862', multi: '#a695e7', text: '#faa774', int: '#e17076', link: '#6ec9cb', consent: '#2e9e4f' }
  /** validate()'s and the server's messages, in the admin's words */
  function human(f: Field | null, m: string): string {
    if (m === 'empty prompt') return t.errEmptyPrompt
    if (m === 'empty') return t.errWelcome
    if (m === 'needs options' || m.startsWith('option label')) return t.errOptions
    if (m === 'min > max') return f?.type === 'multi' ? t.errMultiRange : t.errRange
    if (m === 'min exceeds option count') return t.errMinOptions
    return m
  }
  const has = (id: string, m: string) => errs.get(id)?.some((e) => e.startsWith(m)) ?? false
</script>

{#snippet toggleRow(label: string, value: boolean, set: (v: boolean) => void)}
  <label class="flex min-h-11 items-center gap-3 px-3.5">
    <span class="grow">{label}</span>
    <input type="checkbox" class="toggle border-0 bg-neutral/35 text-white checked:bg-primary checked:text-white" checked={value} onchange={(e) => { haptic.tick(); set(e.currentTarget.checked) }} />
  </label>
{/snippet}

{#snippet numberRow(label: string, value: number | undefined, bad: boolean, set: (e: Event) => void, suffix = '')}
  <label class="flex min-h-11 items-center gap-2 px-3.5">
    <span class="grow">{label}</span>
    <input type="number" inputmode="numeric" class="input input-sm w-24 border-0 bg-base-200 text-right tabular-nums" class:input-error={bad} class:border={bad} value={value ?? ''} oninput={set} />
    {#if suffix}<span class="text-hint">{suffix}</span>{/if}
  </label>
{/snippet}

{#snippet stepperRow(label: string, value: number, lo: number, hi: number, set: (v: number) => void)}
  <div class="flex min-h-11 items-center gap-3 px-3.5">
    <span class="grow">{label}</span>
    <div class="join" role="group" aria-label={label}>
      <button class="btn btn-sm join-item border-0 bg-base-200 text-lg text-info disabled:bg-base-200 disabled:text-hint/40" aria-label={t.fewer} disabled={value <= lo} onclick={() => { haptic.tick(); set(value - 1) }}>−</button>
      <output class="join-item flex min-w-9 items-center justify-center border-x border-base-300 bg-base-200 font-semibold tabular-nums">{value}</output>
      <button class="btn btn-sm join-item border-0 bg-base-200 text-lg text-info disabled:bg-base-200 disabled:text-hint/40" aria-label={t.more} disabled={value >= hi} onclick={() => { haptic.tick(); set(value + 1) }}>+</button>
    </div>
  </div>
{/snippet}

{#snippet fieldErrors(f: Field | null, id: string)}
  {#each errs.get(id) ?? [] as m}<p class="px-4 pt-1 text-[13px] text-error">{human(f, m)}</p>{/each}
{/snippet}

{#if !form}
  {#if msg}
    <div class="mt-3 rounded-box bg-base-100 px-4 py-3">{msg}</div>
    <button class="btn btn-ghost btn-sm mt-1 text-info" onclick={load}>{t.retry}</button>
  {:else}<div class="flex justify-center py-6"><span class="loading loading-spinner text-primary"></span></div>{/if}
{:else}
  <div class="cap">{t.welcome}</div>
  <div class="rounded-box bg-base-100">
    <textarea class="textarea textarea-ghost min-h-0 w-full resize-none rounded-box text-[15px] focus:outline-none" class:shadow-[inset_3px_0_0_var(--color-error)]={errs.has('welcome')}
      rows="2" placeholder={t.welcomePh} aria-label={t.welcome} bind:value={form.welcome}></textarea>
  </div>
  {@render fieldErrors(null, 'welcome')}
  <div class="mt-2"><Preview welcome={form.welcome} /></div>

  {#each form.fields as f, i (f.id)}
    {@const kind = kinds.find(([k]) => k === f.type)!}
    <div class="cap" id="q-{f.id}">{t.question} {i + 1}</div>
    <div class="divide-y divide-base-300 overflow-hidden rounded-box bg-base-100">
      <div class="flex items-center gap-1 py-1 pr-1.5 pl-3.5">
        <span class="flex grow items-center gap-1.5 text-[13px] text-hint"><Icon name={kind[1]} size={15} />{kind[2]}</span>
        <button class="btn btn-ghost btn-square btn-sm text-info" aria-label={t.up} disabled={i === 0} onclick={() => move(i, -1)}><Icon name="up" size={19} /></button>
        <button class="btn btn-ghost btn-square btn-sm text-info" aria-label={t.down} disabled={i === form.fields.length - 1} onclick={() => move(i, 1)}><Icon name="down" size={19} /></button>
        <button class="btn btn-ghost btn-square btn-sm text-error" aria-label={t.del} onclick={() => { haptic.tick(); form = removeField(form!, i) }}><Icon name="trash" size={19} /></button>
      </div>
      <textarea class="textarea textarea-ghost min-h-0 w-full resize-none rounded-none text-[15px] focus:outline-none" class:shadow-[inset_3px_0_0_var(--color-error)]={has(f.id, 'empty prompt') || has(f.id, 'prompt')}
        rows="1" placeholder={t.questionPh} aria-label={t.question} value={f.prompt} oninput={(e) => patch(i, { prompt: e.currentTarget.value })}></textarea>

      {#if f.type === 'radio' || f.type === 'multi'}
        {#each f.options as o, k}
          <div class="flex min-h-11 items-center gap-2.5 pr-1 pl-3.5">
            <span class="size-4 flex-none border-2 border-hint/60" class:rounded-full={f.type === 'radio'} class:rounded={f.type === 'multi'}></span>
            <input class="input input-ghost input-sm grow px-1 text-[15px] focus:outline-none" placeholder={t.optionPh} value={o} oninput={(e) => setOpt(i, f, k, e.currentTarget.value)} />
            <button class="btn btn-ghost btn-square btn-sm text-hint" aria-label={t.delOption} onclick={() => patch(i, { options: f.options.filter((_, n) => n !== k) } as Partial<Field>)}><Icon name="trash" size={16} /></button>
          </div>
        {/each}
        <button class="flex min-h-11 w-full items-center gap-2.5 px-3.5 text-info active:bg-base-200" onclick={() => patch(i, { options: [...f.options, ''] } as Partial<Field>)}><Icon name="plus" size={18} />{t.addOption}</button>
      {/if}
      {#if f.type === 'radio'}{@render toggleRow(t.other, f.other, (v) => patch(i, { other: v }))}{/if}
      {#if f.type === 'multi'}
        {@const r = multiRange(f)}
        {@render stepperRow(t.atLeast, r.min, r.minLo, r.minHi, (v) => patch(i, setMultiMin(f, v)))}
        {@render stepperRow(t.atMost, r.max, r.maxLo, r.maxHi, (v) => patch(i, { max: v }))}
      {/if}
      {#if f.type === 'int'}
        {@render numberRow(t.smallest, f.min, has(f.id, 'min'), (e) => patch(i, { min: num(e) }))}
        {@render numberRow(t.largest, f.max, has(f.id, 'min'), (e) => patch(i, { max: num(e) }))}
      {/if}
      {#if f.type === 'text'}{@render numberRow(t.longest, f.maxLen, has(f.id, 'maxLen'), (e) => patch(i, { maxLen: num(e) ?? 0 }), t.chars)}{/if}
      {#if f.type !== 'consent' && f.type !== 'multi'}{@render toggleRow(t.required, f.required, (v) => patch(i, { required: v }))}{/if}
      <details class="group">
        <summary class="flex min-h-11 cursor-pointer list-none items-center px-3.5 text-info">{t.howItLooks}<span class="ml-auto text-hint transition group-open:rotate-90"><Icon name="chevron" size={16} /></span></summary>
        <div class="px-2.5 pb-2.5"><Preview field={f} /></div>
      </details>
    </div>
    {#if f.type === 'multi'}{@const r = multiRange(f)}<p class="foot">{r.min === 0 ? t.multiOptional : t.pickRange(r.min, r.max, f.options.length)}</p>{/if}
    {#if f.type === 'int'}<p class="foot">{t.noLimit}</p>{/if}
    {#if f.type === 'consent'}<p class="foot">{t.consentAlways}</p>{/if}
    {@render fieldErrors(f, f.id)}
  {/each}

  <div class="mt-4 rounded-box bg-base-100">
    <button class="flex min-h-11 w-full items-center gap-2.5 rounded-box px-3.5 text-info active:bg-base-200" onclick={() => (sheet = true)}><Icon name="plus" size={20} />{t.addField}</button>
  </div>

  {#if errs.has('form')}
    <div class="cap text-error">{t.errors}</div>
    <div class="rounded-box bg-base-100 px-4 py-3 text-error">{#each errs.get('form')! as m}<p>{m}</p>{/each}</div>
  {/if}
  {#if conflict}
    <div class="mt-4 rounded-box bg-base-100 px-4 py-3">{t.conflict}</div>
    <button class="btn btn-ghost btn-sm mt-1 text-info" onclick={load}>{t.reload}</button>
  {/if}
  {#if msg}<p class="foot text-error">{msg}</p>{/if}

  {#if sheet}<BackButton onclick={() => (sheet = false)} />{/if}
  <dialog class="modal modal-bottom" class:modal-open={sheet} aria-label={t.addField}>
    <div class="modal-box bg-base-200 px-3 pt-2 pb-[calc(1rem+env(safe-area-inset-bottom))]">
      <div class="sticky -top-2 z-10 -mx-3 mb-2 grid grid-cols-[2.5rem_1fr_2.5rem] items-center bg-base-200 px-3 pt-2 pb-1">
        <span></span>
        <h3 class="text-center text-[17px] font-semibold">{t.addField}</h3>
        <button class="btn btn-ghost btn-circle btn-sm bg-neutral/15 text-hint" aria-label={t.close} onclick={() => (sheet = false)}><Icon name="close" size={16} /></button>
      </div>
      <div class="list rounded-box bg-base-100">
        {#each kinds as [k, icon, label, hint]}
          <button class="list-row items-center py-2 text-left active:bg-base-200" onclick={() => add(k)}>
            <span class="grid size-7 place-items-center rounded-lg text-white" style="background:{kindColor[k]}"><Icon name={icon} size={17} /></span>
            <div class="min-w-0"><div>{label}</div><div class="truncate text-[13px] text-hint">{hint}</div></div>
          </button>
        {/each}
      </div>
    </div>
    <button class="modal-backdrop" aria-label={t.back} onclick={() => (sheet = false)}></button>
  </dialog>

  <!-- Telegram draws its button outside the page, so it would stay tappable under the sheet -->
  {#if !sheet}<MainButton text={t.saveForm} {busy} {done} onclick={save} />{/if}
{/if}

<svelte:window onkeydown={(e) => { if (sheet && e.key === 'Escape') sheet = false }} />
