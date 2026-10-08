import { MARCA_SIMULACAO } from "../utils/simulacaoDocumento";
import React, { useState, useEffect } from "react";
import {
  CheckCircle,
  AlertCircle,
  Clock,
  Loader,
  Plus,
  Search,
  RefreshCw,
  ChevronDown,
  X,
  Briefcase,
  Move,
  Eye,
  ThumbsUp,
  ThumbsDown,
  Archive,
  RotateCcw,
} from "lucide-react";
import api, { getApiErrorMessage } from "../services/api";
import OrdensServicoCard from "../components/OrdensServicoCard";
import StatusModal from "../components/StatusModal";
import FilaPendenciasOperacionais from "../components/FilaPendenciasOperacionais";
import PageHeader from "../components/PageHeader";
import Modal from "../components/Modal";
import { useAuth } from "../contexts/AuthContext";
import { PERMISSOES, temPermissao } from "../security/permissions";

const STATUS_COLUMNS = [
  {
    value: "PLANEJAMENTO",
    statuses: ["ABERTA", "AGUARDANDO_VISTORIA", "AGUARDANDO_RETIRADA"],
    label: "Preparação",
    color: "bg-blue-50",
    borderColor: "border-blue-200",
    dropColor: "hover:bg-blue-100/50",
  },
  {
    value: "EM_EXECUCAO",
    statuses: ["EM_EXECUCAO"],
    targetStatus: "EM_EXECUCAO",
    label: "Em Execução",
    color: "bg-yellow-50",
    borderColor: "border-yellow-200",
    dropColor: "hover:bg-yellow-100/50",
  },
  {
    value: "PENDENCIAS",
    statuses: ["AGUARDANDO_VALIDACAO", "AGUARDANDO_DEVOLUCAO", "AGUARDANDO_AUDITORIA", "AGUARDANDO_ENCERRAMENTO"],
    targetStatus: "AGUARDANDO_VALIDACAO",
    label: "Validação e fechamento",
    color: "bg-purple-50",
    borderColor: "border-purple-200",
    dropColor: "hover:bg-purple-100/50",
  },
  {
    value: "CONCLUIDA",
    statuses: ["CONCLUIDA"],
    label: "Concluída",
    color: "bg-green-50",
    borderColor: "border-green-200",
    dropColor: "hover:bg-green-100/50",
  },
  {
    value: "FATURADA",
    statuses: ["FATURADA"],
    targetStatus: "FATURADA",
    label: "Faturada",
    color: "bg-gray-50",
    borderColor: "border-gray-200",
    dropColor: "hover:bg-gray-200/50",
  },
];

const TRANSICOES_STATUS = {
  ABERTA: ["EM_EXECUCAO"],
  AGUARDANDO_VISTORIA: [],
  AGUARDANDO_RETIRADA: [],
  EM_EXECUCAO: ["AGUARDANDO_VALIDACAO"],
  AGUARDANDO_VALIDACAO: ["EM_EXECUCAO", "AGUARDANDO_DEVOLUCAO"],
  AGUARDANDO_DEVOLUCAO: [],
  AGUARDANDO_AUDITORIA: [],
  AGUARDANDO_ENCERRAMENTO: [],
  CONCLUIDA: ["FATURADA"],
  FATURADA: [],
};

const podeTransicionarStatus = (statusAtual, novoStatus) =>
  statusAtual === novoStatus || (TRANSICOES_STATUS[statusAtual] || []).includes(novoStatus);

