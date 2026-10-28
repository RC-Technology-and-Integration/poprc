import "./estoqueNavegacao.css";
import { useState } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import { useAuth } from "../contexts/AuthContext";
import { PERMISSOES, temPermissao } from "../security/permissions";
import rcLogo from "../assets/rclogo.jpg";
import {
  Bell,
  Briefcase,
  Building2,
  ClipboardList,
  DollarSign,
  FileText,
  Info,
  Layers,
  LayoutDashboard,
  LogOut,
  Package,
  PanelLeftClose,
  PanelLeftOpen,
  Plane,
  Smartphone,
  TrendingUp,
  Users,
  X,
} from "lucide-react";
import { APP_VERSION_LABEL } from "../config/appVersion";

const secoesMenu = [
  {
    titulo: "Visão geral",
    itens: [{ path: "/", icon: LayoutDashboard, label: "Central operacional", end: true, permissao: PERMISSOES.DASHBOARD_VISUALIZAR }],
  },
  {
    titulo: "Operação",
    itens: [
      { path: "/contratos", icon: FileText, label: "Contratos", permissao: PERMISSOES.CONTRATOS_VISUALIZAR },
      { path: "/projetos", icon: Briefcase, label: "Projetos", permissao: PERMISSOES.PROJETOS_VISUALIZAR },
      { path: "/ordens-servico", icon: ClipboardList, label: "Ordens de Serviço", permissao: PERMISSOES.OS_VISUALIZAR },
      { path: "/obras", icon: Building2, label: "Gestão de Obras", permissao: PERMISSOES.OBRAS_VISUALIZAR },
      { path: "/funcionarios", icon: Users, label: "Equipes", permissao: PERMISSOES.FUNCIONARIOS_VISUALIZAR },
    ],
  },
  {
    titulo: "Materiais e auditoria",
    itens: [
      { path: "/estoque", icon: Package, label: "Estoque", permissao: PERMISSOES.ESTOQUE_VISUALIZAR },
      { path: "/auditoria/tecnica", icon: Layers, label: "Retirada e Devolução", permissao: PERMISSOES.AUDITORIA_VISUALIZAR },
    ],
  },
  {
    titulo: "Gestão",
    itens: [
      { path: "/financeiro/lucratividade", icon: TrendingUp, label: "Lucratividade", permissao: PERMISSOES.FINANCEIRO_VISUALIZAR },
      { path: "/financeiro/faturamento", icon: DollarSign, label: "Faturamento", permissao: PERMISSOES.FINANCEIRO_VISUALIZAR },
      { path: "/logistica/viagens", icon: Plane, label: "Viagens e Reembolsos", permissao: PERMISSOES.FINANCEIRO_VISUALIZAR },
      { path: "/configuracao-notificacoes", icon: Bell, label: "Notificações", permissao: PERMISSOES.NOTIFICACOES_VISUALIZAR },
    ],
  },
];

