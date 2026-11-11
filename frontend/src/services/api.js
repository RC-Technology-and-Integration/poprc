import axios, { CanceledError } from 'axios'
import { API_BASE_URL } from './runtimeConfig'

const api = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true,
  withXSRFToken: true,
})

let reauthHandler = null
let revisaoSessao = 0

export const revisaoAtualSessao = () => revisaoSessao
export const exigirSessaoAtual = (revisao) => {
  if (revisao !== revisaoSessao) throw new CanceledError('A sessão mudou.')
}
export const invalidarSessao = () => {
  delete api.defaults.headers.common['X-XSRF-TOKEN']
  return ++revisaoSessao
}

api.interceptors.request.use(config => {
  config.__revisaoSessao ??= revisaoSessao
  exigirSessaoAtual(config.__revisaoSessao)
  return config
}, error => { throw error }, { synchronous: true })

export const setReauthHandler = (handler) => {
  reauthHandler = handler
}

export const refreshCsrfToken = async () => {
  const revisao = revisaoSessao
  const response = await api.get('/auth/csrf')
  exigirSessaoAtual(revisao)
  const token = response.data?.token
  if (token) api.defaults.headers.common['X-XSRF-TOKEN'] = token
  return token
}

export const getApiErrorMessage = (error, fallback = 'Não foi possível concluir a operação.') => {
  const data = error?.response?.data
  const contentType = error?.response?.headers?.['content-type'] || ''
  const respostaHtml = typeof data === 'string'
    && (contentType.includes('text/html') || /<(!doctype|html|head|body)\b/i.test(data))

  if (respostaHtml) {
    if (error.response?.status === 404) {
      return 'O serviço de autenticação não foi encontrado. Reinicie o backend e o frontend e tente novamente.'
    }
    return 'O servidor respondeu em um formato inesperado. Verifique se o backend correto está em execução.'
  }
  if (typeof data === 'string' && data.trim()) return data
  if (data?.erro) return data.erro
  if (data?.message) return data.message
  if (Array.isArray(data?.erros) && data.erros.length > 0) return data.erros.join(' ')
  if (!error?.response && error?.request) {
    return 'Não foi possível conectar ao servidor. Verifique se o backend está em execução.'
  }
  return error?.response ? fallback : error?.message || fallback
}

api.interceptors.response.use(
  (response) => {
    // A autenticação pode ter alterado o cookie mesmo se a revisão mudou.
    // Seu chamador confirma a identidade com um novo GET depois da transição.
    if (!response.config.__transicaoSessao) exigirSessaoAtual(response.config.__revisaoSessao)
    return response
  },
  async (error) => {
    const config = error.config
    if (config && !config.__transicaoSessao) exigirSessaoAtual(config.__revisaoSessao)
    const responseData = error.response?.data
    const responseMessage = typeof responseData === 'string'
      ? responseData
      : responseData?.erro || responseData?.message || ''
    if (
      error.response?.status === 428 &&
      error.response?.data?.reautenticacaoNecessaria &&
      reauthHandler &&
      config &&
      !config.__reauthRetry &&
      !config.url?.includes('/auth/reauth')
    ) {
      config.__reauthRetry = true
      const token = await reauthHandler()
      exigirSessaoAtual(config.__revisaoSessao)
      if (token && config.headers) {
        config.headers['X-XSRF-TOKEN'] = token
      }
      return api(config)
    }
    const sessaoExpirada = error.response?.status === 403
      && responseMessage.toLowerCase().includes('sessão de segurança expirada')
    if (sessaoExpirada && config && !config.__csrfRetry && !config.url?.includes('/auth/csrf')) {
      config.__csrfRetry = true
      try {
        const token = await refreshCsrfToken()
        exigirSessaoAtual(config.__revisaoSessao)
        if (token && config.headers) {
          config.headers['X-XSRF-TOKEN'] = token
        }
        return api(config)
      } catch {
        // A sessão pode ter expirado de fato; o fluxo abaixo encerra o acesso local.
      }
    }
    if (config && !config.__transicaoSessao) exigirSessaoAtual(config.__revisaoSessao)
    const endpointAutenticacao = ['/auth/me', '/auth/login', '/auth/reauth']
      .some((endpoint) => error.config?.url?.includes(endpoint))
    const logoutIntencional = sessionStorage.getItem('auth:logout') === '1'
    if ((error.response?.status === 401 || sessaoExpirada)
      && !config?.__transicaoSessao
      && !endpointAutenticacao
      && !logoutIntencional) {
      sessionStorage.setItem('auth:reason', 'sessao')
      window.dispatchEvent(new Event('auth:unauthorized'))
    }
    return Promise.reject(error)
  }
)

export default api
