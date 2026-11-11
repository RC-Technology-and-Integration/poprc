import { createContext, useContext, useEffect, useMemo, useRef, useState } from "react";
import api, { exigirSessaoAtual, invalidarSessao, refreshCsrfToken, revisaoAtualSessao, setReauthHandler } from "../services/api";
import { normalizarCpf } from "../utils/cpf";

const AuthContext = createContext(null);
const MUDANCA_SESSAO = "poprc:auth:session-change";
const LOCK_SESSAO = "poprc:auth:transicao";

async function comSessaoExclusiva(executar) {
  let iniciou = false;
  try {
    return await navigator.locks.request(LOCK_SESSAO, { ifAvailable: true }, async lock => {
      iniciou = true;
      if (!lock) throw new Error("Outra aba está autenticando ou encerrando a sessão. Aguarde e tente novamente. Se ela ficou travada, feche ou recarregue aquela aba.");
      return executar();
    });
  } catch (error) {
    if (iniciou) throw error;
    throw new Error("Este navegador não permite coordenar a sessão entre abas. Abra o sistema em um navegador compatível, em HTTPS ou localhost.");
  }
}

export const homePorPerfil = (perfil) => {
  if (perfil === "TECNICO") return "/tecnico";
  if (perfil === "ESTOQUE") return "/estoque";
  if (perfil === "AUDITOR") return "/auditoria/tecnica";
  return "/";
};

