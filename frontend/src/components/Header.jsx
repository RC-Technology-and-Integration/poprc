import { Link, useLocation } from "react-router-dom";
import { Bell, Menu, Moon, Sun } from "lucide-react";
import UserMenu from "./UserMenu";
import rcLogo from "../assets/rclogo.jpg";
import { PERMISSOES, temPermissao } from "../security/permissions";

const contextoRotas = [
  { teste: (path) => path === "/", area: "Visão geral", pagina: "Central operacional" },
  { teste: (path) => path.startsWith("/contratos"), area: "Operação", pagina: "Contratos" },
  { teste: (path) => path.startsWith("/projetos"), area: "Operação", pagina: "Projetos" },
  { teste: (path) => path.startsWith("/ordens-servico"), area: "Operação", pagina: "Ordens de Serviço" },
  { teste: (path) => path.startsWith("/obras"), area: "Operação", pagina: "Gestão de Obras" },
  { teste: (path) => path.startsWith("/funcionarios"), area: "Operação", pagina: "Equipes" },
  { teste: (path) => path.startsWith("/estoque"), area: "Materiais", pagina: "Estoque" },
  { teste: (path) => path.startsWith("/auditoria"), area: "Auditoria", pagina: "Retirada e Devolução" },
  { teste: (path) => path.startsWith("/financeiro/lucratividade"), area: "Gestão", pagina: "Lucratividade" },
  { teste: (path) => path.startsWith("/financeiro/faturamento"), area: "Gestão", pagina: "Faturamento" },
  { teste: (path) => path.startsWith("/logistica"), area: "Gestão", pagina: "Viagens e Reembolsos" },
  { teste: (path) => path.startsWith("/configuracao"), area: "Sistema", pagina: "Notificações" },
];

export default function Header({ usuario, onMenuClick, theme, onThemeToggle }) {
  const { pathname } = useLocation();
  const contexto = contextoRotas.find((item) => item.teste(pathname)) || {
    area: "RC Operations Hub",
    pagina: "Central operacional",
  };

  return (
    <header data-app-header className="z-20 flex h-16 shrink-0 items-center justify-between gap-2 border-b border-slate-200 bg-white px-3 dark:border-slate-800 dark:bg-slate-900 sm:px-6">
      <div className="flex min-w-0 items-center gap-3">
        <button
          type="button"
          onClick={onMenuClick}
          aria-label="Abrir menu"
          title="Abrir menu"
          className="shrink-0 rounded border border-slate-200 p-2 text-slate-700 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800 md:hidden"
        >
          <Menu size={20} />
        </button>
        {(pathname === "/estoque" || pathname.startsWith("/estoque/")) ? (
          <Link to="/" className="estoque-brand" aria-label="POP RC — Central operacional">
            <img src={rcLogo} alt="RC Technology" width="44" height="44" />
            <span>POP RC<small>Operações / Estoque</small></span>
          </Link>
        ) : (
        <div className="min-w-0">
          <p className="truncate text-xs font-semibold text-slate-500 dark:text-slate-400">{contexto.area}</p>
          <p className="truncate text-sm font-bold text-slate-900 dark:text-white sm:text-base">{contexto.pagina}</p>
        </div>
        )}
      </div>

      <div className="flex shrink-0 items-center gap-2 sm:gap-3">
        <button
          type="button"
          onClick={onThemeToggle}
          aria-label={theme === "dark" ? "Ativar tema claro" : "Ativar tema escuro"}
          title={theme === "dark" ? "Tema claro" : "Tema escuro"}
          className="rounded border border-slate-200 p-2 text-slate-500 hover:bg-slate-50 hover:text-blue-700 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-blue-300"
        >
          {theme === "dark" ? <Sun size={18} /> : <Moon size={18} />}
        </button>
        {temPermissao(usuario?.perfil, PERMISSOES.NOTIFICACOES_VISUALIZAR) && (
          <Link
            to="/configuracao-notificacoes"
            aria-label="Abrir notificações"
            title="Notificações"
            className="rounded border border-slate-200 p-2 text-slate-500 hover:bg-slate-50 hover:text-blue-700 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-blue-300"
          >
            <Bell size={18} />
          </Link>
        )}
        <UserMenu />
      </div>
    </header>
  );
}