export default function Sidebar({ isOpen, onClose, collapsed, onToggleCollapsed, estoque = false }) {
  const { usuario, configuracao, logout } = useAuth();
  const navigate = useNavigate();
  const [estoqueExpandido, setEstoqueExpandido] = useState(false);
  const secoesEstoque = [['materiais', 'Materiais e saldos'], ['retiradas', 'Retiradas'], ['pendencias', 'Pendências documentais'], ['historico', 'Histórico'], ['integracao', 'Planilhas e integração']];
  const handleLogout = async () => {
    await logout();
    navigate("/login", { replace: true });
  };

  const secoesVisiveis = secoesMenu
    .map((secao) => ({ ...secao, itens: secao.itens.filter((item) => temPermissao(usuario?.perfil, item.permissao)) }))
    .filter((secao) => secao.itens.length > 0);

  return (
    <>
      <aside
        aria-label="Navegação principal"
        className={`fixed inset-y-0 left-0 z-40 flex w-64 shrink-0 flex-col border-r border-slate-800 bg-slate-950 text-white transition-[width,transform] duration-200 md:visible md:static md:translate-x-0 ${
          collapsed ? "md:w-20" : "md:w-64"
        } ${
          isOpen ? "visible translate-x-0" : "invisible -translate-x-full"
        }`}
      >
        <div className={`flex h-16 shrink-0 items-center gap-3 border-b border-slate-800 px-4 ${
          collapsed ? "md:justify-center md:gap-1 md:px-2" : ""
        }`}>
          <img src={rcLogo} alt="RC Technology" className={`shrink-0 rounded object-cover ${collapsed ? "h-10 w-10 md:h-8 md:w-8" : "h-10 w-10"}`} />
          <div className={`min-w-0 flex-1 ${collapsed ? "md:hidden" : ""}`}>
            <p className="truncate text-sm font-bold text-white">{estoque ? "POP RC" : "RC Operations Hub"}</p>
            <p className="text-xs text-slate-400">{estoque ? "Operações" : "Central operacional"}</p>
          </div>
          <button
            type="button"
            onClick={onToggleCollapsed}
            aria-label={collapsed ? "Expandir menu lateral" : "Recolher menu lateral"}
            title={collapsed ? "Expandir menu lateral" : "Recolher menu lateral"}
            className="hidden rounded p-2 text-slate-400 hover:bg-slate-800 hover:text-white md:flex"
          >
            {collapsed ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />}
          </button>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar menu"
            title="Fechar menu"
            className="rounded p-2 text-slate-400 hover:bg-slate-800 hover:text-white md:hidden"
          >
            <X size={19} />
          </button>
        </div>

        <nav className={`flex-1 overflow-y-auto px-3 py-3 ${collapsed ? "md:px-2" : ""}`}>
          {secoesVisiveis.map((secao) => (
            <div key={secao.titulo} className="mb-4">
              <p className={`mb-1 px-3 text-[10px] font-bold uppercase text-slate-500 ${
                collapsed ? "md:hidden" : ""
              }`}>
                {secao.titulo}
              </p>
              {collapsed && <div className="mx-2 mb-2 hidden border-t border-slate-800 md:block" />}
              <ul className="space-y-0.5">
                {secao.itens.map((item) => (
                  <li key={item.path}>
                    {item.path === "/estoque" ? <>
                      <button type="button" className="estoque-menu-pai" aria-expanded={estoque || estoqueExpandido} aria-controls="estoque-submenu" onClick={() => { setEstoqueExpandido(true); if (collapsed) onToggleCollapsed(); navigate('/estoque/materiais'); }}><Package size={17} /><span>Estoque</span><span aria-hidden="true">⌄</span></button>
                      {(estoque || estoqueExpandido) && <ul id="estoque-submenu" className="estoque-submenu" aria-label="Seções do estoque">{secoesEstoque.map(([path, label]) => <li key={path}><NavLink to={`/estoque/${path}`} onClick={onClose}>{label}</NavLink></li>)}</ul>}
                    </> : (
                    <NavLink
                      to={item.path}
                      end={item.end}
                      onClick={onClose}
                      title={collapsed ? item.label : undefined}
                      aria-label={collapsed ? item.label : undefined}
                      className={({ isActive }) =>
                        `flex min-h-9 items-center gap-3 rounded px-3 py-2 text-sm transition-colors ${
                          collapsed ? "md:justify-center md:gap-0 md:px-2" : ""
                        } ${
                          isActive
                            ? "bg-blue-600 font-semibold text-white"
                            : "text-slate-300 hover:bg-slate-800 hover:text-white"
                        }`
                      }
                    >
                      <item.icon size={collapsed ? 19 : 17} aria-hidden="true" className="shrink-0" />
                      <span className={`min-w-0 leading-4 ${collapsed ? "md:hidden" : ""}`}>
                        {item.label}
                      </span>
                    </NavLink>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>

        <div className={`border-t border-slate-800 p-3 ${collapsed ? "md:p-2" : ""}`}>
          <div
            aria-label={`Versão do sistema ${APP_VERSION_LABEL}`}
            title={`Versão do sistema ${APP_VERSION_LABEL}`}
            className={`mb-2 flex min-h-8 items-center gap-3 px-3 text-xs text-slate-500 ${
              collapsed ? "md:justify-center md:gap-0 md:px-2" : ""
            }`}
          >
            <Info size={15} className="shrink-0" aria-hidden="true" />
            <span className={`truncate font-mono ${collapsed ? "md:hidden" : ""}`}>{APP_VERSION_LABEL}</span>
          </div>
          {temPermissao(usuario?.perfil, PERMISSOES.PORTAL_TECNICO_VISUALIZAR) && (
            <NavLink
              to="/tecnico"
              onClick={onClose}
              title={collapsed ? "Área do Técnico" : undefined}
              aria-label={collapsed ? "Área do Técnico" : undefined}
              className={`mb-2 flex min-h-10 items-center gap-3 rounded border border-slate-700 px-3 py-2 text-sm font-semibold text-slate-200 hover:border-blue-500 hover:bg-slate-900 ${
                collapsed ? "md:justify-center md:gap-0 md:px-2" : ""
              }`}
            >
              <Smartphone size={17} />
              <span className={collapsed ? "md:hidden" : ""}>Área do Técnico</span>
            </NavLink>
          )}
          {configuracao.securityEnabled && <button
            type="button"
            onClick={handleLogout}
            title={collapsed ? "Sair" : undefined}
            aria-label={collapsed ? "Sair" : undefined}
            className={`flex min-h-9 w-full items-center gap-3 rounded px-3 py-2 text-sm text-slate-400 hover:bg-slate-800 hover:text-white ${
              collapsed ? "md:justify-center md:gap-0 md:px-2" : ""
            }`}
          >
            <LogOut size={17} />
            <span className={collapsed ? "md:hidden" : ""}>Sair</span>
          </button>}
        </div>
      </aside>

      {isOpen && (
        <button
          type="button"
          aria-label="Fechar menu"
          onClick={onClose}
          className="fixed inset-0 z-30 bg-slate-950/60 md:hidden"
        />
      )}
    </>
  );
}
