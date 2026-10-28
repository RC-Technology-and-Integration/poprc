import { lazy, Suspense } from "react";
import { BrowserRouter as Router, Navigate, Route, Routes } from "react-router-dom";
import LoadingSpinner from "./components/LoadingSpinner";
import ProtectedRoute from "./components/ProtectedRoute";
import { AuthProvider } from "./contexts/AuthContext";
import Login from "./pages/Login";
import { PERMISSOES, perfisComPermissao } from "./security/permissions";

const Layout = lazy(() => import("./components/Layout"));
const AuditoriaMateriaisEAsBuilt = lazy(() => import("./pages/AuditoriaMateriaisEAsBuilt"));
const ConfiguracaoNotificacoes = lazy(() => import("./pages/ConfiguracaoNotificacoes"));
const Contratos = lazy(() => import("./pages/Contratos"));
const DashboardExecutivo = lazy(() => import("./pages/DashboardExecutivo"));
const ExecutarOrdemServico = lazy(() => import("./pages/ExecutarOrdemServico"));
const Funcionarios = lazy(() => import("./pages/Funcionarios"));
const GestaoComarcas = lazy(() => import("./pages/GestaoComarcas"));
const GestaoFaturamento = lazy(() => import("./pages/GestaoFaturamento"));
const GestaoOrdensServico = lazy(() => import("./pages/GestaoOrdensServico"));
const PainelEstoque = lazy(() => import("./pages/PainelEstoque"));
const PainelFinanceiro = lazy(() => import("./pages/PainelFinanceiro"));
const PainelViagensEReembolso = lazy(() => import("./pages/PainelViagensEReembolso"));
const PortalTecnicoDashboard = lazy(() => import("./pages/PortalTecnicoDashboard"));
const Projetos = lazy(() => import("./pages/Projetos"));
const MeuPerfil = lazy(() => import("./pages/MeuPerfil"));

const ADMIN = perfisComPermissao(PERMISSOES.FINANCEIRO_VISUALIZAR);
const GESTAO = perfisComPermissao(PERMISSOES.DASHBOARD_VISUALIZAR);
const CAMPO = perfisComPermissao(PERMISSOES.OS_VISUALIZAR);
const OBRAS = perfisComPermissao(PERMISSOES.OBRAS_VISUALIZAR);
const ESTOQUE = perfisComPermissao(PERMISSOES.ESTOQUE_VISUALIZAR);
const AUDITORIA = perfisComPermissao(PERMISSOES.AUDITORIA_VISUALIZAR);
const EQUIPES = perfisComPermissao(PERMISSOES.FUNCIONARIOS_VISUALIZAR);

function Pagina({ children, roles, layout = true }) {
  const conteudo = layout ? <Layout>{children}</Layout> : children;
  return <ProtectedRoute roles={roles}>{conteudo}</ProtectedRoute>;
}

function App() {
  return (
    <AuthProvider>
      <Router future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
        <Suspense fallback={<div className="grid min-h-dvh place-items-center bg-slate-50 dark:bg-slate-950"><LoadingSpinner /></div>}>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/perfil" element={<Pagina><MeuPerfil /></Pagina>} />
            <Route path="/" element={<Pagina roles={GESTAO}><DashboardExecutivo /></Pagina>} />
            <Route path="/contratos" element={<Pagina roles={GESTAO}><Contratos /></Pagina>} />
            <Route path="/projetos" element={<Pagina roles={GESTAO}><Projetos /></Pagina>} />
            <Route path="/funcionarios" element={<Pagina roles={EQUIPES}><Funcionarios /></Pagina>} />
            <Route path="/ordens-servico" element={<Pagina roles={CAMPO}><GestaoOrdensServico /></Pagina>} />
            <Route path="/obras" element={<Pagina roles={OBRAS}><GestaoComarcas /></Pagina>} />
            <Route path="/comarcas" element={<Navigate to="/obras" replace />} />
            <Route path="/estoque" element={<Navigate to="/estoque/materiais" replace />} />
            {['materiais', 'retiradas', 'pendencias', 'historico', 'integracao'].map(secao => <Route key={secao} path={`/estoque/${secao}`} element={<Pagina roles={ESTOQUE}><PainelEstoque key={secao} secao={secao} /></Pagina>} />)}
            <Route path="/estoque/*" element={<Navigate to="/estoque/materiais" replace />} />
            <Route path="/financeiro/lucratividade" element={<Pagina roles={ADMIN}><PainelFinanceiro /></Pagina>} />
            <Route path="/financeiro/faturamento" element={<Pagina roles={ADMIN}><GestaoFaturamento /></Pagina>} />
            <Route path="/logistica/viagens" element={<Pagina roles={ADMIN}><PainelViagensEReembolso /></Pagina>} />
            <Route path="/auditoria/tecnica" element={<Pagina roles={AUDITORIA}><AuditoriaMateriaisEAsBuilt /></Pagina>} />
            <Route path="/configuracao-notificacoes" element={<Pagina roles={GESTAO}><ConfiguracaoNotificacoes /></Pagina>} />
            <Route path="/tecnico" element={<Pagina roles={CAMPO} layout={false}><PortalTecnicoDashboard /></Pagina>} />
            <Route path="/tecnico/os/:id" element={<Pagina roles={CAMPO} layout={false}><ExecutarOrdemServico /></Pagina>} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </Router>
    </AuthProvider>
  );
}

export default App;
