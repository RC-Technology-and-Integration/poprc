import { useEffect, useState } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { ChevronDown, LogIn, Moon, ShieldCheck, Sun, User } from "lucide-react";
import rcLogo from "../assets/rclogo.jpg";
import { useAuth, homePorPerfil } from "../contexts/AuthContext";
import api, { getApiErrorMessage } from "../services/api";
import { API_ORIGIN } from "../services/runtimeConfig";
import { useTheme } from "../contexts/ThemeContext";
import { formatarCpf } from "../utils/cpf";
import { APP_VERSION_LABEL } from "../config/appVersion";

export default function Login() {
  const { usuario, carregando, configuracao, login, loginDesenvolvimento, configurarAdministrador } = useAuth();
  const [credenciais, setCredenciais] = useState({ cpf: "", senha: "" });
  const [primeiroAdmin, setPrimeiroAdmin] = useState({ nome: "", cpf: "", senha: "", confirmarSenha: "", cidade: "" });
  const [usuariosDev, setUsuariosDev] = useState([]);
  const [funcionarioId, setFuncionarioId] = useState("");
  const [devAberto, setDevAberto] = useState(false);
  const [entrando, setEntrando] = useState(false);
  const [erro, setErro] = useState("");
  const [mensagem, setMensagem] = useState("");
  const location = useLocation();
  const { theme, toggleTheme } = useTheme();

  useEffect(() => {
    const codigo = new URLSearchParams(location.search).get("error");
    const senhaAlterada = new URLSearchParams(location.search).get("senhaAlterada");
    const motivoSessao = sessionStorage.getItem("auth:reason");
    const logoutIntencional = sessionStorage.getItem("auth:logout") === "1";
    sessionStorage.removeItem("auth:reason");
    sessionStorage.removeItem("auth:logout");
    if (codigo === "conta_nao_vinculada") setErro("Esta conta Zoho não está vinculada a um funcionário ativo. O administrador deve cadastrar o mesmo e-mail em Equipes.");
    if (codigo === "oauth") setErro("A Zoho não concluiu a autenticação. Verifique a configuração OAuth e tente novamente.");
    if (!logoutIntencional && (codigo === "sessao" || motivoSessao === "sessao")) {
      setErro("Sua sessão expirou. Entre novamente para continuar.");
    }
    if (senhaAlterada === "1") setMensagem("Senha alterada e confirmada no banco. Entre com a nova senha.");
  }, [location.search]);

  useEffect(() => {
    if (!configuracao.devLoginEnabled) return;
    api.get("/auth/dev-usuarios").then(({ data }) => { setUsuariosDev(data); if (data.length) setFuncionarioId(String(data[0].id)); }).catch(() => {});
  }, [configuracao.devLoginEnabled]);

  if (!carregando && usuario) {
    return <Navigate to={location.state?.from || homePorPerfil(usuario.perfil)} replace />;
  }

  const entrarLocal = async (event) => {
    event.preventDefault(); setEntrando(true); setErro("");
    try { await login(credenciais.cpf, credenciais.senha); }
    catch (error) { setErro(getApiErrorMessage(error, "CPF ou senha inválidos.")); }
    finally { setEntrando(false); }
  };
  const entrarDev = async () => {
    setEntrando(true); setErro("");
    try { await loginDesenvolvimento(Number(funcionarioId)); }
    catch (error) { setErro(getApiErrorMessage(error)); }
    finally { setEntrando(false); }
  };
  const concluirBootstrap = async (event) => {
    event.preventDefault(); setEntrando(true); setErro("");
    if (primeiroAdmin.senha !== primeiroAdmin.confirmarSenha) {
      setErro("As senhas não conferem."); setEntrando(false); return;
    }
    try {
      await configurarAdministrador({
        nome: primeiroAdmin.nome,
        cpf: primeiroAdmin.cpf,
        senha: primeiroAdmin.senha,
        cidade: primeiroAdmin.cidade,
      });
    } catch (error) { setErro(getApiErrorMessage(error, "Não foi possível configurar o administrador.")); }
    finally { setEntrando(false); }
  };

  return (
    <main className="relative flex min-h-screen items-center justify-center bg-slate-950 p-4">
      <button
        type="button"
        onClick={toggleTheme}
        aria-label={theme === "dark" ? "Ativar tema claro" : "Ativar tema escuro"}
        title={theme === "dark" ? "Tema claro" : "Tema escuro"}
        className="absolute right-4 top-4 rounded border border-slate-700 bg-slate-900 p-2 text-slate-300 hover:bg-slate-800 hover:text-white"
      >
        {theme === "dark" ? <Sun size={18} /> : <Moon size={18} />}
      </button>
      <section className="w-full max-w-md rounded border border-slate-800 bg-white p-6 shadow-xl dark:bg-slate-900">
        <div className="mb-6 flex items-center gap-3 border-b border-slate-200 pb-5"><img src={rcLogo} alt="RC Technology" className="h-12 w-12 rounded object-cover" /><div><h1 className="text-lg font-bold text-slate-950">RC Operations Hub</h1><p className="text-sm text-slate-500">Acesso ao ambiente operacional</p></div></div>
        {erro && <p className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">{erro}</p>}
        {mensagem && <p className="mb-4 rounded border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800">{mensagem}</p>}
        {configuracao.bootstrapRequired ? (
          <form onSubmit={concluirBootstrap} className="space-y-4">
            <div className="rounded border border-blue-200 bg-blue-50 p-3"><h2 className="text-sm font-bold text-blue-950">Configurar primeiro administrador</h2><p className="mt-1 text-xs text-blue-800">Este cadastro aparece somente porque o ambiente está vazio.</p></div>
            <label className="block text-sm font-semibold text-slate-700">Nome<input value={primeiroAdmin.nome} onChange={(e) => setPrimeiroAdmin({ ...primeiroAdmin, nome: e.target.value })} required autoComplete="name" className="mt-1 w-full rounded border border-slate-300 px-3 py-2.5 text-sm" /></label>
            <label className="block text-sm font-semibold text-slate-700">CPF<input value={primeiroAdmin.cpf} onChange={(e) => setPrimeiroAdmin({ ...primeiroAdmin, cpf: formatarCpf(e.target.value) })} inputMode="numeric" maxLength={14} pattern="[0-9]{3}\.[0-9]{3}\.[0-9]{3}-[0-9]{2}" required placeholder="000.000.000-00" className="mt-1 w-full rounded border border-slate-300 px-3 py-2.5 text-sm" /></label>
            <label className="block text-sm font-semibold text-slate-700">Cidade<input value={primeiroAdmin.cidade} onChange={(e) => setPrimeiroAdmin({ ...primeiroAdmin, cidade: e.target.value })} className="mt-1 w-full rounded border border-slate-300 px-3 py-2.5 text-sm" /></label>
            <div className="grid gap-3 sm:grid-cols-2"><label className="block text-sm font-semibold text-slate-700">Senha<input value={primeiroAdmin.senha} onChange={(e) => setPrimeiroAdmin({ ...primeiroAdmin, senha: e.target.value })} type="password" minLength={8} required autoComplete="new-password" className="mt-1 w-full rounded border border-slate-300 px-3 py-2.5 text-sm" /></label><label className="block text-sm font-semibold text-slate-700">Confirmar<input value={primeiroAdmin.confirmarSenha} onChange={(e) => setPrimeiroAdmin({ ...primeiroAdmin, confirmarSenha: e.target.value })} type="password" minLength={8} required autoComplete="new-password" className="mt-1 w-full rounded border border-slate-300 px-3 py-2.5 text-sm" /></label></div>
            <p className="text-xs text-slate-500">Use ao menos 8 caracteres, com letras e números.</p>
            <button disabled={entrando} className="flex w-full items-center justify-center gap-2 rounded bg-blue-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-blue-700 disabled:opacity-50"><ShieldCheck size={18} /> {entrando ? "Configurando..." : "Criar administrador"}</button>
          </form>
        ) : <form onSubmit={entrarLocal} className="space-y-4">
          <label className="block text-sm font-semibold text-slate-700">CPF<input value={credenciais.cpf} onChange={(e) => setCredenciais({ ...credenciais, cpf: formatarCpf(e.target.value) })} inputMode="numeric" maxLength={14} pattern="[0-9]{3}\.[0-9]{3}\.[0-9]{3}-[0-9]{2}" autoComplete="username" required placeholder="000.000.000-00" className="mt-1 w-full rounded border border-slate-300 px-3 py-2.5 text-sm" /></label>
          <label className="block text-sm font-semibold text-slate-700">Senha<input value={credenciais.senha} onChange={(e) => setCredenciais({ ...credenciais, senha: e.target.value })} type="password" autoComplete="current-password" required className="mt-1 w-full rounded border border-slate-300 px-3 py-2.5 text-sm" /></label>
          <button disabled={entrando} className="flex w-full items-center justify-center gap-2 rounded bg-blue-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-blue-700 disabled:opacity-50"><LogIn size={18} /> {entrando ? "Entrando..." : "Entrar com CPF"}</button>
        </form>}
        {!configuracao.bootstrapRequired && configuracao.zohoEnabled && <div className="mt-4"><a href={`${API_ORIGIN}/oauth2/authorization/zoho`} className="flex w-full items-center justify-center gap-2 rounded border border-slate-300 px-4 py-2.5 text-sm font-bold text-slate-700 hover:bg-slate-50"><ShieldCheck size={18} /> Entrar com Zoho</a></div>}
        {!configuracao.bootstrapRequired && configuracao.devLoginEnabled && <div className="mt-5 border-t border-slate-200 pt-4"><button type="button" onClick={() => setDevAberto(!devAberto)} className="flex w-full items-center justify-between text-sm font-semibold text-slate-600"><span className="flex items-center gap-2"><User size={17} /> Ambiente de teste</span><ChevronDown size={16} /></button>{devAberto && <div className="mt-3 space-y-3"><select value={funcionarioId} onChange={(e) => setFuncionarioId(e.target.value)} className="w-full rounded border border-slate-300 bg-white px-3 py-2 text-sm">{usuariosDev.map((item) => <option key={item.id} value={item.id}>{item.nome} - {item.perfil.replaceAll("_", " ")}</option>)}</select><button type="button" onClick={entrarDev} disabled={!funcionarioId || entrando} className="w-full rounded border border-blue-300 px-3 py-2 text-sm font-bold text-blue-700 disabled:opacity-50">Entrar como usuário selecionado</button></div>}</div>}
        <p className="mt-5 border-t border-slate-200 pt-4 text-center font-mono text-xs text-slate-400 dark:border-slate-700" title="Versão do sistema">
          {APP_VERSION_LABEL}
        </p>
      </section>
    </main>
  );
}