export function AuthProvider({ children }) {
  const [usuario, setUsuario] = useState(null);
  const [carregando, setCarregando] = useState(true);
  const [configuracao, setConfiguracao] = useState({ securityEnabled: true, devLoginEnabled: false, zohoEnabled: false, bootstrapRequired: false });
  const [reauthAberta, setReauthAberta] = useState(false);
  const [reauthErro, setReauthErro] = useState("");
  const [reauthProcessando, setReauthProcessando] = useState(false);
  const reauthPromise = useRef(null);

  const [revisaoSessao, setRevisaoSessao] = useState(revisaoAtualSessao);
  const canalSessao = useRef(null);
  const transicoes = useRef(new Set());
  const [avisoSessao, setAvisoSessao] = useState("");

  const cancelarReautenticacao = () => {
    reauthPromise.current?.reject(new Error("Reautenticação cancelada."));
    reauthPromise.current = null;
    setReauthAberta(false);
    setReauthProcessando(false);
  };

  const suspenderIdentidade = () => {
    const revisao = invalidarSessao();
    setRevisaoSessao(revisao);
    setUsuario(null);
    setCarregando(true);
    cancelarReautenticacao();
    return revisao;
  };

  const carregarUsuario = async (revisao = suspenderIdentidade()) => {
    try {
      const { data } = await api.get("/auth/me");
      exigirSessaoAtual(revisao);
      await refreshCsrfToken();
      exigirSessaoAtual(revisao);
      setUsuario(data);
      return data;
    } catch (error) {
      if (error.response?.status !== 401) throw error;
      return null;
    } finally {
      if (revisao === revisaoAtualSessao()) setCarregando(false);
    }
  };

  const comunicar = (id, fase) => {
    if (canalSessao.current) {
      canalSessao.current.postMessage({ id, fase });
      return;
    }
    try {
      localStorage.setItem(MUDANCA_SESSAO, JSON.stringify({ id, fase }));
    } catch {
      // A autorização no servidor vale também quando a notificação entre abas falha.
    }
  };

  const mudarSessao = (rota, dados, renovarAntes = false) => comSessaoExclusiva(async () => {
    const id = crypto.randomUUID();
    transicoes.current.add(id);
    suspenderIdentidade();
    comunicar(id, "inicio");
    let atual;
    try {
      if (renovarAntes) await refreshCsrfToken();
      await api.post(rota, dados, { __transicaoSessao: true });
    } finally {
      transicoes.current.delete(id);
      if (!transicoes.current.size) atual = await carregarUsuario().catch(() => null);
      comunicar(id, "fim");
    }
    if (rota !== "/auth/logout" && !atual) throw new Error("Não foi possível confirmar a sessão.");
    return atual;
  });

  useEffect(() => {
    const acompanhar = id => {
      // A liberação também ocorre se a aba titular fechar antes de enviar "fim".
      navigator.locks?.request(LOCK_SESSAO, { mode: "shared" }, async () => {
        if (transicoes.current.delete(id) && !transicoes.current.size) {
          await carregarUsuario().catch(() => {});
        }
      }).catch(() => {
        // A falha da leitura do lock mantém a identidade suspensa até nova confirmação.
        if (transicoes.current.delete(id) && !transicoes.current.size) setCarregando(false);
      });
    };
    const sincronizar = (mensagem) => {
      const { id, fase } = mensagem ?? {};
      if (fase === "inicio" && typeof id === "string" && !transicoes.current.has(id)) {
        transicoes.current.add(id);
        suspenderIdentidade();
        acompanhar(id);
      } else if (fase === "fim" || fase === "atualizar") {
        const pendente = transicoes.current.has(id);
        // Com Web Locks, a aba observadora espera o fim efetivo da requisição.
        if (pendente && navigator.locks) return;
        transicoes.current.delete(id);
        if (!transicoes.current.size) carregarUsuario().catch(() => {});
      }
    };
    const canal = typeof BroadcastChannel === "function" ? new BroadcastChannel(MUDANCA_SESSAO) : null;
    canalSessao.current = canal;
    if (canal) canal.onmessage = ({ data }) => sincronizar(data);
    const receberStorage = (event) => {
      if (event.key !== MUDANCA_SESSAO) return;
      try {
        sincronizar(JSON.parse(event.newValue));
      } catch {
        sincronizar({ fase: "atualizar" });
      }
    };
    const expirarSessao = () => {
      suspenderIdentidade();
      if (!transicoes.current.size) setCarregando(false);
    };
    const restaurarPagina = (event) => {
      if (event.persisted && !transicoes.current.size) carregarUsuario().catch(() => {});
    };
    window.addEventListener("storage", receberStorage);
    window.addEventListener("auth:unauthorized", expirarSessao);
    window.addEventListener("pageshow", restaurarPagina);
    const revisao = suspenderIdentidade();
    api.get("/auth/config").then(async ({ data }) => {
      exigirSessaoAtual(revisao);
      setConfiguracao(data);
      const trocar = new URLSearchParams(window.location.search).get("trocar") === "1";
      if (!data.securityEnabled && data.devLoginEnabled && !trocar) {
        const usuarios = (await api.get("/auth/dev-usuarios")).data;
        exigirSessaoAtual(revisao);
        const escolhido = usuarios.find(item => item.perfil === "ADMIN") || usuarios[0];
        if (escolhido) return mudarSessao("/auth/dev-login", { funcionarioId: escolhido.id });
      }
      return carregarUsuario(revisao);
    }).catch(() => {
      if (revisao === revisaoAtualSessao()) setCarregando(false);
    });
    return () => {
      invalidarSessao();
      canal?.close();
      canalSessao.current = null;
      window.removeEventListener("storage", receberStorage);
      window.removeEventListener("auth:unauthorized", expirarSessao);
      window.removeEventListener("pageshow", restaurarPagina);
    };
  }, []);

  useEffect(() => {
    setReauthHandler(() => {
      if (transicoes.current.size) return Promise.reject(new Error("A sessão está em confirmação."));
      if (reauthPromise.current) return reauthPromise.current.promise;
      let resolve;
      let reject;
      const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
      reauthPromise.current = { promise, resolve, reject, revisao: revisaoAtualSessao() };
      setReauthErro("");
      setReauthAberta(true);
      return promise;
    });
    return () => setReauthHandler(null);
  }, []);

  const login = (cpf, senha) => mudarSessao("/auth/login", { cpf: normalizarCpf(cpf), senha });
  const loginDesenvolvimento = (funcionarioId) => mudarSessao("/auth/dev-login", { funcionarioId });
  const configurarAdministrador = async (dados) => {
    const atual = await mudarSessao("/auth/bootstrap", dados, true);
    setConfiguracao(estado => ({ ...estado, bootstrapRequired: false }));
    return atual;
  };
  const logout = async () => {
    setAvisoSessao("");
    sessionStorage.setItem("auth:logout", "1");
    try {
      await mudarSessao("/auth/logout", undefined, true);
    } catch (error) {
      sessionStorage.removeItem("auth:logout");
      setAvisoSessao(error.message || "Não foi possível sair. Tente novamente.");
      throw error;
    }
  };
  const alterarSenha = async (senhaAtual, novaSenha) => {
    const atual = await mudarSessao("/auth/alterar-senha", { senhaAtual, novaSenha }, true);
    if (!atual.senhaConfigurada || atual.trocaSenhaObrigatoria) {
      throw new Error("O servidor não confirmou a alteração da senha.");
    }
    return atual;
  };

  const confirmarReautenticacao = async (senha) => {
    const pendente = reauthPromise.current;
    if (!pendente) return;
    setReauthProcessando(true);
    setReauthErro("");
    try {
      const token = await comSessaoExclusiva(async () => {
        exigirSessaoAtual(pendente.revisao);
        const csrf = await refreshCsrfToken();
        exigirSessaoAtual(pendente.revisao);
        await api.post("/auth/reauth", { senha });
        return csrf;
      });
      if (reauthPromise.current !== pendente) return;
      pendente.resolve(token);
      reauthPromise.current = null;
      setReauthAberta(false);
      setReauthProcessando(false);
    } catch (error) {
      if (reauthPromise.current !== pendente) return;
      setReauthErro(error.response?.data?.erro || error.message || "Senha inválida.");
      setReauthProcessando(false);
    }
  };

  const value = useMemo(
    () => ({ usuario, carregando, configuracao, revisaoSessao, login, loginDesenvolvimento, configurarAdministrador, logout, alterarSenha, recarregar: carregarUsuario }),
    [usuario, carregando, configuracao, revisaoSessao],
  );

  return (
    <AuthContext.Provider value={value}>
      {children}
      {avisoSessao && (
        <div role="alert" className="fixed inset-x-4 bottom-4 z-[90] rounded border bg-white p-4 text-slate-900 shadow-lg">
          <p>{avisoSessao}</p>
          <button type="button" onClick={() => setAvisoSessao("")}>Fechar aviso</button>
        </div>
      )}
      {reauthAberta && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center bg-slate-950/70 p-4" role="dialog" aria-modal="true">
          <form
            className="w-full max-w-sm rounded border border-slate-200 bg-white p-5 shadow-2xl"
            onSubmit={(event) => { event.preventDefault(); confirmarReautenticacao(event.currentTarget.senha.value); }}
          >
            <h2 className="text-base font-bold text-slate-900">Confirme sua identidade</h2>
            <p className="mt-1 text-sm text-slate-500">Digite sua senha para autorizar esta operação importante.</p>
            <input name="senha" type="password" autoFocus required className="mt-4 w-full rounded border border-slate-300 px-3 py-2 text-sm" placeholder="Senha" />
            {reauthErro && <p className="mt-2 text-sm text-red-700">{reauthErro}</p>}
            <div className="mt-5 flex justify-end gap-2">
              <button type="button" onClick={cancelarReautenticacao} className="rounded border border-slate-300 px-3 py-2 text-sm font-semibold text-slate-700">Cancelar</button>
              <button type="submit" disabled={reauthProcessando} className="rounded bg-blue-600 px-3 py-2 text-sm font-semibold text-white disabled:opacity-50">{reauthProcessando ? "Validando..." : "Confirmar"}</button>
            </div>
          </form>
        </div>
      )}
    </AuthContext.Provider>
  );
}

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth deve ser usado dentro de AuthProvider");
  return context;
};
