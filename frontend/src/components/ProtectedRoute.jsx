import { Navigate, useLocation } from "react-router-dom";
import { useAuth, homePorPerfil } from "../contexts/AuthContext";

export default function ProtectedRoute({ children, roles }) {
  const { usuario, carregando, configuracao } = useAuth();
  const location = useLocation();

  if (carregando) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-950 text-sm text-slate-300">
        Validando sessão...
      </div>
    );
  }
  if (!usuario) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (configuracao.securityEnabled && usuario.trocaSenhaObrigatoria && location.pathname !== "/perfil") {
    return <Navigate to="/perfil?trocarSenha=1" replace />;
  }
  if (roles && !roles.includes(usuario.perfil)) {
    return <Navigate to={homePorPerfil(usuario.perfil)} replace state={{ acessoNegado: true }} />;
  }
  return children;
}
