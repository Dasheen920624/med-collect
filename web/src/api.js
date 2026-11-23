let csrf

export async function api(path, options = {}) {
  const method = options.method || 'GET'
  const headers = new Headers(options.headers || {})
  if (method !== 'GET') {
    csrf ||= await api('/csrf')
    headers.set(csrf.headerName, csrf.token)
  }
  let body = options.body
  if (body && !(body instanceof FormData) && !(body instanceof URLSearchParams)) {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(body)
  }
  const response = await fetch('/api' + path, { method, headers, body, credentials: 'same-origin' })
  if (response.status === 204) return null
  const result = response.headers.get('content-type')?.includes('json') ? await response.json() : null
  if (!response.ok) {
    if (response.status === 403) csrf = null
    if (response.status === 401 && path !== '/session' && path !== '/login') {
      csrf = null
      window.dispatchEvent(new Event('medcollect:session-expired'))
    }
    const error = new Error(result?.message || (response.status === 401 ? '请登录后继续' : response.status === 403 ? '权限不足或会话已更新' : '请求失败（' + response.status + '）'))
    error.status = response.status
    throw error
  }
  return result
}

export function list(path, filters) {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== '' && value !== undefined && value !== null) query.set(key, value)
  }
  return api(path + '?' + query)
}

export async function login(username, password) {
  await api('/login', { method: 'POST', body: new URLSearchParams({ username, password }) })
  csrf = null
  return api('/session')
}

export async function logout() {
  await api('/logout', { method: 'POST' })
  csrf = null
}

export const send = (path, body, method = 'POST') => api(path, { method, body })
export const original = (id, preview = false) => '/api/materials/' + id + '/original' + (preview ? '?preview=true' : '')
