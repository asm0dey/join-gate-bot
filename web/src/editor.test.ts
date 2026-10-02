import { expect, test } from 'bun:test'
import { addField, moveField, removeField, updateField, validate, type Form, type Text, type Multi } from './editor'

const empty: Form = { welcome: '', fields: [] }

test('add gives unique ids and defaults', () => {
  const f = addField(addField(empty, 'text'), 'text')
  expect(new Set(f.fields.map(x => x.id)).size).toBe(2)
  expect((f.fields[0] as Text).maxLen).toBe(4096)
  expect(f.fields[0].id).toMatch(/^f[0-9a-z]+/)
})

test('consent has no required key', () => {
  expect('required' in addField(empty, 'consent').fields[0]).toBe(false)
})

test('yesno preset', () => {
  const r = addField(empty, 'yesno').fields[0] as any
  expect(r.type).toBe('radio'); expect(r.options).toEqual(['Yes', 'No'])
})

test('move is a no-op at edges', () => {
  const f = addField(addField(empty, 'text'), 'int')
  expect(moveField(f, 0, -1)).toEqual(f)
  expect(moveField(f, 1, 1)).toEqual(f)
  expect(moveField(f, 0, 1).fields.map(x => x.type)).toEqual(['int', 'text'])
})

test('remove and update', () => {
  const f = addField(addField(empty, 'text'), 'int')
  expect(removeField(f, 0).fields.map(x => x.type)).toEqual(['int'])
  expect(updateField(f, 0, { prompt: 'Hi' }).fields[0].prompt).toBe('Hi')
  expect(f.fields[0].prompt).toBe('')
})

const form = (...fields: any[]): Form => ({ welcome: '', fields })

test('validate mirrors server messages', () => {
  expect(validate(form(
    { id: 'a4', type: 'text', prompt: 'x', maxLen: 10, required: true },
    { id: 'a4', type: 'text', prompt: '', maxLen: 0, required: true },
    { id: 'a5', type: 'int', prompt: 'n', min: 5, max: 1, required: true },
    { id: 'a6', type: 'multi', prompt: 'm', options: [], min: 3, required: true },
    { id: ' ', type: 'link', prompt: 'l', required: true },
  ))).toEqual([
    'a4: duplicate id', 'a4: empty prompt', 'a4: maxLen must be 1..4096',
    'a5: min > max',
    'a6: needs options', 'a6: min exceeds option count',
    'form: empty field id',
  ])
  expect(validate({ welcome: 'x'.repeat(2001), fields: [] })).toEqual(['welcome: longer than 2000 chars'])
  const m = { id: 'm', type: 'multi', prompt: 'p', options: ['a'], max: 0, required: true } as Multi
  expect(validate(form(m))).toEqual(['m: max must be at least 1 when required'])
})
