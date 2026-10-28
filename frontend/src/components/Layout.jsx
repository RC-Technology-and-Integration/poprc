import { useEffect, useState } from "react";
import { useLocation } from "react-router-dom";
import Sidebar from "./Sidebar";
import Header from "./Header";
import Alert from "./Alert";
import { useAuth } from "../contexts/AuthContext";
import { useTheme } from "../contexts/ThemeContext";

const SIDEBAR_STORAGE_KEY = "rc-operations:sidebar-collapsed";

const sidebarInicial = () => {
  if (typeof window === "undefined") return false;
  const salvo = window.localStorage.getItem(SIDEBAR_STORAGE_KEY);
  if (salvo != null) return salvo === "true";
  return window.matchMedia?.("(min-width: 768px) and (max-width: 1279px)").matches || false;
};

export default function Layout({ children }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(sidebarInicial);
  const { pathname, state } = useLocation();
  const { usuario } = useAuth();
  const { theme, toggleTheme } = useTheme();

  useEffect(() => {
    setMenuOpen(false);
  }, [pathname]);

  useEffect(() => {
    window.localStorage.setItem(SIDEBAR_STORAGE_KEY, String(sidebarCollapsed));
  }, [sidebarCollapsed]);

  return (
    <div className={`flex h-dvh bg-slate-50 dark:bg-slate-950 ${(pathname === "/estoque" || pathname.startsWith("/estoque/")) ? "layout-estoque" : ""}`}>
      <Sidebar
        estoque={(pathname === "/estoque" || pathname.startsWith("/estoque/"))}
        isOpen={menuOpen}
        onClose={() => setMenuOpen(false)}
        collapsed={sidebarCollapsed}
        onToggleCollapsed={() => setSidebarCollapsed((atual) => !atual)}
      />
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <Header
          usuario={usuario}
          onMenuClick={() => setMenuOpen(true)}
          theme={theme}
          onThemeToggle={toggleTheme}
        />
        <main data-layout-main className="min-w-0 flex-1 overflow-auto p-3 sm:p-4 lg:p-6 2xl:p-8">
          {state?.acessoNegado && <div role="alert" className="mb-4"><Alert type="error" message="Acesso negado. Seu perfil não tem permissão para acessar esta página." /></div>}
          {children}
        </main>
      </div>
    </div>
  );
}
