// Pure form-editing helpers. JSON shape mirrors FormSchema.kt; validate() mirrors validateForm.
export const TEXT_MAX = 4096, MAX_FIELDS = 50, MAX_OPTIONS = 20, MAX_OPTION_LEN = 64, MAX_PROMPT = 1000, MAX_WELCOME = 2000

type Base = { id: string; prompt: string }
export type Radio = Base & { type: 'radio'; options: string[]; other: boolean; required: boolean }
export type Multi = Base & { type: 'multi'; options: string[]; min?: number; max?: number; required: boolean }
export type Text = Base & { type: 'text'; maxLen: number; required: boolean }
export type IntField = Base & { type: 'int'; min?: number; max?: number; required: boolean }
export type Link = Base & { type: 'link'; required: boolean }
export type Consent = Base & { type: 'consent' } // the server's Consent has no `required` key
export type Field = Radio | Multi | Text | IntField | Link | Consent
export interface Form { welcome: string; fields: Field[] }

const nz = <T>(v: T | null | undefined) => v ?? undefined

/** Server responses omit defaulted keys (and null min/max); fill them in. */
export function normalize(f: Form): Form {
  return {
    welcome: f.welcome,
    fields: f.fields.map((x): Field => {
      switch (x.type) {
        case 'radio': return { ...x, other: x.other ?? false, required: x.required ?? true }
        case 'multi': return { ...x, min: nz(x.min), max: nz(x.max), required: x.required ?? true }
        case 'text': return { ...x, maxLen: x.maxLen ?? TEXT_MAX, required: x.required ?? true }
        case 'int': return { ...x, min: nz(x.min), max: nz(x.max), required: x.required ?? true }
        case 'link': return { ...x, required: x.required ?? true }
        case 'consent': return { id: x.id, type: 'consent', prompt: x.prompt }
      }
    }),
  }
}

function newId(f: Form): string {
  const base = 'f' + Date.now().toString(36)
  const ids = new Set(f.fields.map(x => x.id))
  let id = base
  for (let n = 2; ids.has(id); n++) id = base + n
  return id
}

export function addField(f: Form, type: Field['type'] | 'yesno'): Form {
  const b = { id: newId(f), prompt: '' }
  const nf: Field =
    type === 'yesno' ? { ...b, type: 'radio', options: ['Yes', 'No'], other: false, required: true }
    : type === 'radio' ? { ...b, type, options: ['', ''], other: false, required: true }
    : type === 'multi' ? { ...b, type, options: ['', ''], required: true }
    : type === 'text' ? { ...b, type, maxLen: TEXT_MAX, required: true }
    : type === 'int' ? { ...b, type, required: true }
    : type === 'link' ? { ...b, type, required: true }
    : { ...b, type }
  return { ...f, fields: [...f.fields, nf] }
}

export function moveField(f: Form, i: number, dir: -1 | 1): Form {
  const j = i + dir
  if (i < 0 || j < 0 || i >= f.fields.length || j >= f.fields.length) return f
  const fields = [...f.fields];
  [fields[i], fields[j]] = [fields[j], fields[i]]
  return { ...f, fields }
}

export const removeField = (f: Form, i: number): Form => ({ ...f, fields: f.fields.filter((_, k) => k !== i) })

export const updateField = (f: Form, i: number, patch: Partial<Field>): Form =>
  ({ ...f, fields: f.fields.map((x, k) => (k === i ? ({ ...x, ...patch } as Field) : x)) })

export function validate(f: Form): string[] {
  const out: string[] = []
  if (f.fields.length > MAX_FIELDS) out.push(`form: more than ${MAX_FIELDS} fields`)
  if (f.welcome.trim() === '') out.push('welcome: empty')
  else if (f.welcome.length > MAX_WELCOME) out.push(`welcome: longer than ${MAX_WELCOME} chars`)
  const seen = new Set<string>()
  for (const x of f.fields) {
    if (x.id.trim() === '') { out.push('form: empty field id'); continue }
    const err = (m: string) => out.push(`${x.id}: ${m}`)
    if (seen.has(x.id)) err('duplicate id'); else seen.add(x.id)
    if (x.prompt.trim() === '') err('empty prompt')
    else if (x.prompt.length > MAX_PROMPT) err(`prompt longer than ${MAX_PROMPT} chars`)
    if (x.type === 'radio' || x.type === 'multi') {
      if (x.options.length === 0) err('needs options')
      if (x.options.length > MAX_OPTIONS) err(`more than ${MAX_OPTIONS} options`)
      if (x.options.some(o => o.trim() === '' || o.length > MAX_OPTION_LEN)) err(`option label empty or longer than ${MAX_OPTION_LEN} chars`)
    }
    const I32 = (n?: number) => n == null || (Number.isInteger(n) && Math.abs(n) <= 2147483647)
    if (x.type === 'multi' && !(I32(x.min) && I32(x.max))) err('min and max must be whole numbers')
    else if (x.type === 'int' && !(Number.isSafeInteger(x.min ?? 0) && Number.isSafeInteger(x.max ?? 0))) err('min and max must be whole numbers')
    else if (x.type === 'multi') {
      if (x.min != null && x.max != null && x.min > x.max) err('min > max')
      if (x.min != null && x.min > x.options.length) err('min exceeds option count')
      if ((x.min ?? 0) < 0 || (x.max ?? 0) < 0) err('min and max must not be negative')
      if (x.required && x.max === 0) err('max must be at least 1 when required')
    } else if (x.type === 'int') {
      if (x.min != null && x.max != null && x.min > x.max) err('min > max')
    } else if (x.type === 'text') {
      if (!(Number.isInteger(x.maxLen) && x.maxLen >= 1 && x.maxLen <= TEXT_MAX)) err(`maxLen must be 1..${TEXT_MAX}`)
    }
  }
  return out
}

/** validate()'s and the server's "<id>: <message>" lines, grouped by id ("welcome" and "form" included). */
export function errorsByField(errors: string[]): Map<string, string[]> {
  const by = new Map<string, string[]>()
  for (const e of errors) {
    const at = e.indexOf(': ')
    const [key, msg] = at < 0 ? ['form', e] : [e.slice(0, at), e.slice(at + 2)]
    by.set(key, [...(by.get(key) ?? []), msg])
  }
  return by
}

/** The choice counts a multi-choice field enforces (FormSchema.kt's minPicks/maxPicks), and how far each stepper may go. */
export function multiRange(f: Multi) {
  const n = f.options.length
  const min = f.required ? (f.min ?? 1) : 0
  const max = Math.min(f.max ?? n, n)
  return { min, max, minLo: 0, minHi: max, maxLo: Math.max(min, 1), maxHi: n }
}

/** "At least 0" is how an admin makes a multiple choice optional; there is no separate Required switch. */
export const setMultiMin = (f: Multi, min: number): Partial<Multi> => ({ min, required: min >= 1 })
