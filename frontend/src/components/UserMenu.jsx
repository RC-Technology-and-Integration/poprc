import { useEffect, useRef, useState } from "react";
import { ChevronDown, LogOut, RefreshCw, User } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../contexts/AuthContext";

const nomesPerfis = {
  ADMIN: "Administrador",
  SUPERVISOR_TECNICO: "Supervisor técnico",
  TECNICO: "Técnico",
  ESTOQUE: "Estoque",
  AUDITOR: "Auditor",
};

export default function UserMenu({ compacto = false }) {
  const { usuario, configuracao, logout } = useAuth();
  const [aberto, setAberto] = useState(false);
  const ref = useRef(null);
  const navigate = useNavigate();

  useEffect(() => {
    const fechar = (event) => { if (!ref.current?.contains(event.target)) setAberto(false); };
    document.addEventListener("mousedown", fechar);
    return () => document.removeEventListener("mousedown", fechar);
  }, []);

  const sair = async (trocar = false) => {
    try {
      await logout();
    } catch {
      return; // O provedor mostra o motivo e a sessão atual permanece acessível.
    }
    navigate(trocar ? "/login?trocar=1" : "/login", { replace: true });
  };

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setAberto((valor) => !valor)}
        className="flex min-h-10 items-center gap-2 rounded border border-slate-200 px-2 text-left hover:bg-slate-50"
        aria-label={`Abrir menu de ${usuario?.nome || "usuário"}`}
        aria-expanded={aberto}
        aria-haspopup="menu"
      >
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded bg-slate-100 text-slate-600"><User size={16} /></span>
        {!compacto && <span className="hidden min-w-0 sm:block"><span className="block max-w-36 truncate text-sm font-semibold text-slate-800">{usuario?.nome || "Usuário"}</span><span className="block text-[10px] font-bold uppercase text-slate-400">{nomesPerfis[usuario?.perfil] || "Usuário"}</span></span>}
        <ChevronDown size={14} className="text-slate-400" />
      </button>
      {aberto && (
        <div className="absolute right-0 mt-2 w-56 rounded border border-slate-200 bg-white p-1 shadow-xl">
          <button type="button" onClick={() => { setAberto(false); navigate("/perfil"); }} className="flex w-full items-center gap-2 rounded px-3 py-2 text-sm text-slate-700 hover:bg-slate-100"><User size={16} /> Meu perfil</button>
          {configuracao.devLoginEnabled && <button type="button" onClick={() => sair(true)} className="flex w-full items-center gap-2 rounded px-3 py-2 text-sm text-slate-700 hover:bg-slate-100"><RefreshCw size={16} /> Alterar usuário de teste</button>}
          {configuracao.securityEnabled && <button type="button" onClick={() => sair(false)} className="flex w-full items-center gap-2 rounded px-3 py-2 text-sm text-red-700 hover:bg-red-50"><LogOut size={16} /> Sair</button>}
        </div>
      )}
    </div>
  );
}
