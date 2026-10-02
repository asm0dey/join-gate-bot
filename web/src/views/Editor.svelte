<script lang="ts">
  import { onMount } from 'svelte'
  import { api, ApiError, type Group } from '../api'
  import { addField, moveField, normalize, removeField, updateField, validate, type Field, type Form } from '../editor'
  import { t } from '../i18n'
  import Preview from './Preview.svelte'
  let { group }: { group: Group } = $props()

  let form = $state<Form | null>(null)
  let version = $state(0)
  let errors = $state<string[]>([])
  let conflict = $state(false)
  let msg = $state('')
  let busy = $state(false)

  async function load() {
    conflict = false; errors = []; msg = ''
    try {
      const d = await api.form(group.id)
      form = d.schema ? normalize(d.schema) : { welcome: '', fields: [] }
      version = d.version
    } catch { msg = t.loadFail }
  }
  onMount(load)

  const patch = (i: number, p: Partial<Field>) => { form = updateField(form!, i, p) }
  const num = (e: Event) => { const v = (e.currentTarget as HTMLInputElement).value; return v === '' ? undefined : Number(v) }
  const setOpt = (i: number, f: Field & { options: string[] }, k: number, v: string) =>
    patch(i, { options: f.options.map((o, n) => (n === k ? v : o)) } as Partial<Field>)

  async function save() {
    msg = ''; conflict = false
    errors = validate(form!)
    if (errors.length) return
    busy = true
    try {
      version = (await api.saveForm(group.id, form!, version)).version
      group.hasForm = true; msg = t.saved
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) conflict = true
      else if (e instanceof ApiError && e.status === 400 && e.body?.errors) errors = e.body.errors
      else msg = t.saveFail
    } finally { busy = false }
  }
  const types = ['yesno', 'radio', 'multi', 'text', 'int', 'link', 'consent'] as const
</script>

{#if !form}
  {#if msg}<div class="alert alert-error"><span>{msg}</span><button class="btn btn-sm" onclick={load}>{t.retry}</button></div>
  {:else}<span class="loading loading-spinner"></span>{/if}
{:else}
  <label class="form-control mb-3">
    <span class="label">{t.welcome}</span>
    <textarea class="textarea w-full" rows="3" bind:value={form.welcome}></textarea>
  </label>
  <Preview welcome={form.welcome} />

  {#each form.fields as f, i (f.id)}
    <section class="card mt-3 bg-base-100 p-3">
      <div class="mb-2 flex items-center gap-1">
        <span class="badge badge-neutral">{t[f.type]}</span>
        <span class="flex-1 truncate text-xs text-neutral">{f.id}</span>
        <button class="btn btn-xs" aria-label={t.up} onclick={() => (form = moveField(form!, i, -1))}>↑</button>
        <button class="btn btn-xs" aria-label={t.down} onclick={() => (form = moveField(form!, i, 1))}>↓</button>
        <button class="btn btn-xs btn-error btn-outline" aria-label={t.del} onclick={() => (form = removeField(form!, i))}>✕</button>
      </div>
      <label class="form-control">
        <span class="label text-xs">{t.prompt}</span>
        <textarea class="textarea w-full" rows="2" value={f.prompt} oninput={(e) => patch(i, { prompt: e.currentTarget.value })}></textarea>
      </label>

      {#if f.type === 'radio' || f.type === 'multi'}
        <div class="label text-xs">{t.options}</div>
        {#each f.options as o, k}
          <div class="mb-1 flex gap-1">
            <input class="input input-sm flex-1" value={o} oninput={(e) => setOpt(i, f, k, e.currentTarget.value)} />
            <button class="btn btn-sm" aria-label={t.del} onclick={() => patch(i, { options: f.options.filter((_, n) => n !== k) } as Partial<Field>)}>✕</button>
          </div>
        {/each}
        <button class="btn btn-sm btn-ghost self-start" onclick={() => patch(i, { options: [...f.options, ''] } as Partial<Field>)}>+ {t.addOption}</button>
      {/if}
      {#if f.type === 'radio'}
        <label class="label cursor-pointer justify-start gap-2"><input type="checkbox" class="checkbox checkbox-sm" checked={f.other} onchange={(e) => patch(i, { other: e.currentTarget.checked })} />{t.other}</label>
      {/if}
      {#if f.type === 'multi' || f.type === 'int'}
        <div class="mt-1 grid grid-cols-2 gap-2">
          <label class="form-control"><span class="label text-xs">{f.type === 'multi' ? t.minSel : t.min}</span>
            <input type="number" class="input input-sm w-full" value={f.min ?? ''} oninput={(e) => patch(i, { min: num(e) })} /></label>
          <label class="form-control"><span class="label text-xs">{f.type === 'multi' ? t.maxSel : t.max}</span>
            <input type="number" class="input input-sm w-full" value={f.max ?? ''} oninput={(e) => patch(i, { max: num(e) })} /></label>
        </div>
      {/if}
      {#if f.type === 'text'}
        <label class="form-control"><span class="label text-xs">{t.maxLen}</span>
          <input type="number" class="input input-sm w-full" value={f.maxLen} oninput={(e) => patch(i, { maxLen: num(e) ?? 0 })} /></label>
      {/if}
      {#if f.type !== 'consent'}
        <label class="label cursor-pointer justify-start gap-2"><input type="checkbox" class="checkbox checkbox-sm" checked={f.required} onchange={(e) => patch(i, { required: e.currentTarget.checked })} />{t.required}</label>
      {/if}
      <details class="mt-1"><summary class="cursor-pointer text-xs text-neutral">{t.preview}</summary><Preview field={f} /></details>
    </section>
  {/each}

  <div class="mt-3 flex flex-wrap gap-1">
    <span class="w-full text-xs text-neutral">{t.addField}</span>
    {#each types as k}<button class="btn btn-sm" onclick={() => (form = addField(form!, k))}>{t[k]}</button>{/each}
  </div>

  {#if errors.length}
    <div class="alert alert-error mt-3 flex-col items-start"><b>{t.errors}</b><ul class="list-disc pl-4">{#each errors as e}<li>{e}</li>{/each}</ul></div>
  {/if}
  {#if conflict}
    <div class="alert alert-warning mt-3"><span>{t.conflict}</span><button class="btn btn-sm" onclick={load}>{t.reload}</button></div>
  {/if}
  {#if msg}<p class="mt-3 text-sm">{msg}</p>{/if}
  <button class="btn btn-primary btn-block mt-3" disabled={busy} onclick={save}>{t.save}</button>
{/if}
