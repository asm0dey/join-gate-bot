import { tg } from './tg'
import type { Form } from './editor'

export type Group = { id: number; title: string; hasForm: boolean; retentionDays: number; active: boolean }
export type FormDto = { version: number; schema: Form | null }
export type Status = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'EXPIRED'
export const STATUSES: Status[] = ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXPIRED']
export type SubmissionRow = { id: number; userId: number; name: string; username: string | null; status: Status; createdAt: string; decidedBy: number | null }
export type Answer = { fieldId: string; prompt: string; value: string }
export type SubmissionDetail = { row: SubmissionRow; answers: Answer[] | null }

/** [body] is the parsed JSON error body, if any: ErrorsDto on 400, FormDto on 409. */
export class ApiError extends Error {
  constructor(public status: number, public body?: any) { super(String(status)) }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response
  try {
    // relative URL: the app may be served under a path prefix (vite base './')
    res = await fetch(`api${path}`, {
      method,
      headers: { Authorization: `tma ${tg?.initData ?? ''}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    })
  } catch { throw new ApiError(0) }
  if (!res.ok) throw new ApiError(res.status, await res.json().catch(() => undefined))
  return (await res.json().catch(() => undefined)) as T // 202/204 have no body
}

const g = (id: number) => `/groups/${id}`
export const api = {
  groups: () => call<Group[]>('GET', '/groups'),
  form: (id: number) => call<FormDto>('GET', `${g(id)}/form`),
  saveForm: (id: number, schema: Form, baseVersion: number) => call<{ version: number }>('PUT', `${g(id)}/form`, { schema, baseVersion }),
  submissions: (id: number, status?: Status) => call<SubmissionRow[]>('GET', `${g(id)}/submissions${status ? `?status=${status}` : ''}`),
  submission: (id: number, sid: number) => call<SubmissionDetail>('GET', `${g(id)}/submissions/${sid}`),
  remove: (id: number, sid: number) => call<void>('DELETE', `${g(id)}/submissions/${sid}`),
  exportCsv: (id: number) => call<void>('POST', `${g(id)}/export`),
  retention: (id: number, retentionDays: number) => call<void>('PUT', `${g(id)}/settings`, { retentionDays }),
}