export default function GestaoOrdensServico() {
  const { usuario } = useAuth();
  const podeGerenciar = temPermissao(usuario?.perfil, PERMISSOES.OS_GERENCIAR);
  const [ordensServico, setOrdensServico] = useState([]);
  const [projetos, setProjetos] = useState([]);
  const [comarcas, setComarcas] = useState([]);
  const [materiaisEstoque, setMateriaisEstoque] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Inputs de Filtros
  const [filterCliente, setFilterCliente] = useState("");
  const [filterNumeroOS, setFilterNumeroOS] = useState("");
  const [incluirArquivados, setIncluirArquivados] = useState(false);

  const [selectedOrdem, setSelectedOrdem] = useState(null);
  const [showStatusModal, setShowStatusModal] = useState(false);
  const [draggingOrdem, setDraggingOrdem] = useState(null);
  const [checklistModalOpen, setChecklistModalOpen] = useState(false);
  const [ordemChecklistFoco, setOrdemChecklistFoco] = useState(null);
  const [simulacaoHabilitada, setSimulacaoHabilitada] = useState(false);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [formData, setFormData] = useState({
    simulacao: false,
    descricao: "",
    projetoId: "",
    contratoId: "",
    dataHoraInicio: "",
    dataHoraFim: "",
    deadline: "",
    materiais: [{ materialId: "", quantidadePrevista: "" }],
  });

  const carregarAlvosNovaOs = async () => {
    try {
      const [projetosResponse, comarcasResponse, materiaisResponse, configuracaoResponse] = await Promise.all([
        api.get("/projetos"),
        api.get("/comarcas"),
        api.get("/estoque/materiais"),
        api.get("/documentos-internos/configuracao"),
      ]);
      setSimulacaoHabilitada(configuracaoResponse.data?.simulacaoHabilitada === true);
      setProjetos(projetosResponse.data || []);
      setComarcas(comarcasResponse.data || []);
      setMateriaisEstoque(materiaisResponse.data || []);
    } catch (err) {
      console.error("Erro ao puxar árvore de projetos/comarcas:", err);
      setError(getApiErrorMessage(err, "Não foi possível carregar os projetos/comarcas para abertura de OS."));
    }
  };

  // 💥 1. Carrega a lista estática de projetos/comarcas no mount da tela
  useEffect(() => {
    if (podeGerenciar) carregarAlvosNovaOs();
  }, [podeGerenciar]);

  // 💥 2. ENGINE DE DEBOUNCE: Aguarda 400ms após o término da digitação para consultar o Postgres
  useEffect(() => {
    setLoading(true);
    const delayDebounceFn = setTimeout(() => {
      buscarOrdensFilttradas();
    }, 400);

    return () => clearTimeout(delayDebounceFn);
  }, [filterNumeroOS, filterCliente, incluirArquivados]);

  const buscarOrdensFilttradas = async () => {
    setError(null);
    try {
      // Repassa os inputs limpos via query params na URL do Axios ⚡
      const response = await api.get("/ordens-servico", {
        params: {
          numeroOs: filterNumeroOS.trim(),
          cliente: filterCliente.trim(),
          incluirArquivados,
        },
      });
      setOrdensServico(response.data || []);
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao sincronizar dados com a central de filtros do banco."));
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  // DRAG & DROP NATIVO
  const handleDragStart = (e, ordem) => {
    setDraggingOrdem(ordem);
  };

  const handleDrop = async (e, targetStatus) => {
    e.preventDefault();
    if (!draggingOrdem || !targetStatus || draggingOrdem.status === targetStatus) return;

    if (!podeTransicionarStatus(draggingOrdem.status, targetStatus)) {
      alert(`A OS não pode passar diretamente de ${draggingOrdem.status} para ${targetStatus}.`);
      setDraggingOrdem(null);
      return;
    }

    const statusOrigem = draggingOrdem.status;
    const ordemId = draggingOrdem.id;

    setOrdensServico((prev) =>
      prev.map((o) => (o.id === ordemId ? { ...o, status: targetStatus } : o)),
    );
    setDraggingOrdem(null);

    try {
      await api.put(`/ordens-servico/${ordemId}/status`, {
        status: targetStatus,
      });
    } catch (err) {
      console.error(err);
      setOrdensServico((prev) =>
        prev.map((o) =>
          o.id === ordemId ? { ...o, status: statusOrigem } : o,
        ),
      );
      alert(getApiErrorMessage(err, "Não foi possível alterar o status. A movimentação foi desfeita."));
    }
  };

  const transicionarStatusDireto = async (ordemId, novoStatus) => {
    try {
      await api.put(`/ordens-servico/${ordemId}/status`, {
        status: novoStatus,
      });
      setChecklistModalOpen(false);
      buscarOrdensFilttradas();
    } catch (err) {
      console.error(err);
      alert(getApiErrorMessage(err, "Erro ao alterar o status do chamado no banco."));
    }
  };

  const handleCriarOS = async (e) => {
    e.preventDefault();
    if (
      !formData.projetoId ||
      !formData.contratoId ||
      !formData.descricao.trim() ||
      !formData.dataHoraInicio ||
      !formData.dataHoraFim ||
      !formData.deadline ||
      !formData.materiais.length ||
      formData.materiais.some(
        (item) => !item.materialId || Number(item.quantidadePrevista) <= 0,
      )
    ) {
      alert("Preencha o projeto, a descrição, os prazos e ao menos um material previsto.");
      return;
    }

    const inicio = new Date(formData.dataHoraInicio);
    const fim = new Date(formData.dataHoraFim);
    const prazo = new Date(formData.deadline);
    if (fim < inicio) {
      alert("A data e hora de fim não pode ser anterior ao início.");
      return;
    }
    if (prazo < fim) {
      alert("O prazo limite não pode ser anterior ao fim planejado da OS.");
      return;
    }

    const idsMateriais = formData.materiais.map((item) => String(item.materialId));
    if (new Set(idsMateriais).size !== idsMateriais.length) {
      alert("O mesmo material não pode ser adicionado mais de uma vez. Ajuste a quantidade em um único item.");
      return;
    }

    const validacaoSaldo = validarSaldoMateriaisPrevistos();
    if (!validacaoSaldo.ok) {
      alert(validacaoSaldo.mensagem);
      return;
    }

    try {
      await api.post("/ordens-servico", {
        ...formData,
        materiais: formData.materiais.map((item) => ({
          materialId: Number(item.materialId),
          quantidadePrevista: Number(item.quantidadePrevista),
        })),
      });
      setCreateModalOpen(false);
      await Promise.all([buscarOrdensFilttradas(), carregarAlvosNovaOs()]);
    } catch (err) {
      console.error(err);
      alert(
        getApiErrorMessage(err, "Erro ao salvar ordem de serviço vinculada no banco."),
      );
    }
  };

  const alterarArquivamento = async (ordem) => {
    try {
      if (ordem.arquivado) {
        await api.patch(`/ordens-servico/${ordem.id}/restaurar`);
      } else {
        const motivo = window.prompt("Informe o motivo para arquivar esta OS:");
        if (!motivo?.trim()) return;
        await api.patch(`/ordens-servico/${ordem.id}/arquivar`, {
          usuario: usuario?.email || usuario?.nome,
          motivo: motivo.trim(),
        });
      }
      buscarOrdensFilttradas();
    } catch (err) {
      alert(getApiErrorMessage(err, "Não foi possível alterar o arquivamento da OS."));
    }
  };

  const handleRepararVinculos = async () => {
    try {
      setLoading(true);
      const response = await api.post(
        "/ordens-servico/reparar-vinculos-comarcas",
      );
      await Promise.all([buscarOrdensFilttradas(), carregarAlvosNovaOs()]);
      const resumo = response.data || {};
      alert(
        `Sincronização concluída. OS vinculadas: ${resumo.ordensVinculadas || 0}. Materiais criados: ${resumo.materiaisCriados || 0}. Materiais atualizados: ${resumo.materiaisAtualizados || 0}. Conflitos: ${resumo.conflitos || 0}.`,
      );
    } catch (err) {
      console.error(err);
      setError(
        getApiErrorMessage(err, "Erro ao sincronizar vínculos entre OS, comarcas e materiais."),
      );
    } finally {
      setLoading(false);
    }
  };

  const quantidadeMaterial = (valor) => Number(valor || 0);

  const controlaMetragem = (material) =>
    ["FRACIONADO", "METRAGEM", "BOBINA", "ROLO"].includes(material?.tipoControle);
  const unidadeMaterial = (material) => (controlaMetragem(material) ? "m" : "un");
  const saldoEstoqueMaterial = (material) =>
    controlaMetragem(material)
      ? quantidadeMaterial(material?.metragemDisponivel)
      : quantidadeMaterial(material?.quantidadeDisponivel);
  const saldoReservadoMaterial = (material) =>
    controlaMetragem(material)
      ? quantidadeMaterial(material?.metragemReservada)
      : quantidadeMaterial(material?.quantidadeReservada);

  const saldoLivreMaterial = (material) => {
    if (!material) return 0;
    return Math.max(
      0,
      saldoEstoqueMaterial(material) - saldoReservadoMaterial(material),
    );
  };

  const materialPorId = (materialId) =>
    materiaisEstoque.find((material) => Number(material.id) === Number(materialId));

  const validarSaldoMateriaisPrevistos = () => {
    const totaisPorMaterial = formData.materiais.reduce((acc, item) => {
      const materialId = Number(item.materialId);
      if (!materialId) return acc;
      acc[materialId] =
        (acc[materialId] || 0) + quantidadeMaterial(item.quantidadePrevista);
      return acc;
    }, {});

    for (const [materialId, totalPrevisto] of Object.entries(totaisPorMaterial)) {
      const material = materialPorId(materialId);
      const saldoLivre = saldoLivreMaterial(material);
      if (totalPrevisto > saldoLivre) {
        return {
          ok: false,
          mensagem: `Estoque livre insuficiente para ${material?.nome || "material selecionado"}. Disponível para nova OS: ${saldoLivre}. Quantidade prevista: ${totalPrevisto}.`,
        };
      }
    }

    return { ok: true };
  };

  const abrirModalStatus = (ordem) => {
    setSelectedOrdem(ordem);
    setShowStatusModal(true);
  };

  const fecharModalStatus = () => {
    setShowStatusModal(false);
    setSelectedOrdem(null);
  };

  const handleStatusUpdated = (ordemAtualizada) => {
    setOrdensServico((prevOrdens) =>
      prevOrdens.map((ordem) =>
        ordem.id === ordemAtualizada.id ? ordemAtualizada : ordem,
      ),
    );
    fecharModalStatus();
  };

  const renderConteudoChecklist = (textoChecklist) => {
    if (!textoChecklist) {
      return (
        <div className="text-center py-8 text-gray-400 italic text-sm bg-gray-50 rounded-xl border border-dashed">
          Nenhum relatório técnico preenchido em campo para esta OS.
        </div>
      );
    }

    if (textoChecklist.trim().startsWith("{")) {
      try {
        const dadosJson = JSON.parse(textoChecklist);
        const renderValor = (valor) => {
          if (typeof valor === "boolean") {
            return valor ? "Conforme" : "Inconforme";
          }
          if (Array.isArray(valor)) {
            if (valor.length === 0) return "Nenhum item registrado";
            return (
              <ul className="space-y-2 text-left">
                {valor.map((item, index) => {
                  if (item && typeof item === "object") {
                    const descricao =
                      item.titulo || item.descricao || item.nome || item.atividade || `Item ${index + 1}`;
                    return (
                      <li key={item.id || `${descricao}-${index}`} className="flex items-start gap-2">
                        <span className={`mt-0.5 font-black ${item.concluida === false ? "text-amber-600" : "text-emerald-600"}`}>
                          {item.concluida === false ? "Pendente" : "Concluída"}
                        </span>
                        <span>
                          {descricao}
                          {item.categoria && (
                            <small className="ml-2 font-semibold uppercase text-gray-400">
                              {item.categoria}
                            </small>
                          )}
                        </span>
                      </li>
                    );
                  }
                  return <li key={`${String(item)}-${index}`}>{String(item)}</li>;
                })}
              </ul>
            );
          }
          if (valor && typeof valor === "object") {
            return Object.entries(valor)
              .map(([chave, conteudo]) => `${chave}: ${String(conteudo)}`)
              .join(" | ");
          }
          return String(valor ?? "");
        };
        return (
          <div className="space-y-3 bg-gray-50 p-4 rounded-xl border border-gray-200">
            {Object.entries(dadosJson).map(([chave, valor]) => (
              <div
                key={chave}
                className="flex justify-between items-center py-2 border-b border-gray-200/60 last:border-none"
              >
                <span className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                  {chave.replace(/_/g, " ")}
                </span>
                <span className="text-sm font-semibold text-gray-800">
                  {renderValor(valor)}
                </span>
              </div>
            ))}
          </div>
        );
      } catch (err) {}
    }

    return (
      <div className="bg-gray-50 p-4 rounded-xl border border-gray-200 text-sm text-gray-700 leading-relaxed whitespace-pre-line font-medium shadow-inner">
        {textoChecklist}
      </div>
    );
  };

  const agruparPorStatus = () => {
    const agrupado = {};
    STATUS_COLUMNS.forEach((col) => {
      // Agrupa usando a massa de dados que veio já limpa e filtrada do backend! ⚡
      agrupado[col.value] = ordensServico.filter(
        (ordem) => col.statuses.includes(ordem.status || "ABERTA"),
      );
    });
    return agrupado;
  };

  const ordensPorStatus = agruparPorStatus();
  const comarcasPorProjetoId = new Map(
    comarcas
      .filter((comarca) => comarca.projeto?.id)
      .map((comarca) => [Number(comarca.projeto.id), comarca]),
  );
  const projetosDisponiveis = projetos.filter((projeto) => {
    const comarca = comarcasPorProjetoId.get(Number(projeto.id));
    return comarca && !comarca.ordemServico;
  });
  const projetosComResponsavel = projetosDisponiveis.filter(
    (projeto) => projeto.responsavel?.id,
  );

  const atualizarMaterialOs = (index, campo, valor) => {
    setFormData((prev) => ({
      ...prev,
      materiais: prev.materiais.map((item, itemIndex) =>
        itemIndex === index ? { ...item, [campo]: valor } : item,
      ),
    }));
  };

  const adicionarLinhaMaterialOs = () => {
    setFormData((prev) => ({
      ...prev,
      materiais: [...prev.materiais, { materialId: "", quantidadePrevista: "" }],
    }));
  };

  const removerLinhaMaterialOs = (index) => {
    setFormData((prev) => ({
      ...prev,
      materiais:
        prev.materiais.length > 1
          ? prev.materiais.filter((_, itemIndex) => itemIndex !== index)
          : prev.materiais,
    }));
  };

  const abrirModalCriacao = () => {
    const pInicial = projetosComResponsavel[0];
    if (!pInicial) {
      alert("Não há projeto/comarca livre com funcionário responsável. Atribua o responsável na página Projetos antes de emitir a OS.");
      return;
    }

    setFormData({
      simulacao: false,
    descricao: "",
      projetoId: pInicial.id,
      contratoId: pInicial.contrato?.id || "",
      dataHoraInicio: "",
      dataHoraFim: "",
      deadline: "",
      materiais: [{ materialId: "", quantidadePrevista: "" }],
    });
    setCreateModalOpen(true);
  };

  return (
    <div className="mx-auto max-w-[1600px] space-y-5">
      <PageHeader
        eyebrow="Operação"
        title="Ordens de Serviço"
        description="Emita, priorize e acompanhe cada ordem do planejamento ao faturamento."
        actions={podeGerenciar ? (
          <button onClick={abrirModalCriacao} disabled={projetosComResponsavel.length === 0} className="flex items-center justify-center gap-2 rounded bg-blue-600 px-4 py-2 text-sm font-bold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300"><Plus className="h-4 w-4" /> Nova OS</button>
        ) : null}
      />

        <section className="rounded-lg border border-slate-200 bg-white p-4">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto_auto]">
            <div className="relative">
              <Search className="absolute left-3 top-3 text-gray-400 w-5 h-5" />
              <input
                type="text"
                placeholder="Filtrar por nº OS..."
                value={filterNumeroOS}
                onChange={(e) => setFilterNumeroOS(e.target.value)}
                className="w-full rounded border border-slate-300 py-2 pl-10 pr-4 text-sm outline-none focus:border-blue-500"
              />
            </div>

            <div className="relative">
              <Search className="absolute left-3 top-3 text-gray-400 w-5 h-5" />
              <input
                type="text"
                placeholder="Filtrar por cliente..."
                value={filterCliente}
                onChange={(e) => setFilterCliente(e.target.value)}
                className="w-full rounded border border-slate-300 py-2 pl-10 pr-4 text-sm outline-none focus:border-blue-500"
              />
            </div>

            {podeGerenciar && <button
              onClick={handleRepararVinculos}
              className="flex items-center justify-center gap-2 rounded border border-slate-300 bg-white px-4 py-2 text-sm font-bold text-slate-700 hover:bg-slate-50"
            >
              <RefreshCw className="w-4 h-4" /> Sincronizar
            </button>}

            {podeGerenciar && <label className="flex items-center justify-center gap-2 rounded border border-slate-200 px-3 text-xs font-bold text-slate-600">
              <input
                type="checkbox"
                checked={incluirArquivados}
                onChange={(event) => setIncluirArquivados(event.target.checked)}
              />
              Mostrar arquivadas
            </label>}
          </div>

          {podeGerenciar && projetosComResponsavel.length === 0 && (
            <div className="mt-3 text-xs font-semibold text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
              Nenhuma comarca livre com funcionário responsável. Edite o projeto,
              atribua um funcionário e depois retorne para emitir a OS.
            </div>
          )}

          {loading && (filterNumeroOS || filterCliente) && (
            <div className="mt-2 text-[10px] text-blue-500 animate-pulse font-bold uppercase tracking-wider">
              Consultando tabelas do Postgres...
            </div>
          )}
        </section>

      {error && (
          <div className="bg-red-50 border border-red-200 text-red-700 px-6 py-4 rounded-lg flex items-center gap-3 mb-6 text-sm">
            <AlertCircle className="w-5 h-5 flex-shrink-0" />
            <span>{error}</span>
          </div>
      )}

      <section className="grid grid-cols-2 gap-3 lg:grid-cols-5">
        {STATUS_COLUMNS.map((coluna) => (
          <div key={coluna.value} className="rounded-lg border border-slate-200 bg-white p-3.5">
            <div className="flex items-center justify-between gap-2">
              <p className="truncate text-xs font-semibold text-slate-500">{coluna.label}</p>
              <span className={`h-2.5 w-2.5 shrink-0 rounded-sm ${coluna.color.replace("50", "500")}`} />
            </div>
            <p className="mt-2 text-2xl font-bold text-slate-900">{ordensPorStatus[coluna.value]?.length || 0}</p>
          </div>
        ))}
      </section>

      {podeGerenciar && <div>
        <FilaPendenciasOperacionais area="ADMINISTRACAO" titulo="Validações administrativas pendentes" limite={4} recolhivel inicialmenteAberta={false} />
      </div>}

      {/* Kanban Board */}
      <div className="flex gap-4 overflow-x-auto pb-3">
        {STATUS_COLUMNS.map((coluna) => (
          <div key={coluna.value} className="w-full min-w-[280px] flex-1 space-y-2 lg:w-[300px] lg:flex-none">
            <div
              className={`${coluna.color} border ${coluna.borderColor} rounded p-3`}
            >
              <div className="flex items-center justify-between mb-2">
                <h2 className="font-bold text-gray-800 text-sm tracking-wide">
                  {coluna.label}
                </h2>
                <span className="rounded bg-white/70 px-2 py-0.5 text-xs font-bold text-slate-700">
                  {ordensPorStatus[coluna.value]?.length || 0}
                </span>
              </div>
              <div className="w-full h-1 bg-gray-200 rounded-full" />
            </div>

            <div
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => handleDrop(e, coluna.targetStatus)}
              className={`min-h-[380px] space-y-2 rounded border border-dashed border-slate-300 bg-slate-100/50 p-2 transition-colors ${coluna.dropColor}`}
            >
              {ordensPorStatus[coluna.value]?.length > 0 ? (
                ordensPorStatus[coluna.value].map((ordem) => (
                  <div
                    key={ordem.id}
                    draggable={!ordem.arquivado && (TRANSICOES_STATUS[ordem.status] || []).length > 0}
                    onDragStart={(e) => handleDragStart(e, ordem)}
                    className={`group relative flex flex-col overflow-hidden rounded border border-slate-200 bg-white shadow-sm transition ${ordem.arquivado ? "opacity-60" : "cursor-grab active:cursor-grabbing"}`}
                  >
                    <div className="absolute top-2 right-2 opacity-0 group-hover:opacity-40 transition-opacity text-gray-500">
                      <Move size={14} />
                    </div>

                    <div className="p-1 flex-1">
                      <OrdensServicoCard
                        ordem={ordem}
                        onAtualizarStatus={() => abrirModalStatus(ordem)}
                      />
                    </div>

                    <div className="bg-gray-50 px-4 py-2 border-t border-gray-100 flex justify-between items-center text-xs">
                      <span className="text-gray-400 font-mono text-[10px]">
                        OS #{ordem.id}{ordem.arquivado ? " · ARQUIVADA" : ""}
                      </span>
                      <div className="flex items-center gap-3">
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          setOrdemChecklistFoco(ordem);
                          setChecklistModalOpen(true);
                        }}
                        className="text-blue-600 hover:text-blue-800 font-bold flex items-center gap-1 transition-colors"
                      >
                        <Eye size={12} /> <span>Ver Relatório</span>
                      </button>
                      {podeGerenciar && <button
                        onClick={(event) => {
                          event.stopPropagation();
                          alterarArquivamento(ordem);
                        }}
                        title={ordem.arquivado ? "Restaurar OS" : "Arquivar OS"}
                        className={ordem.arquivado ? "text-emerald-600" : "text-red-600"}
                      >
                        {ordem.arquivado ? <RotateCcw size={13} /> : <Archive size={13} />}
                      </button>}
                      </div>
                    </div>
                  </div>
                ))
              ) : (
                <div className="text-center py-12 text-gray-400 pointers-events-none">
                  <p className="text-xs font-medium">Solte chamados aqui</p>
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

      {/* MODAL CHECKLIST */}
      {checklistModalOpen && ordemChecklistFoco && (
        <Modal isOpen={checklistModalOpen} onClose={() => setChecklistModalOpen(false)} title="Relatório Técnico de Campo">
            <p className="mb-4 break-words text-xs text-gray-500">Código OS: {ordemChecklistFoco.numeroOs}</p>
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4 text-xs bg-gray-50 p-3 rounded-lg border border-gray-100 font-medium text-gray-500">
                <div>
                  <span className="block text-[10px] text-gray-400 uppercase font-bold">
                    Projeto
                  </span>
                  <span className="text-gray-800 font-semibold text-xs">
                    Projeto #{ordemChecklistFoco.projeto?.id || "N/A"}
                  </span>
                </div>
                <div>
                  <span className="block text-[10px] text-gray-400 uppercase font-bold">
                    Assinatura Digital
                  </span>
                  <span
                    className={`font-semibold text-xs ${ordemChecklistFoco.assinaturaDigital ? "text-emerald-600" : "text-amber-600"}`}
                  >
                    {ordemChecklistFoco.assinaturaDigital
                      ? "Homologada via App"
                      : "Pendente"}
                  </span>
                </div>
              </div>

              <div>
                <label className="text-[10px] font-black text-gray-400 uppercase tracking-wider block mb-1.5">
                  Escopo Original Solicitado
                </label>
                <div className="p-3 bg-gray-100/50 text-xs text-gray-600 rounded-xl border border-gray-200/60 italic">
                  "{ordemChecklistFoco.descricao || "Sem descrição cadastrada."}
                  "
                </div>
              </div>

              <div>
                <label className="text-[10px] font-black text-gray-400 uppercase tracking-wider block mb-1.5">
                  Checklist / Resposta do Técnico
                </label>
                {renderConteudoChecklist(ordemChecklistFoco.checklist)}
              </div>
            </div>

            <div className="p-4 bg-gray-50 border-t border-gray-100 flex gap-3">
              {ordemChecklistFoco.status === "AGUARDANDO_VALIDACAO" && (
                <>
                  <button
                    onClick={() =>
                      transicionarStatusDireto(
                        ordemChecklistFoco.id,
                        "EM_EXECUCAO",
                      )
                    }
                    className="flex-1 bg-white hover:bg-rose-50 border border-gray-200 text-rose-600 py-2 rounded-xl text-xs font-bold transition flex items-center justify-center gap-1.5 shadow-sm"
                  >
                    <ThumbsDown size={14} /> Recusar Relatório
                  </button>
                  <button
                    onClick={() =>
                      transicionarStatusDireto(
                        ordemChecklistFoco.id,
                        "AGUARDANDO_DEVOLUCAO",
                      )
                    }
                    className="flex-1 bg-blue-600 hover:bg-blue-700 text-white py-2 rounded-xl text-xs font-bold transition flex items-center justify-center gap-1.5 shadow-md"
                  >
                    <ThumbsUp size={14} /> Aprovar OS
                  </button>
                </>
              )}
              {ordemChecklistFoco.status !== "AGUARDANDO_VALIDACAO" && (
                <button
                  onClick={() => setChecklistModalOpen(false)}
                  className="w-full bg-gray-800 hover:bg-gray-900 text-white py-2 rounded-xl text-xs font-bold transition"
                >
                  Fechar Auditoria
                </button>
              )}
            </div>
        </Modal>
      )}

      {/* MODAL CRIAÇÃO */}
      {createModalOpen && (
        <Modal isOpen={createModalOpen} onClose={() => setCreateModalOpen(false)} title="Abrir Nova Ordem de Serviço">
            <form onSubmit={handleCriarOS} className="space-y-4">
              {simulacaoHabilitada && <label className="block rounded border border-amber-300 bg-amber-50 p-3 text-sm">
                <input type="checkbox" checked={formData.simulacao} onChange={e => setFormData(prev => ({ ...prev, simulacao: e.target.checked }))} />
                <strong className="ml-2">Criar OS de simulação documental</strong>
                <p>{MARCA_SIMULACAO}. Escolha permanente nesta OS; OR, vistoria e encerramento serão fictícios. Use um projeto de TESTE; as regras e movimentos de estoque permanecem obrigatórios.</p>
              </label>}

              <div>
                <label className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                  Código / Número da OS
                </label>
                <div className="mt-1 rounded-lg border border-dashed border-blue-200 bg-blue-50 px-3 py-2 text-sm font-semibold text-blue-800">
                  Gerado automaticamente pelo contrato: Contrato - OS sequencial
                </div>
              </div>

              <div>
                <label className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                  Projeto / Comarca Alvo *
                </label>
                <select
                  required
                  value={formData.projetoId}
                  onChange={(e) => {
                    const projId = e.target.value;
                    const projSelecionado = projetosDisponiveis.find(
                      (p) => p.id == projId,
                    );
                    setFormData({
                      ...formData,
                      projetoId: projId,
                      contratoId: projSelecionado?.contrato?.id || "",
                    });
                  }}
                  className="w-full mt-1 p-2.5 border border-gray-300 rounded-lg text-sm bg-gray-50 outline-none focus:ring-2 focus:ring-blue-500"
                >
                  {projetosDisponiveis.map((p) => {
                    const comarca = comarcasPorProjetoId.get(Number(p.id));
                    return (
                    <option key={p.id} value={p.id} disabled={!p.responsavel?.id}>
                      Projeto #{p.id} -{" "}
                      {comarca?.nomeComarca || p.nomeComarcaVinculada || "Infra"} (
                      {p.contrato?.cliente}) - {p.responsavel?.nome || "sem responsável"}
                    </option>
                    );
                  })}
                </select>
              </div>

              <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                <div>
                  <label className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                    Início *
                  </label>
                  <input
                    type="datetime-local"
                    required
                    value={formData.dataHoraInicio}
                    onChange={(e) =>
                      setFormData({ ...formData, dataHoraInicio: e.target.value })
                    }
                    className="w-full mt-1 p-2.5 border border-gray-300 rounded-lg text-sm bg-gray-50 outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div>
                  <label className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                    Fim *
                  </label>
                  <input
                    type="datetime-local"
                    required
                    min={formData.dataHoraInicio || undefined}
                    value={formData.dataHoraFim}
                    onChange={(e) =>
                      setFormData({ ...formData, dataHoraFim: e.target.value })
                    }
                    className="w-full mt-1 p-2.5 border border-gray-300 rounded-lg text-sm bg-gray-50 outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                <div>
                  <label className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                    Deadline *
                  </label>
                  <input
                    type="datetime-local"
                    required
                    min={formData.dataHoraFim || undefined}
                    value={formData.deadline}
                    onChange={(e) =>
                      setFormData({ ...formData, deadline: e.target.value })
                    }
                    className="w-full mt-1 p-2.5 border border-gray-300 rounded-lg text-sm bg-gray-50 outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
              </div>

              <div>
                <label className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                  Descrição do Chamado Técnico *
                </label>
                <textarea
                  rows="3"
                  required
                  value={formData.descricao}
                  onChange={(e) =>
                    setFormData({ ...formData, descricao: e.target.value })
                  }
                  className="w-full mt-1 p-2.5 border border-gray-300 rounded-lg text-sm bg-gray-50 outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder="Descreva o escopo da manutenção..."
                ></textarea>
              </div>

              <div className="rounded-xl border border-gray-200 bg-gray-50 p-3">
                <div className="mb-3 flex items-center justify-between gap-3">
                  <div>
                    <h3 className="text-xs font-black uppercase tracking-wider text-gray-600">
                      Materiais previstos *
                    </h3>
                    <p className="text-[11px] text-gray-500">
                      Defina os materiais da obra antes de emitir a OS.
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={adicionarLinhaMaterialOs}
                    className="shrink-0 rounded-lg bg-white px-3 py-1.5 text-xs font-bold text-blue-700 shadow-sm ring-1 ring-blue-100 hover:bg-blue-50"
                  >
                    + Material
                  </button>
                </div>

                <div className="space-y-2">
                  {formData.materiais.map((item, index) => {
                    const materialSelecionado = materialPorId(item.materialId);
                    const saldoLivreSelecionado = saldoLivreMaterial(materialSelecionado);
                    const quantidadePrevista = quantidadeMaterial(item.quantidadePrevista);
                    const quantidadeInvalida =
                      materialSelecionado && quantidadePrevista > saldoLivreSelecionado;

                    return (
                      <div key={index} className="space-y-1">
                        <div className="grid grid-cols-[minmax(0,1fr)_32px] items-center gap-2 sm:grid-cols-[minmax(0,1fr)_92px_32px]">
                          <select
                            aria-label={`Material ${index + 1}`}
                            required
                            value={item.materialId}
                            onChange={(e) =>
                              atualizarMaterialOs(index, "materialId", e.target.value)
                            }
                            className="col-span-2 min-w-0 w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-blue-500 sm:col-span-1"
                          >
                            <option value="">Selecione o material</option>
                            {materiaisEstoque.map((material) => {
                              const saldoLivre = saldoLivreMaterial(material);
                              const reservado = saldoReservadoMaterial(material);
                              const emEstoque = saldoEstoqueMaterial(material);
                              const unidade = unidadeMaterial(material);
                              const selecionadoEmOutraLinha = formData.materiais.some(
                                (outroItem, outroIndex) =>
                                  outroIndex !== index &&
                                  String(outroItem.materialId) === String(material.id),
                              );
                              return (
                                <option key={material.id} value={material.id} disabled={selecionadoEmOutraLinha}>
                                  {material.nome} ({saldoLivre} {unidade} disponível para OS, {reservado} {unidade} reservado, {emEstoque} {unidade} em estoque)
                                </option>
                              );
                            })}
                          </select>
                          <input
                            aria-label={`Quantidade do material ${index + 1}`}
                            type="number"
                            min={controlaMetragem(materialSelecionado) ? "0.001" : "1"}
                            max={materialSelecionado ? saldoLivreSelecionado : undefined}
                            step={controlaMetragem(materialSelecionado) ? "0.001" : "1"}
                            required
                            value={item.quantidadePrevista}
                            onChange={(e) =>
                              atualizarMaterialOs(
                                index,
                                "quantidadePrevista",
                                e.target.value,
                              )
                            }
                            className={`w-full rounded-lg border bg-white px-3 py-2 text-sm outline-none focus:ring-2 ${
                              quantidadeInvalida
                                ? "border-rose-300 focus:ring-rose-500"
                                : "border-gray-300 focus:ring-blue-500"
                            }`}
                            placeholder="Qtd."
                          />
                          <button
                            type="button"
                            onClick={() => removerLinhaMaterialOs(index)}
                            disabled={formData.materiais.length === 1}
                            className="flex h-9 w-8 items-center justify-center rounded-lg text-gray-400 hover:bg-white hover:text-rose-600 disabled:opacity-30"
                            title="Remover material"
                          >
                            <X size={16} />
                          </button>
                        </div>
                        {materialSelecionado && (
                          <p
                            className={`text-[10px] font-semibold ${
                              quantidadeInvalida ? "text-rose-600" : "text-gray-500"
                            }`}
                          >
                            Disponível para nova OS: {saldoLivreSelecionado} {unidadeMaterial(materialSelecionado)}. Em estoque:{" "}
                            {saldoEstoqueMaterial(materialSelecionado)} {unidadeMaterial(materialSelecionado)}.
                            Reservado:{" "}
                            {saldoReservadoMaterial(materialSelecionado)} {unidadeMaterial(materialSelecionado)}.
                          </p>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>

              <button
                type="submit"
                className="w-full bg-blue-600 hover:bg-blue-700 text-white py-2.5 rounded-lg font-bold text-sm transition-all shadow-sm"
              >
                Emitir Ordem de Serviço
              </button>
            </form>
        </Modal>
      )}

      {/* Modal de Status */}
      {showStatusModal && selectedOrdem && (
        <StatusModal
          ordem={selectedOrdem}
          statusPermitidos={TRANSICOES_STATUS[selectedOrdem.status] || []}
          onClose={fecharModalStatus}
          onStatusAtualizado={handleStatusUpdated}
        />
      )}
    </div>
  );
}
