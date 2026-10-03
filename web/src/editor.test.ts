import { expect, test } from 'bun:test'
import { addField, errorsByField, moveField, multiRange, setMultiMin, removeField, updateField, validate, type Form, type Text, type Multi } from './editor'

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

const form = (...fields: any[]): Form => ({ welcome: 'w', fields })

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
  expect(validate({ welcome: ' \n', fields: [] })).toEqual(['welcome: empty'])
  const m = { id: 'm', type: 'multi', prompt: 'p', options: ['a'], max: 0, required: true } as Multi
  expect(validate(form(m))).toEqual(['m: max must be at least 1 when required'])
})

test('validate: whole-number limits', () => {
  expect(validate(form({ id: 'a', type: 'multi', prompt: 'p', options: ['x'], min: 1.5, required: true }))).toEqual(['a: min and max must be whole numbers'])
  expect(validate(form({ id: 'a', type: 'multi', prompt: 'p', options: ['x'], max: 3e9, required: false }))).toEqual(['a: min and max must be whole numbers'])
  expect(validate(form({ id: 'b', type: 'int', prompt: 'p', min: 0.5, required: true }))).toEqual(['b: min and max must be whole numbers'])
  expect(validate(form({ id: 'b', type: 'int', prompt: 'p', max: 3e9, required: true }))).toEqual([])
})

test('validate: limits and lengths', () => {
  expect(validate(form({ id: 'a', type: 'multi', prompt: 'p', options: ['x'], min: -1, required: false }))).toEqual(['a: min and max must not be negative'])
  expect(validate(form({ id: 'a', type: 'radio', prompt: 'p'.repeat(1001), options: ['o'.repeat(65)], other: false, required: true })))
    .toEqual(['a: prompt longer than 1000 chars', 'a: option label empty or longer than 64 chars'])
  const many = Array.from({ length: 51 }, (_, i) => ({ id: 'i' + i, type: 'link', prompt: 'p', required: true }))
  expect(validate(form(...many))).toEqual(['form: more than 50 fields'])
})

test('errorsByField groups messages under their field, welcome or form', () => {
  const by = errorsByField(['welcome: empty', 'a4: duplicate id', 'a4: empty prompt', 'form: empty field id'])
  expect(by.get('welcome')).toEqual(['empty'])
  expect(by.get('a4')).toEqual(['duplicate id', 'empty prompt'])
  expect(by.get('form')).toEqual(['empty field id'])
  expect(by.get('zz')).toBeUndefined()
})

test('multiRange: At least 0 is optional, an optional field never asks for more, bounds keep min <= max <= options', () => {
  const m = (p: Partial<Multi>): Multi => ({ id: 'm', type: 'multi', prompt: 'p', options: ['a', 'b', 'c'], required: true, ...p })
  expect(multiRange(m({}))).toEqual({ min: 1, max: 3, minLo: 0, minHi: 3, maxLo: 1, maxHi: 3 })
  expect(multiRange(m({ required: false }))).toEqual({ min: 0, max: 3, minLo: 0, minHi: 3, maxLo: 1, maxHi: 3 })
  expect(multiRange(m({ required: false, min: 2 })).min).toBe(0)
  expect(multiRange(m({ min: 2, max: 2 }))).toEqual({ min: 2, max: 2, minLo: 0, minHi: 2, maxLo: 2, maxHi: 3 })
  // a max left over from a longer option list shows as the option count
  expect(multiRange(m({ max: 5 })).max).toBe(3)
})

test('setMultiMin: At least 0 makes the field optional, anything above makes it required', () => {
  const m: Multi = { id: 'm', type: 'multi', prompt: 'p', options: ['a', 'b'], required: true }
  expect(setMultiMin(m, 0)).toEqual({ min: 0, required: false })
  expect(setMultiMin(m, 2)).toEqual({ min: 2, required: true })
})
