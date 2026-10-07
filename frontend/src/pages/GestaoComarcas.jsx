import { MARCA_SIMULACAO, EMPRESA_FICTICIA, CAMPOS_IDENTIDADE_FICTICIA, camposFicticios } from "../utils/simulacaoDocumento";
import { useState, useEffect, useRef } from "react";
import {
  AlertTriangle,
  MapPin,
  User,
  Zap,
  History,
  FileText,
  Printer,
  Upload,
  CheckCircle2,
  PenTool,
  Package,
  ShieldCheck,
  Save,
  Trash2,
  Archive,
  RotateCcw,
} from "lucide-react";
import api from "../services/api";
import Modal from "../components/Modal";
import LoadingSpinner from "../components/LoadingSpinner";
import Alert from "../components/Alert";
import HistoricoAtividadesComarca from "../components/HistoricoAtividadesComarca";
import FilaPendenciasOperacionais from "../components/FilaPendenciasOperacionais";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import { API_BASE_URL, buildApiFileUrl } from "../services/runtimeConfig";
import rcLogo from "../assets/rclogo.jpg";
import { useAuth } from "../contexts/AuthContext";
import { PERMISSOES, temPermissao } from "../security/permissions";
import { formatarCpf } from "../utils/cpf";

const DOCUMENTO_INICIAL = "VISTORIA_INICIAL_OS";
const DOCUMENTO_FINAL = "ENCERRAMENTO_OS";
const ASSINATURAS_DOCUMENTO = [
  {
    papel: "TECNICO",
    label: "Técnico responsável",
    campoAssinatura: "assinaturaTecnicoBase64",
    campoNome: "tecnicoAssinadoPor",
    campoData: "dataAssinaturaTecnico",
  },
  {
    papel: "GESTOR_RC",
    label: "Gestor do projeto RC",
    campoAssinatura: "assinaturaGestorBase64",
    campoNome: "gestorAssinadoPor",
    campoData: "dataAssinaturaGestor",
  },
  {
    papel: "GERENTE_FORUM",
    label: "Gerente do fórum",
    campoAssinatura: "assinaturaGerenteBase64",
    campoNome: "gerenteAssinadoPor",
    campoData: "dataAssinaturaGerente",
  },
];

const getCategoriaMaterialLabel = (categoria) =>
  categoria === "FERRAMENTA" ? "Ferramenta" : "Material de Consumo";

const OBJETO_SERVICO_OPCOES = [
  "Instalação de cabeamento estruturado",
  "Instalação de eletrocalhas",
  "Instalação de canaletas",
  "Instalação de eletrodutos",
  "Instalação de patch panel e conectorização de pontos",
  "Conectorização de patch cords nos ativos",
  "Organização e identificação de racks",
  "Lançamento de cabos de rede CAT6A",
  "Lançamento de cabos de fibra",
  "Instalação de DIO e/ou Cassete",
  "Organização de equipamentos no rack",
];

const ESTADO_INICIAL_OPCOES = [
  "Foi realizado registro fotográfico completo do ambiente",
  "Foram registradas anomalias estruturais pré-existentes",
  "Eventuais irregularidades foram comunicadas formalmente à gestão do projeto",
  "Foram registradas as condições de funcionamento dos equipamentos",
  "Computadores, impressoras e telefones foram verificados",
];

const RACK_NECESSIDADE_OPCOES = [
  ["HA_NECESSIDADE", "HÁ necessidade de substituição do rack da comarca"],
  ["NAO_HA_NECESSIDADE", "NÃO HÁ necessidade de substituição do rack da comarca"],
];

const RACK_DISPONIBILIDADE_OPCOES = [
  ["PRESENTE", "O rack de substituição já está presente na comarca"],
  ["NAO_EXISTE", "Não existe rack de substituição na comarca"],
];

const ESTADO_FINAL_OPCOES = [
  "O ambiente foi entregue limpo e organizado",
  "Não houve dano estrutural decorrente da execução",
  "O forro foi reinstalado adequadamente",
  "O telhado foi restabelecido ao estado original de acesso",
  "As canaletas e eletrocalhas/eletrodutos foram fixadas conforme padrão técnico",
  "Os cabos foram identificados conforme manual do fabricante",
  "O ambiente foi apresentado ao gerente do fórum",
  "Todos os equipamentos presentes nas salas estão no mesmo estado da entrada",
  "Foi realizado registro fotográfico final antes e depois",
];

export default function GestaoComarcas() {
  const { usuario } = useAuth();
  const podeExecutarObra = temPermissao(usuario?.perfil, PERMISSOES.OBRAS_EXECUTAR);
  const podeGerenciarObra = temPermissao(usuario?.perfil, PERMISSOES.OBRAS_GERENCIAR);
  const podeGerarOr = temPermissao(usuario?.perfil, PERMISSOES.OR_GERENCIAR);
  const podeEditarDocumento = temPermissao(usuario?.perfil, PERMISSOES.DOCUMENTOS_EDITAR);
  const USUARIO_ATUAL = usuario?.email || usuario?.nome || "Sistema";
  const [comarcas, setComarcas] = useState([]);
  const [filtroEtapa, setFiltroEtapa] = useState("TODAS");
  const [incluirArquivados, setIncluirArquivados] = useState(false);
  const [materiaisEstoque, setMateriaisEstoque] = useState([]);
  const [ordensRetirada, setOrdensRetirada] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showModal, setShowModal] = useState(false);
  const [selectedComarca, setSelectedComarca] = useState(null);
  const [comarcaHistorico, setComarcaHistorico] = useState(null);
  const [documentoVisualizacao, setDocumentoVisualizacao] = useState(null);
  const [documentoVistoria, setDocumentoVistoria] = useState(null);
  const [documentoVistoriaForm, setDocumentoVistoriaForm] = useState(null);
  const [documentosVistoriaHistorico, setDocumentosVistoriaHistorico] = useState([]);
  const [documentoAssinaturasLog, setDocumentoAssinaturasLog] = useState([]);
  const [documentoIntegridade, setDocumentoIntegridade] = useState(null);
  const [documentoAssinaturaAtual, setDocumentoAssinaturaAtual] = useState(null);
  const [papelAssinaturaAtual, setPapelAssinaturaAtual] = useState(null);
  const [nomeAssinanteAtual, setNomeAssinanteAtual] = useState("");
  const [salvandoDocumento, setSalvandoDocumento] = useState(false);
  const [documentoSujo, setDocumentoSujo] = useState(false);
  const [documentoMensagem, setDocumentoMensagem] = useState("");
  const [showAssinaturaModal, setShowAssinaturaModal] = useState(false);
  const [comarcaAssinaturaAtual, setComarcaAssinaturaAtual] = useState(null);
  const [showMaterialModal, setShowMaterialModal] = useState(false);
  const [comarcaMaterialAtual, setComarcaMaterialAtual] = useState(null);
  const [materialEmEdicao, setMaterialEmEdicao] = useState(null);
  const [materialForm, setMaterialForm] = useState({
    materialId: "",
    nomeMaterial: "",
    quantidadePrevista: "",
    itemAdicional: false,
  });
  const [showFaltantesModal, setShowFaltantesModal] = useState(false);
  const [comarcaFaltantesAtual, setComarcaFaltantesAtual] = useState(null);
  const [faltantesForm, setFaltantesForm] = useState({
    faltouMaterial: false,
    materialItemIds: [],
    descricao: "",
  });
  const [showTimelineModal, setShowTimelineModal] = useState(false);
  const [timelineMaterialAtual, setTimelineMaterialAtual] = useState(null);
  const [timelineForm, setTimelineForm] = useState({
    dataHoraSolicitacao: "",
    dataHoraRetirada: "",
    dataHoraUso: "",
  });
  const [viradaForms, setViradaForms] = useState({});
  const [encerrandoComarcaId, setEncerrandoComarcaId] = useState(null);
  const [carregandoResumoId, setCarregandoResumoId] = useState(null);
  const [encerramentoResumo, setEncerramentoResumo] = useState(null);

  // 💥 Controles reativos de validação para a Etapa 1 (Vistoria)
  const [fotosVistoria, setFotosVistoria] = useState({});
  const [assinaturasVistoria, setAssinaturasVistoria] = useState({});
  const [assinaturaEmEdicao, setAssinaturaEmEdicao] = useState(false);
  const assinaturaCanvasRef = useRef(null);
  const assinandoRef = useRef(false);
  const fotosVistoriaRef = useRef({});

  const [formData, setFormData] = useState({
    percentualConcluido: 0,
    pendencias: "",
  });

  useEffect(() => {
    fetchComarcas();
    if (usuario?.perfil === "ADMIN") fetchMateriaisEstoque();
    fetchOrdensRetirada();
  }, [incluirArquivados, usuario?.perfil]);

  useEffect(() => {
    return () => {
      Object.values(fotosVistoriaRef.current).forEach((foto) => {
        if (foto?.previewUrl) {
          URL.revokeObjectURL(foto.previewUrl);
        }
      });
    };
  }, []);

  useEffect(() => {
    if (!showAssinaturaModal) return;

    const canvas = assinaturaCanvasRef.current;
    if (!canvas) return;

    const context = canvas.getContext("2d");
    const { width } = canvas.getBoundingClientRect();
    const height = 220;
    const ratio = window.devicePixelRatio || 1;

    canvas.width = width * ratio;
    canvas.height = height * ratio;
    canvas.style.height = `${height}px`;

    context.scale(ratio, ratio);
    context.lineWidth = 2.5;
    context.lineCap = "round";
    context.lineJoin = "round";
    context.strokeStyle = "#0f172a";
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, width, height);
    setAssinaturaEmEdicao(false);
  }, [showAssinaturaModal]);

  const fetchComarcas = async () => {
    try {
      setLoading(true);
      const response = await api.get("/comarcas", { params: { incluirArquivados } });
      setComarcas(response.data || []);
      setError(null);
    } catch (err) {
      setError("Erro ao carregar comarcas");
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const alterarArquivamentoComarca = async (comarca) => {
    try {
      if (comarca.arquivado) {
        await api.patch(`/comarcas/${comarca.id}/restaurar`);
      } else {
        const motivo = window.prompt("Informe o motivo para arquivar esta obra/comarca:");
        if (!motivo?.trim()) return;
        await api.patch(`/comarcas/${comarca.id}/arquivar`, {
          usuario: USUARIO_ATUAL,
          motivo: motivo.trim(),
        });
      }
      fetchComarcas();
    } catch (err) {
      alert(err.response?.data?.erro || "Não foi possível alterar o arquivamento da obra.");
    }
  };

  const fetchMateriaisEstoque = async () => {
    try {
      const response = await api.get("/estoque/materiais");
      setMateriaisEstoque(response.data || []);
    } catch (err) {
      console.error("Erro ao carregar materiais do estoque", err);
    }
  };

  const fetchOrdensRetirada = async () => {
    try {
      const response = await api.get("/ordens-retirada");
      setOrdensRetirada(response.data || []);
    } catch (err) {
      console.error("Erro ao carregar ordens de retirada", err);
    }
  };

  const getArquivoUrl = (caminho) => {
    if (!caminho) return null;
    return buildApiFileUrl(caminho);
  };

  const atualizarComarcaNaLista = (comarcaAtualizada) => {
    setComarcas((prev) =>
      prev.map((comarca) =>
        comarca.id === comarcaAtualizada.id
          ? { ...comarca, ...comarcaAtualizada }
          : comarca,
      ),
    );
  };

  const temFotoVistoria = (comarca) =>
    !!fotosVistoria[comarca.id]?.previewUrl || !!comarca.fotoVistoriaUrl;

  const temAssinaturaVistoria = (comarca) =>
    !!assinaturasVistoria[comarca.id]?.base64 || !!comarca.assinaturaBase64;

  // 💥 REGRA DE NEGÓCIO: Bloqueia avanço se não houver foto e assinatura digital
  const handleAvancarFase = async (comarca) => {
    if (!temFotoVistoria(comarca) || !temAssinaturaVistoria(comarca)) {
      alert(
        "🔒 BLOQUEIO OPERACIONAL: Upload de foto da vistoria e Assinatura com o Gerente são obrigatórios para liberar a infraestrutura!",
      );
      return;
    }

    try {
      const response = await api.patch(`/comarcas/${comarca.id}/avancar-etapa`);
      atualizarComarcaNaLista(response.data);
      const proximaEtapa = response.data.etapaAtual;
      alert(
        proximaEtapa === 3
          ? `Virada de Rede liberada para a comarca de ${comarca.nomeComarca}.`
          : `Etapa 2 (Infraestrutura) desbloqueada para a comarca de ${comarca.nomeComarca}.`,
      );
    } catch (err) {
      alert(
        err.response?.data?.erro ||
          "Erro ao homologar vistoria e liberar infraestrutura.",
      );
      console.error("Erro ao transicionar etapa", err);
    }
  };

  const handleOpenModal = (comarca) => {
    setSelectedComarca(comarca);
    setFormData({
      percentualConcluido: comarca.percentualConcluido || 0,
      pendencias: comarca.pendencias || "",
    });
    setShowModal(true);
  };

  const handleCloseModal = () => {
    setShowModal(false);
    setSelectedComarca(null);
    setFormData({ percentualConcluido: 0, pendencias: "" });
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!selectedComarca) return;

    try {
      await api.patch(`/comarcas/${selectedComarca.id}`, {
        percentualConcluido: parseFloat(formData.percentualConcluido),
        pendencias: formData.pendencias,
      });

      setError(null);
      handleCloseModal();
      fetchComarcas();
    } catch (err) {
      setError("Erro ao atualizar comarca");
      console.error(err);
    }
  };

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({
      ...prev,
      [name]: value,
    }));
  };

  const abrirModalMaterial = (comarca, material = null) => {
    setComarcaMaterialAtual(comarca);
    setMaterialEmEdicao(material);
    setMaterialForm({
      materialId: material?.material?.id || material?.materialId || "",
      nomeMaterial: material?.nomeMaterial || "",
      quantidadePrevista: material?.quantidadePrevista || "",
      itemAdicional: Boolean(material?.itemAdicional),
    });
    setShowMaterialModal(true);
  };

  const abrirModalItemAdicional = (comarca) => {
    setComarcaMaterialAtual(comarca);
    setMaterialEmEdicao(null);
    setMaterialForm({
      materialId: "",
      nomeMaterial: "",
      quantidadePrevista: "",
      itemAdicional: true,
    });
    setShowMaterialModal(true);
  };

  const fecharModalMaterial = () => {
    setShowMaterialModal(false);
    setComarcaMaterialAtual(null);
    setMaterialEmEdicao(null);
    setMaterialForm({
      materialId: "",
      nomeMaterial: "",
      quantidadePrevista: "",
      itemAdicional: false,
    });
  };

  const handleMaterialFormChange = (e) => {
    const { name, value } = e.target;
    setMaterialForm((prev) => {
      if (name === "materialId") {
        const materialSelecionado = materiaisEstoque.find(
          (material) => String(material.id) === String(value),
        );
        return {
          ...prev,
          materialId: value,
          nomeMaterial: materialSelecionado?.nome || "",
        };
      }

      return { ...prev, [name]: value };
    });
  };

  const handleSalvarMaterialPrevisto = async (e) => {
    e.preventDefault();
    if (!comarcaMaterialAtual) return;

    const payload = {
      materialId: materialForm.materialId
        ? parseInt(materialForm.materialId, 10)
        : null,
      nomeMaterial: materialForm.nomeMaterial.trim(),
      quantidadePrevista: parseFloat(materialForm.quantidadePrevista),
    };

    try {
      const response = materialEmEdicao
        ? await api.put(
            `/comarcas/materiais-previstos/${materialEmEdicao.id}`,
            payload,
          )
        : materialForm.itemAdicional
          ? await api.post(
              `/comarcas/${comarcaMaterialAtual.id}/itens-adicionais`,
              payload,
            )
        : await api.post(
            `/comarcas/${comarcaMaterialAtual.id}/materiais-previstos`,
            payload,
          );

      atualizarComarcaNaLista(response.data);
      fecharModalMaterial();
    } catch (err) {
      alert(
        err.response?.data?.erro ||
          "Erro ao salvar material previsto para esta comarca.",
      );
      console.error("Erro ao salvar material previsto", err);
    }
  };

  const abrirModalFaltantes = (comarca) => {
    const materiaisFaltantes = (comarca.materiais || [])
      .filter((material) => material.materialFaltante)
      .map((material) => material.id);
    setComarcaFaltantesAtual(comarca);
    setFaltantesForm({
      faltouMaterial: Boolean(comarca.faltouMaterial || materiaisFaltantes.length),
      materialItemIds: materiaisFaltantes,
      descricao:
        comarca.descricaoMaterialFaltante ||
        (comarca.materiais || []).find((material) => material.descricaoFaltante)
          ?.descricaoFaltante ||
        "",
    });
    setShowFaltantesModal(true);
  };

  const fecharModalFaltantes = () => {
    setShowFaltantesModal(false);
    setComarcaFaltantesAtual(null);
    setFaltantesForm({ faltouMaterial: false, materialItemIds: [], descricao: "" });
  };

  const toggleMaterialFaltante = (materialId) => {
    setFaltantesForm((prev) => {
      const selecionados = prev.materialItemIds.includes(materialId)
        ? prev.materialItemIds.filter((id) => id !== materialId)
        : [...prev.materialItemIds, materialId];
      return { ...prev, materialItemIds: selecionados };
    });
  };

  const handleSalvarFaltantes = async (e) => {
    e.preventDefault();
    if (!comarcaFaltantesAtual) return;
    if (
      faltantesForm.faltouMaterial &&
      (!faltantesForm.descricao.trim() || faltantesForm.materialItemIds.length === 0)
    ) {
      alert("Selecione ao menos um material e descreva o que está faltando.");
      return;
    }

    try {
      const response = await api.patch(
        `/comarcas/${comarcaFaltantesAtual.id}/materiais-faltantes`,
        {
          faltouMaterial: faltantesForm.faltouMaterial,
          materialItemIds: faltantesForm.faltouMaterial
            ? faltantesForm.materialItemIds
            : [],
          descricao: faltantesForm.faltouMaterial
            ? faltantesForm.descricao.trim()
            : "",
        },
      );
      atualizarComarcaNaLista(response.data);
      fecharModalFaltantes();
    } catch (err) {
      alert(
        err.response?.data?.erro ||
          "Erro ao salvar controle de materiais faltantes.",
      );
      console.error(err);
    }
  };

  const toDatetimeLocal = (value) => {
    if (!value) return "";
    return String(value).slice(0, 16);
  };

  const fromDatetimeLocal = (value) => (value ? `${value}:00` : null);

  const formatarDataHora = (value) => {
    if (!value) return "-";
    return new Date(value).toLocaleString("pt-BR", {
      day: "2-digit",
      month: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  };

  const formatarDataDocumento = (value) => {
    if (!value) return "";
    return String(value).slice(0, 10);
  };

  const abrirDocumento = (tipo, comarca) => {
    setDocumentoVisualizacao({ tipo, comarca });
  };

  const gerarOrAdicional = async (comarca) => {
    const ordemServicoId = comarca.ordemServico?.id;
    if (!ordemServicoId) {
      alert("Esta obra ainda não possui OS vinculada para gerar OR.");
      return;
    }

    const confirmar = window.confirm(
      `Gerar uma nova Ordem de Retirada para ${comarca.ordemServico.numeroOs}?`,
    );
    if (!confirmar) return;

    try {
      await api.post(`/ordens-retirada/os/${ordemServicoId}`);
      await fetchOrdensRetirada();
      alert("OR adicional gerada com sucesso.");
    } catch (err) {
      alert(
        err.response?.data?.erro ||
          "Não foi possível gerar a OR adicional para esta OS.",
      );
      console.error("Erro ao gerar OR adicional", err);
    }
  };

  const criarDocumentoVistoriaForm = (comarca, tipo, conteudoSalvo = {}) => {
    const os = comarca.ordemServico || {};
    const contrato = os.contrato || comarca.projeto?.contrato || {};
    return {
      contrato: contrato.contrato || "",
      projeto:
        "Execução de serviços de Cabeamento de Rede e Fibra junto ao Tribunal de Justiça da Paraíba",
      numeroOs: os.numeroOs || `OS-2026-0${comarca.id}`,
      comarcaForum: comarca.nomeComarca || "",
      endereco: comarca.endereco || "",
      dataInicio: formatarDataDocumento(os.dataHoraInicio),
      dataConclusao: formatarDataDocumento(os.dataHoraFim),
      equipeResponsavel: "",
      gestorRc: contrato.gestorContrato?.nome || "",
      gerenteForum: "",
      recebidoPor: "",
      tecnicoResponsavel: comarca.projeto?.responsavel?.nome || "",
      cpfTecnico: "",
      gestorProjetoRc: contrato.gestorContrato?.nome || "",
      cargoGerente: "",
      objetoServicos: [],
      outrosObjeto: "",
      descricaoServicos: os.descricao || "",
      estadoInicial: [],
      anomaliasPreExistentes: "",
      protocoloComunicacao: "",
      rackNecessidade: "",
      rackDisponibilidade: "",
      rackObservacoes: "",
      rackLocalData: "",
      estadoFinal: [],
      observacoesFinais: "",
      ressalvas: "",
      dataGerenteForum: "",
      carimboGerente: "",
      responsavelDesignadoNome: "",
      responsavelDesignadoCargo: "",
      responsavelDesignadoData: "",
      gerenteDesignanteNome: "",
      declaracaoDesignacaoData: "",
      declaracaoDesignacao: "",
      ...conteudoSalvo,
      ...(os.simulacao ? camposFicticios() : {}),
      tipoDocumento: tipo,
    };
  };

  const lerConteudoDocumento = (documento) => {
    if (!documento?.conteudoJson) return {};
    try {
      return JSON.parse(documento.conteudoJson);
    } catch {
      return {};
    }
  };

  const abrirDocumentoVistoria = async (comarca, tipo) => {
    let documentos = [];
    try {
      const response = await api.get(`/documentos-internos/comarca/${comarca.id}`);
      documentos = response.data || [];
    } catch (err) {
      console.error("Não foi possível carregar os documentos anteriores", err);
    }

    const documentoSalvo = documentos.find((documento) =>
      tipo === DOCUMENTO_INICIAL
        ? [DOCUMENTO_INICIAL, "VISTORIA_OS"].includes(documento.tipo)
        : documento.tipo === DOCUMENTO_FINAL,
    );
    const documentoInicial = documentos.find((documento) =>
      [DOCUMENTO_INICIAL, "VISTORIA_OS"].includes(documento.tipo),
    );
    const conteudoBase = lerConteudoDocumento(
      documentoSalvo || (tipo === DOCUMENTO_FINAL ? documentoInicial : null),
    );

    setDocumentosVistoriaHistorico(documentos);
    setDocumentoVistoria({ tipo, comarca, documentoSalvo: documentoSalvo || null });
    setDocumentoVistoriaForm(criarDocumentoVistoriaForm(comarca, tipo, conteudoBase));
    setDocumentoSujo(false);
    setDocumentoMensagem("");
    carregarAuditoriaDocumento(documentoSalvo);
  };

  const abrirVersaoDocumento = (documento) => {
    const tipo = documento.tipo === DOCUMENTO_FINAL ? DOCUMENTO_FINAL : DOCUMENTO_INICIAL;
    setDocumentoVistoria((prev) => ({ ...prev, tipo, documentoSalvo: documento }));
    setDocumentoVistoriaForm(
      criarDocumentoVistoriaForm(
        documentoVistoria.comarca,
        tipo,
        lerConteudoDocumento(documento),
      ),
    );
    setDocumentoSujo(false);
    setDocumentoMensagem("");
    carregarAuditoriaDocumento(documento);
  };

  const carregarAuditoriaDocumento = async (documento) => {
    if (!documento?.id || !documento.hashRegistro) {
      setDocumentoAssinaturasLog([]);
      setDocumentoIntegridade(null);
      return;
    }
    try {
      const [logsResponse, integridadeResponse] = await Promise.all([
        api.get(`/documentos-internos/${documento.id}/assinaturas/log`),
        api.get(`/documentos-internos/${documento.id}/integridade`),
      ]);
      setDocumentoAssinaturasLog(logsResponse.data || []);
      setDocumentoIntegridade(integridadeResponse.data || null);
    } catch (err) {
      setDocumentoAssinaturasLog([]);
      setDocumentoIntegridade(null);
      console.error("Não foi possível verificar a auditoria do documento", err);
    }
  };

  const criarNovaVersaoDocumento = async () => {
    const documentoAtual = documentoVistoria?.documentoSalvo;
    if (!documentoAtual?.id) return;
    if (documentoAtual.status === "INVALIDADO") {
      alert("Este documento já foi invalidado. Selecione a versão substituta no histórico.");
      return;
    }

    const motivo = window.prompt(
      "Informe o motivo da correção. A versão atual continuará preservada no histórico:",
    );
    if (!motivo?.trim()) return;

    try {
      const response = await api.post(
        `/documentos-internos/${documentoAtual.id}/invalidar`,
        { motivo: motivo.trim() },
      );
      const novaVersao = response.data;
      const documentoInvalidado = {
        ...documentoAtual,
        status: "INVALIDADO",
        invalidadoPor: USUARIO_ATUAL,
        invalidadoEm: new Date().toISOString(),
        motivoInvalidacao: motivo.trim(),
      };
      setDocumentosVistoriaHistorico((prev) => [
        novaVersao,
        ...prev.map((documento) =>
          documento.id === documentoAtual.id ? documentoInvalidado : documento,
        ),
      ]);
      setDocumentoVistoria((prev) => ({ ...prev, documentoSalvo: novaVersao }));
      setDocumentoVistoriaForm(
        criarDocumentoVistoriaForm(
          documentoVistoria.comarca,
          documentoVistoria.tipo,
          lerConteudoDocumento(novaVersao),
        ),
      );
      setDocumentoAssinaturasLog([]);
      setDocumentoIntegridade(null);
      setDocumentoSujo(false);
      setDocumentoMensagem("Versão anterior invalidada. Nova versão criada para correção.");
    } catch (err) {
      alert(err.response?.data?.erro || "Não foi possível criar a versão de correção.");
      console.error(err);
    }
  };

  const atualizarDocumentoVistoria = (campo, valor) => {
    if (["PARCIALMENTE_ASSINADO", "REGISTRADO", "INVALIDADO"].includes(documentoVistoria?.documentoSalvo?.status)) {
      return;
    }
    setDocumentoVistoriaForm((prev) => ({ ...prev, [campo]: valor }));
    setDocumentoSujo(true);
    setDocumentoMensagem("Alterações ainda não salvas.");
  };

  const alternarOpcaoDocumento = (campo, opcao) => {
    if (["PARCIALMENTE_ASSINADO", "REGISTRADO", "INVALIDADO"].includes(documentoVistoria?.documentoSalvo?.status)) {
      return;
    }
    setDocumentoVistoriaForm((prev) => {
      const selecionados = prev[campo] || [];
      return {
        ...prev,
        [campo]: selecionados.includes(opcao)
          ? selecionados.filter((item) => item !== opcao)
          : [...selecionados, opcao],
      };
    });
    setDocumentoSujo(true);
    setDocumentoMensagem("Alterações ainda não salvas.");
  };

  const documentoSimulacao = documentoVistoria?.documentoSalvo
    ? documentoVistoria.documentoSalvo.simulacao === true
    : documentoVistoria?.comarca?.ordemServico?.simulacao === true;

  const montarConteudoDocumentoVistoria = () => ({
    modelo:
      documentoVistoria?.tipo === DOCUMENTO_FINAL
        ? "ORDEM DE SERVIÇO - ENCERRAMENTO, ACEITE E CONFORMIDADE TÉCNICA"
        : "ORDEM DE SERVIÇO - ABERTURA E VISTORIA TÉCNICA INICIAL",
    tipoDocumento: documentoVistoria?.tipo || DOCUMENTO_INICIAL,
    empresa: documentoSimulacao ? EMPRESA_FICTICIA : "RC TECHNOLOGY AND INTEGRATION LTDA",
    cnpj: documentoSimulacao ? "NÃO APLICÁVEL — EMPRESA FICTÍCIA" : "33.910.895/0001-50",
    preenchidoEm: new Date().toISOString(),
    ...documentoVistoriaForm,
  });

  const salvarDocumentoVistoria = async () => {
    if (!documentoVistoria?.comarca || !documentoVistoriaForm) return null;
    const documentoAtual = documentoVistoria.documentoSalvo;
    if (["PARCIALMENTE_ASSINADO", "REGISTRADO", "INVALIDADO"].includes(documentoAtual?.status)) {
      throw new Error("Documento assinado não pode ser alterado. Crie uma nova versão.");
    }
    setSalvandoDocumento(true);
    try {
      const conteudo = montarConteudoDocumentoVistoria();
      const payload = {
        simulacao: documentoSimulacao,
        comarcaId: documentoVistoria.comarca.id,
        tipo: documentoVistoria.tipo,
        conteudoJson: JSON.stringify(conteudo),
        recebidoPor:
          documentoVistoriaForm.recebidoPor ||
          documentoVistoriaForm.gerenteForum ||
          "Responsável da Unidade",
      };
      const response = documentoAtual?.id
        ? await api.put(`/documentos-internos/${documentoAtual.id}/conteudo`, payload)
        : await api.post("/documentos-internos/vistoria", payload);
      setDocumentoVistoria((prev) => ({ ...prev, documentoSalvo: response.data }));
      setDocumentoVistoriaForm(criarDocumentoVistoriaForm(documentoVistoria.comarca, documentoVistoria.tipo, lerConteudoDocumento(response.data)));
      setDocumentoAssinaturasLog([]);
      setDocumentoIntegridade(null);
      setDocumentoSujo(false);
      setDocumentoMensagem("Documento salvo no servidor.");
      setDocumentosVistoriaHistorico((prev) => [
        response.data,
        ...prev.filter((documento) => documento.id !== response.data.id),
      ]);
      return response.data;
    } finally {
      setSalvandoDocumento(false);
    }
  };

  const handleSalvarDocumentoVistoria = async () => {
    try {
      await salvarDocumentoVistoria();
    } catch (err) {
      const mensagem =
        err.response?.data?.erro ||
        err.message ||
        "Não foi possível salvar o documento.";
      setDocumentoMensagem(`Erro ao salvar: ${mensagem}`);
    }
  };

  const fecharDocumentoVistoria = () => {
    if (documentoSujo && !window.confirm("Existem alterações não salvas. Deseja fechar e descartá-las?")) {
      return;
    }
    setDocumentoVistoria(null);
    setDocumentoVistoriaForm(null);
    setDocumentoSujo(false);
    setDocumentoMensagem("");
  };

  const imprimirDocumentoVistoria = () => {
    if (!documentoVistoriaForm) return;
    if (documentoSimulacao) { abrirPdfServidor(); return; }
    const conteudo = montarConteudoDocumentoVistoria();
    const escaparHtml = (valor) =>
      String(valor ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
    const valor = (texto) => escaparHtml(texto) || "&nbsp;";
    const linha = (texto = "", classe = "") =>
      `<span class="fill ${classe}">${valor(texto)}</span>`;
    const marcado = (campo, opcao) =>
      (conteudo[campo] || []).includes(opcao) ? "X" : "";
    const opcao = (campo, texto) => `
      <div class="check-row"><span class="box">${marcado(campo, texto)}</span><span>${escaparHtml(texto)}</span></div>`;
    const opcaoUnica = (campo, valorOpcao, texto) => `
      <div class="check-row"><span class="box">${conteudo[campo] === valorOpcao ? "X" : ""}</span><span>${escaparHtml(texto)}</span></div>`;
    const cabecalho = `
      <header class="document-header">
        <img src="${new URL(rcLogo, window.location.origin).href}" alt="RC Technology" />
        <span class="brand-mark"><i></i><i></i><i></i></span>
      </header>`;
    const rodape = (pagina) => `
      <footer class="document-footer">
        <div><strong>MATRIZ:</strong><br/>Natal/RN - Rua Raimundo Chaves, 1892 - Candelária - CEP:59064-390<br/>
        <strong>FILIAIS:</strong><br/>Manaus/AM - Av. Djalma Batista, 3000 - LJ 43 - Parque 10 de Novembro - CEP:69055-038<br/>
        Boa Vista/RR - Av. Capitão Julio Bezerra, 272 - LJ 12 - Centro - CEP:69301-410<br/>
        Fone: +55 (84) 3343-1227 &nbsp; E-mail: comercial@rctechnology.com.br</div>
        <strong>Página ${pagina} de 6</strong>
      </footer>`;
    const pagina = (numero, corpo, classe = "") => `
      <section class="page ${classe}">${cabecalho}<main>${corpo}</main>${rodape(numero)}</section>`;
    const documentoFinal = documentoVistoria?.tipo === DOCUMENTO_FINAL;
    const documentoSalvo = documentoVistoria?.documentoSalvo || {};
    const assinaturaImpressa = (imagem, nome) =>
      imagem?.startsWith("data:image/")
        ? `<span class="digital-signature"><img src="${escaparHtml(imagem)}" alt="Assinatura digital"/><small>${valor(nome)}</small></span>`
        : linha("");
    const subtitulo = documentoFinal
      ? "ENCERRAMENTO, ACEITE E CONFORMIDADE TÉCNICA"
      : "ABERTURA E VISTORIA TÉCNICA INICIAL";
    const descricaoServicos = documentoFinal
      ? "Descrição detalhada dos serviços executados:"
      : "Descrição detalhada dos serviços previstos:";
    const anoOs = String(conteudo.numeroOs || "").match(/20\d{2}/)?.[0] || new Date().getFullYear();
    const numeroOsSemAno = String(conteudo.numeroOs || "").replace(/\/?20\d{2}/, "");
    const paginas = [
      pagina(1, `
        <div class="cover-title"><h1>ORDEM DE SERVIÇO (OS)</h1><h2>${subtitulo}</h2></div>
        <div class="company"><strong>RC TECHNOLOGY AND INTEGRATION LTDA</strong><br/><br/>CNPJ: 33.910.895/0001-50</div>
        <p><strong>Contrato:</strong> ${linha(conteudo.contrato, "wide")}</p>
        <p><strong>Projeto:</strong> ${linha(conteudo.projeto, "wide")}</p>
        <div class="os-number">ORDEM DE SERVIÇO Nº ${linha(numeroOsSemAno, "short")} / ${anoOs}</div>
        <div class="form-lines">
          <p>Comarca/Fórum: ${linha(conteudo.comarcaForum)}</p>
          <p>Endereço: ${linha(conteudo.endereco)}</p>
          <p>Data de Início: ${linha(conteudo.dataInicio, "date")}</p>
          <p>Data de Conclusão: ${linha(conteudo.dataConclusao, "date")}</p>
          <p class="multiline">Equipe Responsável: ${linha(conteudo.equipeResponsavel)}</p>
          <p>Gestor RC Technology: ${linha(conteudo.gestorRc)}</p>
          <p>Gerente do Fórum: ${linha(conteudo.gerenteForum)}</p>
        </div>`, "cover"),
      pagina(2, `
        <h3>1. OBJETO DA ORDEM DE SERVIÇO</h3>
        <p>Execução de serviços de infraestrutura tecnológica, incluindo (marcar conforme aplicável):</p>
        <div class="checks">${OBJETO_SERVICO_OPCOES.map((item) => opcao("objetoServicos", item)).join("")}</div>
        <p>Outros: ${linha(conteudo.outrosObjeto)}</p>
        <p class="writing-label">${descricaoServicos}</p>
        <div class="writing-lines">${valor(conteudo.descricaoServicos)}</div>
        <h3>2. REGISTRO DE CONDIÇÃO PREDIAL - ESTADO INICIAL</h3>
        <p>Declara-se que, antes do início dos serviços:</p>
        <div class="checks">${ESTADO_INICIAL_OPCOES.slice(0, 4).map((item) => opcao("estadoInicial", item)).join("")}</div>`),
      pagina(3, `
        <div class="checks">${ESTADO_INICIAL_OPCOES.slice(4).map((item) => opcao("estadoInicial", item)).join("")}</div>
        <p class="writing-label">Anomalias pré-existentes identificadas (se houver):</p>
        <div class="writing-lines compact">${valor(conteudo.anomaliasPreExistentes)}</div>
        <p>Protocolo de comunicação (se aplicável): ${linha(conteudo.protocoloComunicacao)}</p>
        <h3>3. NECESSIDADE DE SUBSTITUIÇÃO DO RACK DA COMARCA</h3>
        <p>Após a realização da vistoria técnica, declaro que:</p>
        <div class="checks">
          ${RACK_NECESSIDADE_OPCOES.map(([valorOpcao, texto]) => opcaoUnica("rackNecessidade", valorOpcao, texto)).join("")}
          ${RACK_DISPONIBILIDADE_OPCOES.map(([valorOpcao, texto]) => opcaoUnica("rackDisponibilidade", valorOpcao, texto)).join("")}
        </div>
        <p class="writing-label">Observações/Justificativa (quando aplicável):</p>
        <div class="writing-lines compact">${valor(conteudo.rackObservacoes)}</div>
        <p>Local e data: ${linha(conteudo.rackLocalData)}</p>
        <h3>4. DECLARAÇÃO DE CONFORMIDADE TÉCNICA - ESTADO FINAL</h3>
        <div class="checks">${ESTADO_FINAL_OPCOES.slice(0, 4).map((item) => opcao("estadoFinal", item)).join("")}</div>`),
      pagina(4, `
        <div class="checks">${ESTADO_FINAL_OPCOES.slice(4).map((item) => opcao("estadoFinal", item)).join("")}</div>
        <p class="writing-label">Observações finais:</p>
        <div class="writing-lines compact">${valor(conteudo.observacoesFinais)}</div>
        <h3>5. DECLARAÇÃO DE ACEITE E CIÊNCIA</h3>
        <p>O Gerente do Fórum declara que:</p>
        <p>- Acompanhou ou tomou ciência da conclusão dos serviços;</p>`),
      pagina(5, `
        <p>- O ambiente foi vistoriado;</p>
        <p>- Os serviços foram executados conforme descrito;</p>
        <p>- Não há pendências aparentes no momento da vistoria.</p>
        <p class="writing-label">Ressalvas (caso existam):</p>
        <div class="writing-lines compact">${valor(conteudo.ressalvas)}</div>
        <h3>6. CLÁUSULA DE RESGUARDO TÉCNICO</h3>
        <p class="justified">A presente Ordem de Serviço e a vistoria prévia realizada conjuntamente têm como finalidade registrar as condições aparentes dos ambientes e equipamentos existentes antes da execução dos serviços, incluindo computadores, impressoras e telefones.</p>
        <p class="justified">Fica estabelecido que eventuais defeitos, falhas, vícios, desgastes naturais, irregularidades ou danos preexistentes não poderão ser imputados à equipe técnica da RC Technology, assim como qualquer dano futuro não poderá ser atribuído à execução dos serviços realizados, salvo mediante comprovação técnica de dolo ou culpa grave.</p>
        <p class="justified">A assinatura deste documento pelas partes envolvidas formaliza a ciência, concordância e validação das condições verificadas no ato da vistoria e da conclusão dos serviços executados.</p>
        <h3>7. ASSINATURAS</h3>
        <p><strong>Pela RC Technology:</strong></p>
        <p>Técnico Responsável:</p>
        <p>Nome: ${linha(conteudo.tecnicoResponsavel)}</p>
        <p>CPF: ${linha(conteudo.cpfTecnico)}</p>
        <p class="signature-space">Assinatura: ${assinaturaImpressa(documentoSalvo.assinaturaTecnicoBase64, documentoSalvo.tecnicoAssinadoPor)}</p>`),
      pagina(6, `
        <p><strong>Gestor do Projeto RC Technology:</strong></p>
        <p>Nome: ${linha(conteudo.gestorProjetoRc)}</p>
        <p class="signature-space">Assinatura: ${assinaturaImpressa(documentoSalvo.assinaturaGestorBase64, documentoSalvo.gestorAssinadoPor)}</p>
        <p class="group-title"><strong>Pelo Fórum / Unidade:</strong></p>
        <p>Gerente do Fórum:</p>
        <p>Nome: ${linha(conteudo.gerenteForum)}</p>
        <p>Cargo: ${linha(conteudo.cargoGerente)}</p>
        <p class="signature-space">Assinatura: ${assinaturaImpressa(documentoSalvo.assinaturaGerenteBase64, documentoSalvo.gerenteAssinadoPor)}</p>
        <p>Data: ${linha(conteudo.dataGerenteForum, "date")} &nbsp;&nbsp; Carimbo (se aplicável): ${linha(conteudo.carimboGerente)}</p>
        <h4>RESPONSÁVEL DESIGNADO PARA ACOMPANHAMENTO DA VISTORIA:</h4>
        <p><em>(Preencher apenas caso a vistoria não seja acompanhada diretamente pelo(a) Gerente da Unidade)</em></p>
        <p>Nome: ${linha(conteudo.responsavelDesignadoNome)}</p>
        <p>Cargo/Função: ${linha(conteudo.responsavelDesignadoCargo)}</p>
        <p class="signature-space">Assinatura: ${linha("")}</p>
        <p>Data: ${linha(conteudo.responsavelDesignadoData, "date")}</p>
        <h4>DECLARAÇÃO DE DESIGNAÇÃO:</h4>
        <p class="justified">Eu, ${linha(conteudo.gerenteDesignanteNome || conteudo.gerenteForum, "medium")}, na condição de Gerente da Comarca/Unidade, declaro para os devidos fins que designo o(a) servidor(a)/colaborador(a) acima identificado(a) para acompanhar a vistoria prévia e os procedimentos relacionados à execução dos serviços, conferindo-lhe autorização para atuar em minha representação durante todo o processo de inspeção inicial dos ambientes.</p>
        <p>Assinatura do(a) Gerente: ${assinaturaImpressa(documentoSalvo.assinaturaGerenteBase64, documentoSalvo.gerenteAssinadoPor)}</p>
        <p>Data: ${linha(conteudo.declaracaoDesignacaoData, "date")}</p>`),
    ].join("");
    const janela = window.open("", "_blank", "width=900,height=900");
    if (!janela) return;
    janela.document.write(`
      <html>
        <head>
          <title>${valor(conteudo.numeroOs)} - ${subtitulo}</title>
          <style>
            * { box-sizing: border-box; }
            @page { size: A4; margin: 0; }
            body { margin: 0; background: #e5e7eb; color: #111; font-family: Arial, sans-serif; font-size: 11pt; }
            .page { position: relative; width: 210mm; min-height: 297mm; margin: 8mm auto; padding: 43mm 25mm 31mm; overflow: hidden; background: white; box-shadow: 0 2px 12px #64748b55; page-break-after: always; }
            .page:last-child { page-break-after: auto; }
            .document-header { position: absolute; inset: 0 0 auto; height: 35mm; background: #0b2cac; display: flex; align-items: center; justify-content: space-between; padding: 5mm 17mm; }
            .document-header img { width: 31mm; height: 25mm; object-fit: contain; }
            .brand-mark { display: grid; grid-template-columns: repeat(2, 5mm); grid-template-rows: repeat(2, 5mm); }
            .brand-mark i { background: #1c78c0; }
            .brand-mark i:nth-child(2) { background: #08216c; transform: translate(2mm, 2mm); }
            .brand-mark i:nth-child(3) { background: #e30613; transform: translate(2mm, -2mm); }
            .document-footer { position: absolute; inset: auto 0 0; height: 27mm; display: flex; align-items: flex-start; justify-content: space-between; gap: 8mm; padding: 4mm 20mm; background: #e10600; color: white; font-size: 6.7pt; line-height: 1.18; }
            .document-footer > strong { padding-top: 1mm; white-space: nowrap; font-size: 8pt; }
            main { line-height: 1.35; }
            h1, h2, h3 { color: #143da2; font-weight: 400; }
            h3 { margin: 5mm 0 3mm; font-size: 14pt; }
            h4 { margin: 6mm 0 3mm; font-size: 10.5pt; }
            p { margin: 2.5mm 0; }
            .cover main { padding-top: 12mm; }
            .cover-title { margin-bottom: 15mm; text-align: center; }
            .cover-title h1 { margin: 0 0 3mm; font-size: 18pt; }
            .cover-title h2 { margin: 0; font-size: 17pt; }
            .company { margin-bottom: 7mm; }
            .os-number { margin: 15mm 0 5mm; color: #143da2; font-size: 15pt; }
            .fill { display: inline-block; min-width: 80mm; min-height: 5mm; padding: 0 1mm; border-bottom: .35mm solid #111; color: #111; vertical-align: bottom; }
            .fill.wide { min-width: 115mm; }
            .fill.medium { min-width: 65mm; }
            .fill.short { min-width: 22mm; }
            .fill.date { min-width: 35mm; }
            .form-lines p { display: flex; align-items: flex-end; gap: 2mm; }
            .form-lines .fill { flex: 1; }
            .form-lines .multiline { align-items: flex-start; min-height: 27mm; }
            .checks { display: grid; gap: 2.5mm; margin: 3mm 0; }
            .check-row { display: flex; align-items: flex-start; gap: 2mm; }
            .box { display: inline-flex; width: 4mm; height: 4mm; flex: 0 0 4mm; align-items: center; justify-content: center; border: .3mm solid #111; font-size: 8pt; font-weight: 700; line-height: 1; }
            .writing-label { margin-top: 5mm; }
            .writing-lines { min-height: 22mm; padding: 1mm 0; line-height: 7mm; white-space: pre-wrap; background: repeating-linear-gradient(to bottom, transparent 0, transparent 6.6mm, #111 6.6mm, #111 6.9mm); }
            .writing-lines.compact { min-height: 15mm; }
            .justified { text-align: justify; }
            .signature-space { margin-top: 6mm; }
            .digital-signature { display: inline-flex; min-width: 80mm; min-height: 14mm; align-items: flex-end; gap: 3mm; border-bottom: .35mm solid #111; vertical-align: bottom; }
            .digital-signature img { width: 38mm; height: 13mm; object-fit: contain; }
            .digital-signature small { padding-bottom: 1mm; font-size: 7pt; }
            .group-title { margin-top: 8mm; }
            .preview-toolbar { position: sticky; z-index: 20; top: 0; display: flex; align-items: center; justify-content: center; gap: 5mm; padding: 3mm; background: #0f172a; color: white; font-size: 10pt; }
            .preview-toolbar button { border: 0; border-radius: 2mm; padding: 2.5mm 5mm; background: #2563eb; color: white; font-weight: 700; cursor: pointer; }
            @media print {
              body { background: white; }
              .page { margin: 0; box-shadow: none; }
              .preview-toolbar { display: none; }
            }
          </style>
        </head>
        <body>
          <div class="preview-toolbar">
            <span>Pré-visualização A4 - confirme os campos e a escala antes de imprimir.</span>
            <button type="button" onclick="window.print()">Imprimir agora</button>
          </div>
          ${paginas}
        </body>
      </html>
    `);
    janela.document.close();
    janela.focus();
  };

  const abrirPdfServidor = async () => {
    try {
      const documento = documentoVistoria?.documentoSalvo && !documentoSujo
        ? documentoVistoria.documentoSalvo
        : await salvarDocumentoVistoria();
      if (!documento?.id) return;
      window.open(`${API_BASE_URL}/documentos-internos/${documento.id}/pdf`, "_blank", "noopener,noreferrer");
    } catch (err) {
      alert(err.response?.data?.erro || "Não foi possível gerar o PDF no servidor.");
    }
  };

  const getNomeAssinantePadrao = (papel) => {
    if (papel === "TECNICO") return documentoVistoriaForm?.tecnicoResponsavel || "";
    if (papel === "GESTOR_RC") return documentoVistoriaForm?.gestorProjetoRc || "";
    return documentoVistoriaForm?.gerenteForum || "";
  };

  const assinarDocumentoVistoria = async (papel) => {
    try {
      const documento = documentoVistoria?.documentoSalvo && !documentoSujo
        ? documentoVistoria.documentoSalvo
        : await salvarDocumentoVistoria();
      if (!documento) return;
      setDocumentoAssinaturaAtual(documento);
      setPapelAssinaturaAtual(papel);
      setNomeAssinanteAtual(getNomeAssinantePadrao(papel));
      setComarcaAssinaturaAtual(documentoVistoria.comarca);
      setShowAssinaturaModal(true);
    } catch (err) {
      alert(err.response?.data?.erro || "Não foi possível gerar o documento para assinatura.");
      console.error(err);
    }
  };

  const validarOrdemTimeline = () => {
    const solicitacao = timelineForm.dataHoraSolicitacao
      ? new Date(timelineForm.dataHoraSolicitacao)
      : null;
    const retirada = timelineForm.dataHoraRetirada
      ? new Date(timelineForm.dataHoraRetirada)
      : null;
    const uso = timelineForm.dataHoraUso ? new Date(timelineForm.dataHoraUso) : null;

    if (solicitacao && retirada && retirada < solicitacao) {
      return "A retirada não pode acontecer antes da solicitação.";
    }
    if (retirada && uso && uso < retirada) {
      return "O uso não pode acontecer antes da retirada.";
    }
    if (solicitacao && uso && uso < solicitacao) {
      return "O uso não pode acontecer antes da solicitação.";
    }

    return null;
  };

  const abrirModalTimeline = (material) => {
    setTimelineMaterialAtual(material);
    setTimelineForm({
      dataHoraSolicitacao: toDatetimeLocal(material.dataHoraSolicitacao),
      dataHoraRetirada: toDatetimeLocal(material.dataHoraRetirada),
      dataHoraUso: toDatetimeLocal(material.dataHoraUso),
    });
    setShowTimelineModal(true);
  };

  const fecharModalTimeline = () => {
    setShowTimelineModal(false);
    setTimelineMaterialAtual(null);
    setTimelineForm({
      dataHoraSolicitacao: "",
      dataHoraRetirada: "",
      dataHoraUso: "",
    });
  };

  const handleSalvarTimeline = async (e) => {
    e.preventDefault();
    if (!timelineMaterialAtual) return;

    const erroTimeline = validarOrdemTimeline();
    if (erroTimeline) {
      alert(erroTimeline);
      return;
    }

    try {
      const response = await api.patch(
        `/comarcas/materiais-previstos/${timelineMaterialAtual.id}/timeline`,
        {
          dataHoraSolicitacao: fromDatetimeLocal(timelineForm.dataHoraSolicitacao),
          dataHoraRetirada: fromDatetimeLocal(timelineForm.dataHoraRetirada),
          dataHoraUso: fromDatetimeLocal(timelineForm.dataHoraUso),
        },
      );
      atualizarComarcaNaLista(response.data);
      fecharModalTimeline();
    } catch (err) {
      alert(err.response?.data?.erro || "Erro ao salvar timeline do material.");
      console.error(err);
    }
  };

  const getViradaForm = (comarca) => ({
      provasFuncionamento: comarca.viradaRedeProvasFuncionamento || "",
      checklist: comarca.viradaRedeChecklist || "",
      concluida: Boolean(comarca.viradaRedeConcluida),
      ...(viradaForms[comarca.id] || {}),
    });

  const setViradaFormValue = (comarcaId, campo, valor) => {
    setViradaForms((prev) => ({
      ...prev,
      [comarcaId]: {
        ...(prev[comarcaId] || {}),
        [campo]: valor,
      },
    }));
  };

  const handleSalvarViradaRede = async (comarca) => {
    const form = getViradaForm(comarca);

    if (
      form.concluida &&
      !form.provasFuncionamento &&
      !comarca.viradaRedeProvasFuncionamento
    ) {
      alert("Envie a foto da prova de funcionamento antes de concluir.");
      return;
    }

    try {
      const response = await api.patch(`/comarcas/${comarca.id}/virada-rede`, form);
      atualizarComarcaNaLista(response.data);
      setViradaForms((prev) => {
        const next = { ...prev };
        delete next[comarca.id];
        return next;
      });
    } catch (err) {
      alert(err.response?.data?.erro || "Erro ao salvar Virada de Rede.");
      console.error(err);
    }
  };

  const handleConcluirObra = async (comarca) => {
    try {
      setEncerrandoComarcaId(comarca.id);
      const response = await api.patch(`/comarcas/${comarca.id}/concluir`, {
        concluidaPor: USUARIO_ATUAL,
      });
      const resumo = response.data;
      setComarcas((atuais) =>
        atuais.map((item) =>
          item.id === comarca.id
            ? {
                ...item,
                situacao: resumo.situacao,
                percentualConcluido: 100,
                dataConclusao: resumo.concluidaEm,
                concluidaPor: resumo.concluidaPor,
                projeto: item.projeto
                  ? { ...item.projeto, status: "CONCLUIDO" }
                  : item.projeto,
              }
            : item,
        ),
      );
      setEncerramentoResumo(resumo);
    } catch (err) {
      alert(err.response?.data?.erro || "Não foi possível concluir a obra.");
    } finally {
      setEncerrandoComarcaId(null);
    }
  };

  const handleAbrirResumoEncerramento = async (comarca) => {
    try {
      setCarregandoResumoId(comarca.id);
      const response = await api.get(`/comarcas/${comarca.id}/encerramento`);
      setEncerramentoResumo(response.data);
    } catch (err) {
      alert(err.response?.data?.erro || "Não foi possível carregar o encerramento da obra.");
    } finally {
      setCarregandoResumoId(null);
    }
  };

  const handleRemoverMaterialPrevisto = async (material) => {
    if (!material?.id) return;
    const confirmar = window.confirm(
      `Remover o material previsto "${material.nomeMaterial}" desta comarca?`,
    );
    if (!confirmar) return;

    try {
      const response = await api.delete(
        `/comarcas/materiais-previstos/${material.id}`,
      );
      atualizarComarcaNaLista(response.data);
    } catch (err) {
      alert(
        err.response?.data?.erro ||
          "Erro ao remover material previsto desta comarca.",
      );
      console.error("Erro ao remover material previsto", err);
    }
  };

  const handleProvaViradaRedeChange = async (e, comarcaId) => {
    const file = e.target.files?.[0];

    if (!file) return;

    if (!["image/jpeg", "image/png"].includes(file.type)) {
      alert("Selecione uma imagem válida nos formatos JPEG ou PNG.");
      e.target.value = "";
      return;
    }

    const payload = new FormData();
    payload.append("foto", file);

    try {
      const response = await api.post(
        `/comarcas/${comarcaId}/virada-rede/prova`,
        payload,
        {
          headers: { "Content-Type": "multipart/form-data" },
        },
      );

      atualizarComarcaNaLista(response.data);
      setViradaForms((prev) => {
        const formAtual = prev[comarcaId] || {};
        return {
          ...prev,
          [comarcaId]: {
            ...formAtual,
            provasFuncionamento: response.data.viradaRedeProvasFuncionamento || "",
          },
        };
      });
    } catch (err) {
      alert(
        err.response?.data?.erro ||
          "Não foi possível salvar a prova da Virada de Rede.",
      );
      console.error(err);
    } finally {
      e.target.value = "";
    }
  };

  const handleFotoVistoriaChange = async (e, comarcaId) => {
    const file = e.target.files?.[0];

    if (!file) return;

    if (!["image/jpeg", "image/png"].includes(file.type)) {
      alert("Selecione uma imagem válida nos formatos JPEG ou PNG.");
      e.target.value = "";
      return;
    }

    const payload = new FormData();
    payload.append("foto", file);

    try {
      const response = await api.post(
        `/comarcas/${comarcaId}/vistoria/foto`,
        payload,
        {
          headers: { "Content-Type": "multipart/form-data" },
        },
      );

      atualizarComarcaNaLista(response.data);
      setFotosVistoria((prev) => {
        if (prev[comarcaId]?.previewUrl?.startsWith("blob:")) {
          URL.revokeObjectURL(prev[comarcaId].previewUrl);
        }

        const next = { ...prev };
        delete next[comarcaId];
        fotosVistoriaRef.current = next;
        return next;
      });
    } catch (err) {
      alert(
        err.response?.data?.erro ||
          "Não foi possível salvar a foto da vistoria no servidor.",
      );
      console.error(err);
    } finally {
      e.target.value = "";
    }
  };

  const removerEvidencia = async (comarcaId, tipo) => {
    const mensagens = {
      foto: "remover a foto da vistoria",
      assinatura: "remover o termo assinado da vistoria",
      prova: "remover a prova de funcionamento",
    };
    if (!window.confirm(`Deseja realmente ${mensagens[tipo]}?`)) return;

    const endpoints = {
      foto: `/comarcas/${comarcaId}/vistoria/foto`,
      assinatura: `/comarcas/${comarcaId}/vistoria/assinatura`,
      prova: `/comarcas/${comarcaId}/virada-rede/prova`,
    };
    try {
      const response = await api.delete(endpoints[tipo]);
      atualizarComarcaNaLista(response.data);
      if (tipo === "foto") {
        setFotosVistoria((prev) => {
          const next = { ...prev };
          delete next[comarcaId];
          fotosVistoriaRef.current = next;
          return next;
        });
      }
      if (tipo === "assinatura") {
        setAssinaturasVistoria((prev) => {
          const next = { ...prev };
          delete next[comarcaId];
          return next;
        });
      }
      if (tipo === "prova") {
        setViradaForms((prev) => ({
          ...prev,
          [comarcaId]: { ...(prev[comarcaId] || {}), provasFuncionamento: "" },
        }));
      }
    } catch (err) {
      alert(err.response?.data?.erro || "Não foi possível remover a evidência.");
      console.error(err);
    }
  };

  const abrirModalAssinatura = (comarca) => {
    setDocumentoAssinaturaAtual(null);
    setPapelAssinaturaAtual(null);
    setNomeAssinanteAtual("");
    setComarcaAssinaturaAtual(comarca);
    setShowAssinaturaModal(true);
  };

  const fecharModalAssinatura = () => {
    setShowAssinaturaModal(false);
    setComarcaAssinaturaAtual(null);
    setDocumentoAssinaturaAtual(null);
    setPapelAssinaturaAtual(null);
    setNomeAssinanteAtual("");
    setAssinaturaEmEdicao(false);
  };

  const getAssinaturaPoint = (event) => {
    const canvas = assinaturaCanvasRef.current;
    const rect = canvas.getBoundingClientRect();
    const sourceEvent = event.touches?.[0] || event;

    return {
      x: sourceEvent.clientX - rect.left,
      y: sourceEvent.clientY - rect.top,
    };
  };

  const iniciarAssinatura = (event) => {
    event.preventDefault();
    const canvas = assinaturaCanvasRef.current;
    const context = canvas.getContext("2d");
    const point = getAssinaturaPoint(event);

    assinandoRef.current = true;
    context.beginPath();
    context.moveTo(point.x, point.y);
  };

  const desenharAssinatura = (event) => {
    if (!assinandoRef.current) return;

    event.preventDefault();
    const canvas = assinaturaCanvasRef.current;
    const context = canvas.getContext("2d");
    const point = getAssinaturaPoint(event);

    context.lineTo(point.x, point.y);
    context.stroke();
    setAssinaturaEmEdicao(true);
  };

  const finalizarAssinatura = () => {
    assinandoRef.current = false;
  };

  const limparAssinatura = () => {
    const canvas = assinaturaCanvasRef.current;
    const context = canvas.getContext("2d");
    const { width, height } = canvas.getBoundingClientRect();

    context.clearRect(0, 0, width, height);
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, width, height);
    setAssinaturaEmEdicao(false);
  };

  const confirmarAssinatura = async () => {
    if (!comarcaAssinaturaAtual) return;

    if (documentoAssinaturaAtual && !nomeAssinanteAtual.trim()) {
      alert("Informe o nome de quem está assinando.");
      return;
    }

    if (!assinaturaEmEdicao) {
      alert("Desenhe a assinatura antes de confirmar.");
      return;
    }

    const canvas = assinaturaCanvasRef.current;
    const assinaturaBase64 = canvas.toDataURL("image/png");

    try {
      if (documentoAssinaturaAtual) {
        const response = await api.patch(
          `/documentos-internos/${documentoAssinaturaAtual.id}/assinaturas/${papelAssinaturaAtual}`,
          { assinaturaBase64, nomeAssinante: nomeAssinanteAtual },
        );
        setDocumentoVistoria((prev) => ({
          ...prev,
          documentoSalvo: response.data,
        }));
        setDocumentoSujo(false);
        setDocumentoMensagem("Assinatura registrada. O conteúdo desta versão está bloqueado.");
        setDocumentosVistoriaHistorico((prev) =>
          prev.map((documento) =>
            documento.id === response.data.id ? response.data : documento,
          ),
        );
        await carregarAuditoriaDocumento(response.data);
        fecharModalAssinatura();
        return;
      }

      const response = await api.patch(
        `/comarcas/${comarcaAssinaturaAtual.id}/vistoria/assinatura`,
        { assinaturaBase64 },
      );

      atualizarComarcaNaLista(response.data);
      setAssinaturasVistoria((prev) => {
        const next = { ...prev };
        delete next[comarcaAssinaturaAtual.id];
        return next;
      });
      fecharModalAssinatura();
    } catch (err) {
      alert(
        err.response?.data?.erro ||
          "Não foi possível salvar a assinatura no servidor.",
      );
      console.error(err);
    }
  };

  const getPercentualConcluido = (comarca) => {
    if (comarca.situacao === "CONCLUIDA") return 100;

    const progressoVistoria =
      (temFotoVistoria(comarca) ? 50 : 0) +
      (temAssinaturaVistoria(comarca) ? 50 : 0);

    if (!comarca.etapaAtual || comarca.etapaAtual === 1) {
      return progressoVistoria;
    }

    if (comarca.etapaAtual === 3) {
      return comarca.viradaRedeConcluida
        ? 90
        : Math.max(comarca.percentualConcluido ?? 0, 85);
    }

    return Math.min(comarca.percentualConcluido ?? progressoVistoria, 100);
  };

  const getProgressColor = (percentage) => {
    if (percentage >= 80) return "bg-green-500";
    if (percentage >= 50) return "bg-yellow-500";
    return "bg-red-500";
  };

  const materialEstoqueSelecionado = materiaisEstoque.find(
    (material) => String(material.id) === String(materialForm.materialId),
  );
  const controlaMetragem = (material) =>
    ["FRACIONADO", "METRAGEM", "BOBINA", "ROLO"].includes(material?.tipoControle);
  const saldoEstoqueMaterial = (material) =>
    controlaMetragem(material)
      ? Number(material?.metragemDisponivel || 0)
      : Number(material?.quantidadeDisponivel || 0);
  const saldoReservadoMaterial = (material) =>
    controlaMetragem(material)
      ? Number(material?.metragemReservada || 0)
      : Number(material?.quantidadeReservada || 0);
  const unidadeMaterial = (material) => (controlaMetragem(material) ? "m" : "un");
  const quantidadeLivreMaterialSelecionado = materialEstoqueSelecionado
    ? Math.max(
        0,
        saldoEstoqueMaterial(materialEstoqueSelecionado) -
          saldoReservadoMaterial(materialEstoqueSelecionado),
      )
    : 0;

  const comarcasFiltradas = comarcas.filter((comarca) => {
    if (filtroEtapa === "TODAS") return true;
    if (filtroEtapa === "CONCLUIDA") return comarca.situacao === "CONCLUIDA";
    if (comarca.situacao === "CONCLUIDA") return false;
    const etapa = comarca.etapaAtual || 1;
    return (
      (filtroEtapa === "VISTORIA" && etapa === 1) ||
      (filtroEtapa === "INFRA" && etapa === 2) ||
      (filtroEtapa === "VIRADA" && etapa === 3)
    );
  });
  const resumoEtapas = {
    vistoria: comarcas.filter((comarca) => comarca.situacao !== "CONCLUIDA" && (comarca.etapaAtual || 1) === 1).length,
    infraestrutura: comarcas.filter((comarca) => comarca.situacao !== "CONCLUIDA" && comarca.etapaAtual === 2).length,
    virada: comarcas.filter((comarca) => comarca.situacao !== "CONCLUIDA" && comarca.etapaAtual === 3).length,
    concluidas: comarcas.filter((comarca) => comarca.situacao === "CONCLUIDA").length,
  };

  if (loading) return <LoadingSpinner />;
  if (error) return <Alert type="error" message={error} />;

  return (
    <div className="mx-auto max-w-[1600px] space-y-5">
      <PageHeader
        eyebrow="Operação"
        title="Gestão de Obras"
        description="Acompanhe vistoria, infraestrutura, virada de rede e encerramento por OS."
        actions={
        <div className="grid w-full grid-cols-1 gap-3 sm:w-auto sm:grid-cols-[auto_minmax(12rem,1fr)_auto] sm:items-end">
          {podeGerenciarObra && <label className="flex items-center gap-2 text-xs font-bold text-slate-600 sm:pb-2">
            <input
              type="checkbox"
              checked={incluirArquivados}
              onChange={(event) => setIncluirArquivados(event.target.checked)}
            />
            Mostrar arquivadas
          </label>}
          <label className="block min-w-0">
            <span className="mb-1 block text-[10px] font-black uppercase text-slate-500">
              Filtrar por etapa
            </span>
            <select
              value={filtroEtapa}
              onChange={(event) => setFiltroEtapa(event.target.value)}
              className="w-full rounded border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 sm:min-w-48"
            >
              <option value="TODAS">Todas as obras</option>
              <option value="VISTORIA">Vistoria</option>
              <option value="INFRA">Infraestrutura</option>
              <option value="VIRADA">Virada de Rede</option>
              <option value="CONCLUIDA">Concluídas</option>
            </select>
          </label>
          <span className="text-xs font-bold text-slate-500 sm:pb-2">
            {comarcasFiltradas.length} resultado(s)
          </span>
        </div>
        }
      />

      <section className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {[
          ["Vistoria", resumoEtapas.vistoria, "border-amber-200", "text-amber-700"],
          ["Infraestrutura", resumoEtapas.infraestrutura, "border-cyan-200", "text-cyan-700"],
          ["Virada de rede", resumoEtapas.virada, "border-blue-200", "text-blue-700"],
          ["Concluídas", resumoEtapas.concluidas, "border-emerald-200", "text-emerald-700"],
        ].map(([label, valor, borda, cor]) => (
          <div key={label} className={`rounded-lg border bg-white p-4 ${borda}`}>
            <p className="text-xs font-semibold text-slate-500">{label}</p>
            <p className={`mt-2 text-2xl font-bold ${cor}`}>{valor}</p>
          </div>
        ))}
      </section>

      <div>
        <FilaPendenciasOperacionais area="GESTAO_OBRAS" titulo="Pendências da Gestão de Obras" limite={4} recolhivel inicialmenteAberta={false} />
      </div>

      <div className="grid grid-cols-1 gap-5 2xl:grid-cols-2">
        {comarcasFiltradas.map((comarca) => {
          const materiaisPrevistos = Array.isArray(comarca.materiais)
            ? comarca.materiais
            : [];
          const totalMateriaisPrevistos = materiaisPrevistos.reduce(
            (total, material) => total + (material.quantidadePrevista || 0),
            0,
          );
          const totalMateriaisAuditados = materiaisPrevistos.reduce(
            (total, material) => total + (material.quantidadeAuditada || 0),
            0,
          );
          const fotoVistoria = fotosVistoria[comarca.id];
          const assinaturaValida = assinaturasVistoria[comarca.id];
          const fotoVistoriaPreview =
            fotoVistoria?.previewUrl ||
            (comarca.fotoVistoriaUrl
              ? `${API_BASE_URL}/comarcas/${comarca.id}/vistoria/foto?v=${encodeURIComponent(comarca.fotoVistoriaUrl)}`
              : null);
          const assinaturaPreview =
            assinaturaValida?.base64 || comarca.assinaturaBase64;
          const fotoVistoriaConcluida = !!fotoVistoriaPreview;
          const assinaturaConcluida = !!assinaturaPreview;
          const percentualConcluido = getPercentualConcluido(comarca);
          const etapaAtual = comarca.etapaAtual || 1;
          const obraConcluida = comarca.situacao === "CONCLUIDA";
          const retiradaExecutada =
            !!comarca.ordemServico?.status &&
            !["ABERTA", "AGUARDANDO_VISTORIA", "AGUARDANDO_RETIRADA"].includes(
              comarca.ordemServico.status,
            );
          const materiaisFaltantes = materiaisPrevistos.filter(
            (material) => material.materialFaltante,
          );
          const viradaForm = getViradaForm(comarca);

          return (
          <div
            key={comarca.id}
            className="relative flex flex-col justify-between overflow-hidden rounded border border-slate-200 bg-white shadow-sm"
          >
            {podeGerenciarObra && comarca.arquivado && (
              <button
                type="button"
                onClick={() => alterarArquivamentoComarca(comarca)}
                className="absolute right-3 top-3 z-20 inline-flex items-center gap-1 rounded-md bg-emerald-600 px-3 py-2 text-xs font-bold text-white shadow"
              >
                <RotateCcw size={14} /> Restaurar obra
              </button>
            )}
            <div
              className={`flex-1 space-y-4 bg-white p-5 ${comarca.arquivado ? "pointer-events-none opacity-50" : ""} ${comarca.pendencias ? "border-l-4 border-l-red-500" : ""}`}
            >
              {/* Topo do Card: Identificador Único da OS + Stepper Visual de Linha do Tempo */}
              <div className="space-y-3">
                <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0">
                  <h3 className="text-xl font-bold text-slate-800">
                    {comarca.nomeComarca}
                  </h3>
                  <div className="mt-1 inline-flex items-center gap-1 text-xs font-mono font-bold text-blue-600 bg-blue-50 px-2 py-0.5 rounded border border-blue-100">
                    <FileText size={12} /> OS:{" "}
                    {comarca.ordemServico?.numeroOs || `OS-2026-0${comarca.id}`}
                  </div>
                  </div>
                  <span className={`w-fit shrink-0 rounded px-2.5 py-1 text-[10px] font-black uppercase ${obraConcluida ? "bg-emerald-50 text-emerald-700" : etapaAtual === 1 ? "bg-amber-50 text-amber-700" : etapaAtual === 2 ? "bg-cyan-50 text-cyan-700" : "bg-blue-50 text-blue-700"}`}>
                    {obraConcluida ? "Concluída" : etapaAtual === 1 ? "Em vistoria" : etapaAtual === 2 ? "Em infraestrutura" : "Em virada de rede"}
                  </span>
                </div>

                <div className="grid grid-cols-4 gap-1 rounded-lg bg-slate-100 p-1 text-[9px] font-black uppercase sm:text-[10px]">
                  <span className={`flex min-h-8 items-center justify-center rounded px-1 text-center ${etapaAtual === 1 && !obraConcluida ? "bg-amber-500 text-white" : etapaAtual > 1 || obraConcluida ? "bg-white text-slate-600" : "text-slate-400"}`}>1. Vistoria</span>
                  <span className={`flex min-h-8 items-center justify-center rounded px-1 text-center ${etapaAtual === 2 && !obraConcluida ? "bg-cyan-600 text-white" : etapaAtual > 2 || obraConcluida ? "bg-white text-slate-600" : "text-slate-400"}`}>2. Infra</span>
                  <span className={`flex min-h-8 items-center justify-center rounded px-1 text-center ${etapaAtual === 3 && !obraConcluida ? "bg-blue-600 text-white" : obraConcluida ? "bg-white text-slate-600" : "text-slate-400"}`}>3. Virada</span>
                  <span className={`flex min-h-8 items-center justify-center rounded px-1 text-center ${obraConcluida ? "bg-emerald-600 text-white" : "text-slate-400"}`}>4. Concluída</span>
                </div>

                  <div className="flex gap-2 overflow-x-auto pb-1">
                    <button
                      type="button"
                      onClick={() => abrirDocumento("os", comarca)}
                      className="shrink-0 rounded-md border border-blue-200 bg-white px-3 py-2 text-[10px] font-black uppercase text-blue-700 hover:bg-blue-50"
                    >
                      Visualizar OS
                    </button>
                    <button
                      type="button"
                      onClick={() => abrirDocumento("retirada", comarca)}
                      className="shrink-0 rounded-md border border-slate-200 bg-white px-3 py-2 text-[10px] font-black uppercase text-slate-700 hover:bg-slate-50"
                    >
                      Visualizar Ordem de Retirada
                    </button>
                    {podeGerarOr && <button
                      type="button"
                      onClick={() => gerarOrAdicional(comarca)}
                      disabled={!comarca.ordemServico?.id}
                      className="shrink-0 rounded-md border border-emerald-200 bg-white px-3 py-2 text-[10px] font-black uppercase text-emerald-700 hover:bg-emerald-50 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300"
                    >
                      Gerar OR Adicional
                    </button>}
                    {podeGerenciarObra && <button
                      type="button"
                      onClick={() => alterarArquivamentoComarca(comarca)}
                      className="inline-flex shrink-0 items-center gap-1 rounded-md border border-red-200 bg-white px-3 py-2 text-[10px] font-black uppercase text-red-700 hover:bg-red-50"
                    >
                      <Archive size={12} /> Arquivar obra
                    </button>}
                </div>
              </div>

              {/* Dados Estruturados de Campo */}
              <div className="space-y-2 text-sm text-slate-700">
                <div className="flex items-start gap-3">
                  <MapPin
                    size={18}
                    className="text-blue-500 mt-0.5 flex-shrink-0"
                  />
                  <div>
                    <p className="text-xs font-bold text-slate-400 uppercase">
                      Endereço
                    </p>
                    <p className="text-slate-800 font-medium">
                      {comarca.endereco || "Não informado"}
                    </p>
                  </div>
                </div>

                <div className="flex items-start gap-3">
                  <User
                    size={18}
                    className="text-purple-500 mt-0.5 flex-shrink-0"
                  />
                  <div>
                    <p className="text-xs font-bold text-slate-400 uppercase">
                      Juiz Responsável
                    </p>
                    <p className="text-slate-800 font-medium">
                      {comarca.juizResponsavel || "Não informado"}
                    </p>
                  </div>
                </div>

                {/* Painel de Previsão de Materiais / Insumos Planejados */}
                <div className="flex items-start gap-3 pt-1">
                  <Zap
                    size={18}
                    className="text-yellow-500 mt-0.5 flex-shrink-0"
                  />
                  <div className="flex-1 min-w-0">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <p className="text-xs font-bold text-slate-400 uppercase">
                        Painel de Previsão de Materiais
                      </p>
                      <div className="flex flex-wrap gap-1">
                        {podeExecutarObra && <button
                          type="button"
                          onClick={() => abrirModalFaltantes(comarca)}
                          className="inline-flex items-center gap-1 rounded-md border border-amber-200 bg-amber-50 px-2 py-1 text-[10px] font-bold uppercase tracking-wide text-amber-700 hover:bg-amber-100"
                        >
                          <AlertTriangle size={12} />
                          Faltantes
                        </button>}
                      </div>
                    </div>
                    {materiaisPrevistos.length > 0 ? (
                      <div className="mt-2 overflow-x-auto rounded-lg border border-slate-200">
                        <div className="bg-slate-50 px-3 py-2 flex items-center justify-between gap-2 text-xs font-bold text-slate-600">
                          <span>{materiaisPrevistos.length} itens previstos</span>
                          <span>
                            {totalMateriaisPrevistos} prev. /{" "}
                            {totalMateriaisAuditados} audit.
                          </span>
                        </div>
                        <div className="grid min-w-[760px] grid-cols-[1.1fr_auto_auto_minmax(8rem,1fr)_auto_auto_auto] gap-3 bg-white px-3 py-2 text-[10px] font-black uppercase tracking-wide text-slate-400 border-b border-slate-100">
                          <span>Material</span>
                          <span>Prev.</span>
                          <span>Aud.</span>
                          <span>O que está faltando</span>
                          <span>Status</span>
                          <span>Timeline</span>
                          <span>Origem</span>
                        </div>
                        <div className="divide-y divide-slate-100">
                          {materiaisPrevistos.map((material) => (
                            <div
                              key={material.id || material.nomeMaterial}
                              className="grid min-w-[760px] grid-cols-[1.1fr_auto_auto_minmax(8rem,1fr)_auto_auto_auto] items-center gap-3 px-3 py-2 text-xs"
                            >
                              <span className="flex min-w-0 items-center gap-2">
                                {material.material?.fotoProdutoUrl ? (
                                  <img
                                    src={getArquivoUrl(material.material.fotoProdutoUrl)}
                                    alt={`Foto de ${material.nomeMaterial}`}
                                    className="h-9 w-9 flex-shrink-0 rounded-md border border-slate-200 object-cover"
                                  />
                                ) : (
                                  <span className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-md border border-slate-200 bg-slate-50 text-slate-400">
                                    <Package size={16} />
                                  </span>
                                )}
                                <span className="min-w-0">
                                  <span className="block truncate font-semibold text-slate-700">
                                    {material.nomeMaterial || "Material sem nome"}
                                  </span>
                                  <span className="mt-0.5 flex flex-wrap gap-1">
                                    <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[9px] font-bold uppercase text-slate-500">
                                      {getCategoriaMaterialLabel(
                                        material.material?.categoria,
                                      )}
                                    </span>
                                    {material.itemAdicional && (
                                      <span className="rounded bg-emerald-50 px-1.5 py-0.5 text-[9px] font-bold uppercase text-emerald-700">
                                        Adicional
                                      </span>
                                    )}
                                  </span>
                                  {material.material?.descricao && (
                                    <span className="mt-0.5 block truncate text-[10px] font-normal text-slate-400">
                                      {material.material.descricao}
                                    </span>
                                  )}
                                </span>
                              </span>
                              <span className="text-slate-500">
                                Prev.:{" "}
                                <strong className="text-slate-800">
                                  {material.quantidadePrevista || 0}
                                </strong>
                              </span>
                              <span className="text-slate-500">
                                Aud.:{" "}
                                <strong className="text-slate-800">
                                  {material.quantidadeAuditada || 0}
                                </strong>
                              </span>
                              <span className="text-slate-500 whitespace-normal">
                                {material.materialFaltante ? (
                                  <span className="rounded bg-amber-50 px-2 py-0.5 text-[10px] font-bold text-amber-800">
                                    {material.descricaoFaltante ||
                                      comarca.descricaoMaterialFaltante ||
                                      "Material faltante"}
                                  </span>
                                ) : (
                                  <span className="text-slate-300">-</span>
                                )}
                              </span>
                              <span className="inline-flex flex-wrap gap-1">
                              {material.estoqueReservado && !material.estoqueBaixado && (
                                <span className="rounded bg-blue-50 px-2 py-0.5 text-[10px] font-bold uppercase text-blue-700">
                                  Reservado
                                </span>
                              )}
                              {material.estoqueBaixado && (
                                <span className="rounded bg-emerald-50 px-2 py-0.5 text-[10px] font-bold uppercase text-emerald-700">
                                  Baixado
                                </span>
                              )}
                              </span>
                              <button
                                type="button"
                                onClick={() => abrirModalTimeline(material)}
                                className="rounded border border-slate-200 px-2 py-1 text-[10px] font-bold uppercase text-slate-600 hover:bg-slate-50"
                              >
                                Horários
                              </button>
                              <span className="rounded bg-slate-100 px-2 py-1 text-[10px] font-bold uppercase text-slate-500">
                                Definido na OS
                              </span>
                            </div>
                          ))}
                        </div>
                        <div className="border-t border-slate-100 bg-slate-50 px-3 py-2">
                          <p className="mb-2 text-[10px] font-black uppercase tracking-wide text-slate-400">
                            Timeline logística
                          </p>
                          <div className="space-y-1">
                            {materiaisPrevistos.map((material) => (
                              <div
                                key={`timeline-${material.id || material.nomeMaterial}`}
                                className="grid min-w-[620px] grid-cols-[1fr_auto_auto_auto] gap-2 text-[10px] text-slate-500"
                              >
                                <span className="font-semibold text-slate-700 truncate">
                                  {material.nomeMaterial}
                                </span>
                                <span>Solic.: {formatarDataHora(material.dataHoraSolicitacao)}</span>
                                <span>Ret.: {formatarDataHora(material.dataHoraRetirada)}</span>
                                <span>Uso: {formatarDataHora(material.dataHoraUso)}</span>
                              </div>
                            ))}
                          </div>
                        </div>
                      </div>
                    ) : (
                      <p className="mt-2 rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-3 text-xs text-slate-500">
                        Nenhum material previsto cadastrado.{" "}
                        {comarca.quantidadePontos ?? 0} pontos previstos para
                        instalação física.
                      </p>
                    )}
                  </div>
                </div>
              </div>

              {/* 🛑 SEÇÃO DE VISTORIA COM GERENTE COM CARDS DE UPLOAD/ASSINATURA (ETAPA 1) */}
              {(!comarca.etapaAtual || comarca.etapaAtual === 1) && (
                <div className="bg-amber-50/40 border border-amber-200/70 rounded-xl p-3 grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs font-bold tracking-wide">
                  <div className="relative">
                    <label
                      className={`h-full border border-dashed p-3 rounded-lg text-center transition flex flex-col items-center justify-center gap-2 ${podeExecutarObra ? "cursor-pointer hover:border-blue-400" : "cursor-default"} ${fotoVistoriaConcluida ? "bg-emerald-50 border-emerald-300 text-emerald-800" : "bg-white text-slate-500"}`}
                    >
                      {podeExecutarObra && <input
                        type="file"
                        accept="image/jpeg,image/png"
                        className="hidden"
                        onChange={(e) => handleFotoVistoriaChange(e, comarca.id)}
                      />}
                      <span className="flex items-center justify-center gap-1">
                        <Upload size={14} />{" "}
                        {fotoVistoriaConcluida
                          ? "Foto Carregada"
                          : "Fazer Upload Foto"}
                      </span>
                      {fotoVistoriaPreview && (
                        <img
                          src={fotoVistoriaPreview}
                          alt={`Preview da vistoria ${fotoVistoria?.file?.name || "salva"}`}
                          className="h-16 w-full rounded-md object-cover border border-emerald-200"
                        />
                      )}
                    </label>
                    {podeExecutarObra && fotoVistoriaConcluida && (
                      <button
                        type="button"
                        title="Remover foto da vistoria"
                        onClick={() => removerEvidencia(comarca.id, "foto")}
                        className="absolute right-2 top-2 rounded-md bg-white p-1.5 text-red-600 shadow hover:bg-red-50"
                      >
                        <Trash2 size={14} />
                      </button>
                    )}
                  </div>
                  <div
                    onClick={() => podeExecutarObra && abrirModalAssinatura(comarca)}
                    className={`relative border border-dashed p-3 rounded-lg text-center transition flex flex-col items-center justify-center gap-2 ${podeExecutarObra ? "cursor-pointer hover:border-blue-400" : "cursor-default"} ${assinaturaConcluida ? "bg-emerald-50 border-emerald-300 text-emerald-800" : "bg-white text-slate-500"}`}
                  >
                    <span className="flex items-center justify-center gap-1">
                      <PenTool size={14} />{" "}
                      {assinaturaConcluida
                        ? "Termo Assinado"
                        : "Coletar Assinatura"}
                    </span>
                    {assinaturaPreview && (
                      <img
                        src={assinaturaPreview}
                        alt="Preview da assinatura coletada"
                        className="h-16 w-full rounded-md object-contain border border-emerald-200 bg-white"
                      />
                    )}
                    {podeExecutarObra && assinaturaConcluida && (
                      <button
                        type="button"
                        title="Remover termo assinado"
                        onClick={(event) => {
                          event.stopPropagation();
                          removerEvidencia(comarca.id, "assinatura");
                        }}
                        className="absolute right-2 top-2 rounded-md bg-white p-1.5 text-red-600 shadow hover:bg-red-50"
                      >
                        <Trash2 size={14} />
                      </button>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => abrirDocumentoVistoria(comarca, DOCUMENTO_INICIAL)}
                    className="sm:col-span-2 flex items-center justify-center gap-2 rounded-lg border border-blue-200 bg-white px-3 py-2 text-xs font-black uppercase tracking-wide text-blue-700 transition hover:bg-blue-50"
                  >
                    <FileText size={14} />
                    Documento Inicial - Serviços Previstos
                  </button>
                </div>
              )}

              {etapaAtual === 3 && (
                <div className="rounded-xl border border-blue-200 bg-blue-50/40 p-3 space-y-3">
                  <div className="flex items-center justify-between gap-2">
                    <div>
                      <p className="text-xs font-black uppercase tracking-wide text-blue-800">
                        3. Virada de Rede
                      </p>
                      <p className="text-xs text-blue-700">
                        Provas de funcionamento, conectividade e validação final.
                      </p>
                    </div>
                    {comarca.viradaRedeConcluida && (
                      <span className="inline-flex items-center gap-1 rounded bg-emerald-100 px-2 py-1 text-[10px] font-bold uppercase text-emerald-700">
                        <CheckCircle2 size={12} /> Concluída
                      </span>
                    )}
                  </div>
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    <div className="relative">
                      <label className="block text-[10px] font-bold uppercase text-slate-500 mb-1">
                        Provas de funcionamento
                      </label>
                      <label
                        className={`border border-dashed p-3 rounded-lg text-center transition flex flex-col items-center justify-center gap-2 text-xs font-bold ${podeExecutarObra ? "cursor-pointer" : "cursor-default"} ${
                          comarca.viradaRedeProvasFuncionamento
                            ? "bg-emerald-50 border-emerald-300 text-emerald-800"
                            : "bg-white hover:border-blue-400 text-slate-500"
                        }`}
                      >
                        {podeExecutarObra && <input
                          type="file"
                          accept="image/jpeg,image/png"
                          disabled={obraConcluida}
                          className="hidden"
                          onChange={(e) =>
                            handleProvaViradaRedeChange(e, comarca.id)
                          }
                        />}
                        <span className="flex items-center justify-center gap-1">
                          <Upload size={14} />
                          {comarca.viradaRedeProvasFuncionamento
                            ? "Prova Carregada"
                            : "Enviar Foto da Prova"}
                        </span>
                        {comarca.viradaRedeProvasFuncionamento && (
                          <img
                            src={`${API_BASE_URL}/comarcas/${comarca.id}/virada-rede/prova?v=${encodeURIComponent(comarca.viradaRedeProvasFuncionamento)}`}
                            alt={`Prova de funcionamento ${comarca.nomeComarca}`}
                            className="h-24 w-full rounded-md object-cover border border-emerald-200"
                          />
                        )}
                      </label>
                      {podeExecutarObra && comarca.viradaRedeProvasFuncionamento &&
                        !comarca.viradaRedeConcluida &&
                        !obraConcluida && (
                          <button
                            type="button"
                            title="Remover prova de funcionamento"
                            onClick={() => removerEvidencia(comarca.id, "prova")}
                            className="absolute right-2 top-7 rounded-md bg-white p-1.5 text-red-600 shadow hover:bg-red-50"
                          >
                            <Trash2 size={14} />
                          </button>
                        )}
                    </div>
                    <div>
                      <label className="block text-[10px] font-bold uppercase text-slate-500 mb-1">
                        Checklist de validação
                      </label>
                      <textarea
                        rows="3"
                        disabled={obraConcluida || !podeExecutarObra}
                        value={viradaForm.checklist}
                        onChange={(e) =>
                          setViradaFormValue(comarca.id, "checklist", e.target.value)
                        }
                        className="w-full rounded-lg border border-blue-100 px-3 py-2 text-xs focus:outline-none focus:ring-2 focus:ring-blue-400"
                        placeholder="Ex: ping ok, rota ok, link ativo, acesso validado..."
                      />
                    </div>
                  </div>
                  <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
                    <label className="inline-flex items-center gap-2 text-xs font-bold text-slate-700">
                      <input
                        type="checkbox"
                        disabled={obraConcluida || !podeExecutarObra}
                        checked={viradaForm.concluida}
                        onChange={(e) =>
                          setViradaFormValue(comarca.id, "concluida", e.target.checked)
                        }
                      />
                      Virada de Rede concluída
                    </label>
                    {podeExecutarObra && <button
                      type="button"
                      onClick={() => handleSalvarViradaRede(comarca)}
                      disabled={obraConcluida}
                      className="rounded-lg bg-blue-600 px-3 py-2 text-xs font-bold text-white hover:bg-blue-700"
                    >
                      Salvar Virada
                    </button>}
                  </div>
                  <button
                    type="button"
                    onClick={() => abrirDocumentoVistoria(comarca, DOCUMENTO_FINAL)}
                    className="flex w-full items-center justify-center gap-2 rounded-lg border border-blue-300 bg-white px-3 py-2 text-xs font-black uppercase tracking-wide text-blue-800 transition hover:bg-blue-100"
                  >
                    <FileText size={14} />
                    Documento Final - Encerramento e Aceite
                  </button>
                  {podeExecutarObra && comarca.viradaRedeConcluida && (
                    <button
                      type="button"
                      onClick={() => handleConcluirObra(comarca)}
                      disabled={obraConcluida || encerrandoComarcaId === comarca.id}
                      className="flex w-full items-center justify-center gap-2 rounded-lg bg-emerald-600 px-3 py-2.5 text-xs font-black uppercase tracking-wide text-white transition hover:bg-emerald-700 disabled:cursor-not-allowed disabled:bg-emerald-300"
                    >
                      <ShieldCheck size={15} />
                      {obraConcluida
                        ? "Obra concluída"
                        : encerrandoComarcaId === comarca.id
                          ? "Validando encerramento..."
                          : "Validar e concluir obra"}
                    </button>
                  )}
                  {obraConcluida && (
                    <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-xs text-emerald-800">
                      <p className="font-black uppercase">Encerramento registrado</p>
                      <p className="mt-1">
                        {comarca.concluidaPor || "Responsável registrado"} em {formatarDataHora(comarca.dataConclusao)}
                      </p>
                    </div>
                  )}
                </div>
              )}

              {/* Barra de Progresso Operacional */}
              <div>
                <div className="flex items-center justify-between mb-2">
                  <p className="text-xs font-bold text-slate-400 uppercase">
                    Progresso de Conclusão
                  </p>
                  <span className="text-sm font-bold text-slate-700">
                    {percentualConcluido}%
                  </span>
                </div>
                <div className="w-full bg-slate-200 rounded-full h-3 overflow-hidden">
                  <div
                    className={`h-full transition-all duration-300 ${getProgressColor(percentualConcluido)}`}
                    style={{
                      width: `${Math.min(percentualConcluido, 100)}%`,
                    }}
                  />
                </div>
              </div>

              {comarca.pendencias && (
                <div className="bg-red-100 border border-red-300 rounded-lg p-3 text-xs text-red-800 flex items-center gap-2">
                  <AlertTriangle size={14} className="flex-shrink-0" />
                  <p>
                    <span className="font-bold">Pendência:</span>{" "}
                    {comarca.pendencias}
                  </p>
                </div>
              )}

              {materiaisFaltantes.length > 0 && (
                <div className="bg-amber-100 border border-amber-300 rounded-lg p-3 text-xs text-amber-900 flex items-start gap-2">
                  <AlertTriangle size={14} className="flex-shrink-0 mt-0.5" />
                  <div>
                    <p className="font-bold">O que está faltando</p>
                    <p>{comarca.descricaoMaterialFaltante}</p>
                    <p className="mt-1 text-[10px] font-bold uppercase tracking-wide">
                      {materiaisFaltantes
                        .map((material) => material.nomeMaterial)
                        .join(", ")}
                    </p>
                  </div>
                </div>
              )}
            </div>

            {/* Ações dinâmicas com trava de avanço baseada na etapa */}
            <div className="bg-slate-50 px-6 py-3 border-t border-slate-200 flex flex-col sm:flex-row gap-2">
              {obraConcluida ? (
                <button
                  type="button"
                  onClick={() => handleAbrirResumoEncerramento(comarca)}
                  disabled={carregandoResumoId === comarca.id}
                  className="flex-1 bg-emerald-600 hover:bg-emerald-700 disabled:bg-emerald-300 text-white font-bold py-2 px-4 rounded-lg text-xs transition-colors shadow-sm uppercase tracking-wider"
                >
                  {carregandoResumoId === comarca.id ? "Carregando encerramento..." : "Consultar encerramento"}
                </button>
              ) : podeExecutarObra && etapaAtual === 1 ? (
                <button
                  onClick={() => handleAvancarFase(comarca)}
                  className="flex-1 bg-amber-600 hover:bg-amber-700 text-white font-bold py-2 px-4 rounded-lg text-xs transition-colors shadow-sm uppercase tracking-wider"
                >
                  Homologar Vistoria e Liberar Obras
                </button>
              ) : podeExecutarObra && etapaAtual === 2 ? (
                <button
                  onClick={() => handleAvancarFase(comarca)}
                  disabled={!retiradaExecutada}
                  title={
                    retiradaExecutada
                      ? "Liberar Virada de Rede"
                      : "A Ordem de Retirada precisa ser executada no estoque."
                  }
                  className="flex-1 bg-blue-600 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded-lg text-xs transition-colors shadow-sm uppercase tracking-wider disabled:cursor-not-allowed disabled:bg-slate-300 disabled:text-slate-600 disabled:shadow-none"
                >
                  {retiradaExecutada
                    ? "Liberar Virada de Rede"
                    : "Aguardando retirada da OR"}
                </button>
              ) : podeExecutarObra ? (
                <button
                  onClick={() => handleOpenModal(comarca)}
                  className="flex-1 bg-blue-600 hover:bg-blue-700 text-white font-semibold py-2 px-4 rounded-lg text-xs transition-colors shadow-sm"
                >
                  Registrar Pendência/Ajustar Progresso
                </button>
              ) : null}
              <button
                onClick={() => setComarcaHistorico(comarca)}
                className="flex-shrink-0 flex items-center justify-center gap-1 bg-white hover:bg-slate-100 border text-slate-700 font-bold py-2 px-4 rounded-lg text-xs transition-colors"
              >
                <History size={14} />
                Histórico
              </button>
            </div>
          </div>
          );
        })}
        {comarcasFiltradas.length === 0 && (
          <div className="2xl:col-span-2 rounded-lg border border-dashed border-slate-300 bg-white">
            <EmptyState
              icon={MapPin}
              title="Nenhuma obra encontrada nesta etapa"
              description="Escolha outra etapa ou habilite a visualização de obras arquivadas."
            />
          </div>
        )}
      </div>

      {/* Modal Ajustar Progresso */}
      <Modal
        isOpen={showModal}
        onClose={handleCloseModal}
        title={`Ajustar Progresso - ${selectedComarca?.nomeComarca}`}
      >
        <form onSubmit={handleSubmit} className="space-y-6">
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-2">
              Percentual de Conclusão (%)
            </label>
            <div className="flex items-center gap-4">
              <input
                type="number"
                name="percentualConcluido"
                value={formData.percentualConcluido}
                onChange={handleInputChange}
                min="0"
                max="100"
                className="flex-1 px-4 py-2 border border-slate-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
              <span className="text-slate-700 font-semibold">%</span>
            </div>
          </div>
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-2">
              Pendências/Observações
            </label>
            <textarea
              name="pendencias"
              value={formData.pendencias}
              onChange={handleInputChange}
              rows="4"
              className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
              placeholder="Descreva qualquer detalhe em campo..."
            />
          </div>
          <div className="flex gap-3 justify-end">
            <button
              type="button"
              onClick={handleCloseModal}
              className="px-5 py-2 border rounded-lg text-slate-700 font-semibold hover:bg-slate-50 transition-colors"
            >
              Cancelar
            </button>
            <button
              type="submit"
              className="px-5 py-2 bg-blue-600 hover:bg-blue-700 text-white font-semibold rounded-lg transition-colors"
            >
              Salvar Alterações
            </button>
          </div>
        </form>
      </Modal>

      <Modal
        isOpen={showMaterialModal}
        onClose={fecharModalMaterial}
        title={`${materialEmEdicao ? "Editar" : materialForm.itemAdicional ? "Adicionar Item Adicional" : "Adicionar Material Previsto"} - ${comarcaMaterialAtual?.nomeComarca || ""}`}
      >
        <form onSubmit={handleSalvarMaterialPrevisto} className="space-y-5">
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-2">
              Material do Estoque *
            </label>
            <select
              name="materialId"
              required
              value={materialForm.materialId}
              onChange={handleMaterialFormChange}
              className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="">Selecione um material cadastrado</option>
              {materiaisEstoque.map((material) => (
                <option key={material.id} value={material.id}>
                  {material.nome}{" "}
                  {material.partNumber ? `- ${material.partNumber}` : ""}{" "}
                  (
                  {Math.max(
                    0,
                    saldoEstoqueMaterial(material) - saldoReservadoMaterial(material),
                  )}{" "}
                  {unidadeMaterial(material)} disponível)
                </option>
              ))}
            </select>
            {materialEstoqueSelecionado && (
              <div className="mt-2 rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs">
                <div className="mb-3 flex gap-3">
                  {materialEstoqueSelecionado.fotoProdutoUrl ? (
                    <img
                      src={getArquivoUrl(materialEstoqueSelecionado.fotoProdutoUrl)}
                      alt={`Foto de ${materialEstoqueSelecionado.nome}`}
                      className="h-14 w-14 flex-shrink-0 rounded-md border border-slate-200 object-cover"
                    />
                  ) : (
                    <span className="flex h-14 w-14 flex-shrink-0 items-center justify-center rounded-md border border-slate-200 bg-white text-slate-400">
                      <Package size={18} />
                    </span>
                  )}
                  <div className="min-w-0">
                    <span className="rounded bg-white px-2 py-0.5 text-[10px] font-bold uppercase text-slate-500">
                      {getCategoriaMaterialLabel(
                        materialEstoqueSelecionado.categoria,
                      )}
                    </span>
                    {materialEstoqueSelecionado.descricao && (
                      <p className="mt-1 line-clamp-2 text-slate-600">
                        {materialEstoqueSelecionado.descricao}
                      </p>
                    )}
                  </div>
                </div>
                <div className="grid grid-cols-3 gap-2">
                  <span className="rounded bg-white px-2 py-1 text-slate-500">
                    Em estoque:{" "}
                    <strong className="text-slate-800">
                      {saldoEstoqueMaterial(materialEstoqueSelecionado)} {unidadeMaterial(materialEstoqueSelecionado)}
                    </strong>
                  </span>
                  <span className="rounded bg-blue-50 px-2 py-1 text-blue-700">
                    Reservado:{" "}
                    <strong>{saldoReservadoMaterial(materialEstoqueSelecionado)} {unidadeMaterial(materialEstoqueSelecionado)}</strong>
                  </span>
                  <span className="rounded bg-emerald-50 px-2 py-1 text-emerald-700">
                    Disponível: <strong>{quantidadeLivreMaterialSelecionado} {unidadeMaterial(materialEstoqueSelecionado)}</strong>
                  </span>
                </div>
              </div>
            )}
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-2">
              Quantidade Prevista *
            </label>
            <input
              type="number"
              name="quantidadePrevista"
              required
              min={controlaMetragem(materialEstoqueSelecionado) ? "0.001" : "1"}
              step={controlaMetragem(materialEstoqueSelecionado) ? "0.001" : "1"}
              value={materialForm.quantidadePrevista}
              onChange={handleMaterialFormChange}
              className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="0"
            />
          </div>

          <div className="flex gap-3 justify-end">
            <button
              type="button"
              onClick={fecharModalMaterial}
              className="px-5 py-2 border rounded-lg text-slate-700 font-semibold hover:bg-slate-50 transition-colors"
            >
              Cancelar
            </button>
            <button
              type="submit"
              className="px-5 py-2 bg-blue-600 hover:bg-blue-700 text-white font-semibold rounded-lg transition-colors"
            >
              {materialEmEdicao
                ? "Salvar Material"
                : materialForm.itemAdicional
                  ? "Adicionar Item Adicional"
                  : "Adicionar Material"}
            </button>
          </div>
        </form>
      </Modal>

      <Modal
        isOpen={showFaltantesModal}
        onClose={fecharModalFaltantes}
        title={`Materiais Faltantes - ${comarcaFaltantesAtual?.nomeComarca || ""}`}
      >
        <form onSubmit={handleSalvarFaltantes} className="space-y-5">
          <label className="inline-flex items-center gap-2 text-sm font-bold text-slate-700">
            <input
              type="checkbox"
              checked={faltantesForm.faltouMaterial}
              onChange={(e) =>
                setFaltantesForm((prev) => ({
                  ...prev,
                  faltouMaterial: e.target.checked,
                  materialItemIds: e.target.checked ? prev.materialItemIds : [],
                  descricao: e.target.checked ? prev.descricao : "",
                }))
              }
            />
            Faltou material?
          </label>

          {faltantesForm.faltouMaterial && (
            <>
              <div>
                <p className="block text-sm font-semibold text-slate-700 mb-2">
                  Materiais que faltaram *
                </p>
                <div className="max-h-48 overflow-auto rounded-lg border border-slate-200 divide-y divide-slate-100">
                  {(comarcaFaltantesAtual?.materiais || []).map((material) => (
                    <label
                      key={material.id}
                      className="flex items-center justify-between gap-3 px-3 py-2 text-sm"
                    >
                      <span className="font-medium text-slate-700">
                        {material.nomeMaterial}
                      </span>
                      <input
                        type="checkbox"
                        checked={faltantesForm.materialItemIds.includes(material.id)}
                        onChange={() => toggleMaterialFaltante(material.id)}
                      />
                    </label>
                  ))}
                </div>
              </div>

              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-2">
                  Descrição detalhada *
                </label>
                <textarea
                  rows="4"
                  required
                  value={faltantesForm.descricao}
                  onChange={(e) =>
                    setFaltantesForm((prev) => ({
                      ...prev,
                      descricao: e.target.value,
                    }))
                  }
                  className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-amber-500 resize-none"
                  placeholder="Descreva exatamente o que faltou, quantidade, impacto e providência necessária..."
                />
              </div>
            </>
          )}

          <div className="flex gap-3 justify-end">
            <button
              type="button"
              onClick={fecharModalFaltantes}
              className="px-5 py-2 border rounded-lg text-slate-700 font-semibold hover:bg-slate-50 transition-colors"
            >
              Cancelar
            </button>
            <button
              type="submit"
              className="px-5 py-2 bg-amber-600 hover:bg-amber-700 text-white font-semibold rounded-lg transition-colors"
            >
              Salvar Faltantes
            </button>
          </div>
        </form>
      </Modal>

      <Modal
        isOpen={showTimelineModal}
        onClose={fecharModalTimeline}
        title={`Timeline - ${timelineMaterialAtual?.nomeMaterial || ""}`}
      >
        <form onSubmit={handleSalvarTimeline} className="space-y-5">
          {[
            ["dataHoraSolicitacao", "Data/Hora de Solicitação"],
            ["dataHoraRetirada", "Data/Hora de Retirada"],
            ["dataHoraUso", "Data/Hora de Uso"],
          ].map(([campo, label]) => (
            <div key={campo}>
              <label className="block text-sm font-semibold text-slate-700 mb-2">
                {label}
              </label>
              <input
                type="datetime-local"
                value={timelineForm[campo]}
                onChange={(e) =>
                  setTimelineForm((prev) => ({
                    ...prev,
                    [campo]: e.target.value,
                  }))
                }
                className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          ))}

          <div className="flex gap-3 justify-end">
            <button
              type="button"
              onClick={fecharModalTimeline}
              className="px-5 py-2 border rounded-lg text-slate-700 font-semibold hover:bg-slate-50 transition-colors"
            >
              Cancelar
            </button>
            <button
              type="submit"
              className="px-5 py-2 bg-blue-600 hover:bg-blue-700 text-white font-semibold rounded-lg transition-colors"
            >
              Salvar Timeline
            </button>
          </div>
        </form>
      </Modal>

      <Modal
        isOpen={!!documentoVistoria && !!documentoVistoriaForm}
        onClose={fecharDocumentoVistoria}
        title={`${documentoVistoria?.tipo === DOCUMENTO_FINAL ? "Documento Final" : "Documento Inicial"} - ${documentoVistoria?.comarca?.nomeComarca || ""}`}
      >
        {documentoVistoriaForm && (
          <div className="max-h-[76vh] space-y-5 overflow-y-auto pr-1 text-sm">
            {documentoSimulacao && <div className="rounded border-2 border-red-600 bg-red-50 p-3 text-red-800">
              <strong>{MARCA_SIMULACAO}</strong>
              <p>{EMPRESA_FICTICIA}. Identidades, declarações, aceite e designação são fictícios. Cada papel registra uma marca TESTE. A impressão usa o PDF marcado do servidor.</p>
            </div>}

            <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
              <p className="text-center text-base font-black text-slate-800">
                ORDEM DE SERVIÇO (OS)
              </p>
              <p className="text-center text-xs font-bold uppercase text-slate-500">
                {documentoVistoria?.tipo === DOCUMENTO_FINAL
                  ? "Encerramento, aceite e conformidade técnica"
                  : "Abertura, serviços previstos e vistoria técnica inicial"}
              </p>
              {documentoVistoria.documentoSalvo?.status === "REGISTRADO" && (
                <div className="mt-3 rounded-lg border border-emerald-200 bg-emerald-50 p-2 text-xs font-bold text-emerald-800">
                  Documento totalmente assinado e registrado. Integridade:{" "}
                  <span className={documentoIntegridade?.integro ? "text-emerald-700" : "text-rose-700"}>
                    {documentoIntegridade?.integro ? "confirmada" : "aguardando verificação"}
                  </span>
                  <br />Hash:{" "}
                  <span className="font-mono">
                    {documentoVistoria.documentoSalvo.hashRegistro}
                  </span>
                </div>
              )}
              {documentoVistoria.documentoSalvo?.status === "PARCIALMENTE_ASSINADO" && (
                <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 p-2 text-xs font-bold text-amber-800">
                  Documento parcialmente assinado. Complete as assinaturas pendentes para registrar a versão final.
                </div>
              )}
              {documentoVistoria.documentoSalvo?.status === "INVALIDADO" && (
                <div className="mt-3 rounded-lg border border-red-200 bg-red-50 p-2 text-xs text-red-800">
                  <p className="font-black">Versão invalidada e preservada para auditoria.</p>
                  <p className="mt-1">
                    Motivo: {documentoVistoria.documentoSalvo.motivoInvalidacao || "Não informado"}
                  </p>
                </div>
              )}
              {documentoAssinaturasLog.length > 0 && (
                <div className="mt-3 space-y-1 rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs text-slate-600">
                  <p className="font-black uppercase tracking-wide text-slate-700">Log imutável de assinaturas</p>
                  {documentoAssinaturasLog.map((log) => (
                    <p key={log.id}>
                      <strong>{log.papel.replaceAll("_", " ")}</strong>: {log.nomeAssinante} em{" "}
                      {new Date(log.registradoEm).toLocaleString("pt-BR")} · hash {log.hashAssinatura.slice(0, 12)}...
                    </p>
                  ))}
                </div>
              )}
            </div>

            <div className="border-y border-slate-200 py-3">
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <History size={16} className="text-slate-500" />
                  <h3 className="text-xs font-black uppercase tracking-wide text-slate-600">
                    Histórico de documentos
                  </h3>
                </div>
                {podeEditarDocumento && ["PARCIALMENTE_ASSINADO", "REGISTRADO"].includes(
                  documentoVistoria.documentoSalvo?.status,
                ) && documentoVistoria.comarca?.situacao !== "CONCLUIDA" && (
                  <button
                    type="button"
                    onClick={criarNovaVersaoDocumento}
                    className="rounded-md border border-blue-200 bg-white px-2.5 py-1 text-xs font-bold text-blue-700 hover:bg-blue-50"
                  >
                    Corrigir versão
                  </button>
                )}
              </div>
              <div className="flex gap-2 overflow-x-auto pb-1">
                {documentosVistoriaHistorico.map((documento) => {
                  const selecionado = documentoVistoria.documentoSalvo?.id === documento.id;
                  const final = documento.tipo === DOCUMENTO_FINAL;
                  return (
                    <button
                      key={documento.id}
                      type="button"
                      onClick={() => abrirVersaoDocumento(documento)}
                      className={`min-w-52 rounded-md border px-3 py-2 text-left text-xs transition ${
                        selecionado
                          ? "border-blue-400 bg-blue-50 text-blue-900"
                          : "border-slate-200 bg-white text-slate-600 hover:border-blue-200"
                      }`}
                    >
                      <span className="block font-black">
                        {documento.simulacao ? "TESTE · " : ""}{final ? "Encerramento" : "Vistoria inicial"} #{documento.id}
                      </span>
                      <span className="mt-1 block">
                        {documento.dataGeracao
                          ? new Date(documento.dataGeracao).toLocaleString("pt-BR")
                          : "Data não informada"}
                      </span>
                      <span className="mt-1 block font-bold">
                        {documento.status === "REGISTRADO"
                          ? "Registrado"
                          : documento.status === "PARCIALMENTE_ASSINADO"
                            ? "Assinatura parcial"
                            : documento.status === "INVALIDADO"
                              ? "Invalidado"
                              : "Pendente"}
                      </span>
                    </button>
                  );
                })}
                {documentosVistoriaHistorico.length === 0 && (
                  <p className="py-2 text-xs text-slate-400">Nenhuma versão salva nesta obra.</p>
                )}
              </div>
            </div>

            <fieldset disabled={!podeEditarDocumento} className="space-y-5">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              {[
                ["contrato", "Contrato"],
                ["numeroOs", "Ordem de Serviço"],
                ["comarcaForum", "Comarca/Fórum"],
                ["endereco", "Endereço"],
                ["dataInicio", "Data de Início", "date"],
                ["dataConclusao", "Data de Conclusão", "date"],
                ["equipeResponsavel", "Equipe Responsável"],
                ["gestorRc", "Gestor RC Technology"],
                ["gerenteForum", "Gerente do Fórum"],
                ["recebidoPor", "Quem recebeu o acesso"],
                ["tecnicoResponsavel", "Técnico Responsável"],
                ["cpfTecnico", "CPF do Técnico"],
                ["gestorProjetoRc", "Gestor do Projeto RC"],
                ["cargoGerente", "Cargo do Gerente"],
                ["dataGerenteForum", "Data do aceite do Gerente", "date"],
                ["carimboGerente", "Carimbo do Gerente (se aplicável)"],
              ].map(([campo, label, type = "text"]) => (
                <label key={campo} className="block">
                  <span className="mb-1 block text-xs font-black uppercase tracking-wide text-slate-500">
                    {documentoSimulacao ? label.replace("RC Technology", "fictício TESTE").replace("Projeto RC", "Projeto fictício TESTE") : label}
                  </span>
                  <input
                    type={type}
                    readOnly={documentoSimulacao && (CAMPOS_IDENTIDADE_FICTICIA.includes(campo) || campo === "cpfTecnico")}
                    value={
                      campo === "cpfTecnico" && !documentoSimulacao
                        ? formatarCpf(documentoVistoriaForm[campo])
                        : documentoVistoriaForm[campo] || ""
                    }
                    onChange={(e) =>
                      atualizarDocumentoVistoria(
                        campo,
                        campo === "cpfTecnico" && !documentoSimulacao
                          ? formatarCpf(e.target.value)
                          : e.target.value,
                      )
                    }
                    inputMode={campo === "cpfTecnico" ? "numeric" : undefined}
                    maxLength={campo === "cpfTecnico" ? 14 : undefined}
                    placeholder={campo === "cpfTecnico" ? "000.000.000-00" : undefined}
                    className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </label>
              ))}
            </div>

            <div className="rounded-xl border border-slate-200 p-4">
              <h3 className="mb-3 text-xs font-black uppercase tracking-wide text-slate-600">
                1. Objeto da Ordem de Serviço
              </h3>
              <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
                {OBJETO_SERVICO_OPCOES.map((opcao) => (
                  <label key={opcao} className="flex items-start gap-2 text-xs font-semibold text-slate-700">
                    <input
                      type="checkbox"
                      checked={documentoVistoriaForm.objetoServicos.includes(opcao)}
                      onChange={() => alternarOpcaoDocumento("objetoServicos", opcao)}
                    />
                    {opcao}
                  </label>
                ))}
              </div>
              <input
                value={documentoVistoriaForm.outrosObjeto}
                onChange={(e) =>
                  atualizarDocumentoVistoria("outrosObjeto", e.target.value)
                }
                className="mt-3 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                placeholder="Outros serviços"
              />
              <textarea
                rows="3"
                value={documentoVistoriaForm.descricaoServicos}
                onChange={(e) =>
                  atualizarDocumentoVistoria("descricaoServicos", e.target.value)
                }
                className="mt-3 w-full resize-none rounded-lg border border-slate-300 px-3 py-2 text-sm"
                placeholder={
                  documentoVistoria?.tipo === DOCUMENTO_FINAL
                    ? "Descrição detalhada dos serviços executados"
                    : "Descrição detalhada dos serviços previstos"
                }
              />
            </div>

            <div className="rounded-xl border border-slate-200 p-4">
              <h3 className="mb-3 text-xs font-black uppercase tracking-wide text-slate-600">
                2. Registro de Condição Predial - Estado Inicial
              </h3>
              <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
                {ESTADO_INICIAL_OPCOES.map((opcao) => (
                  <label key={opcao} className="flex items-start gap-2 text-xs font-semibold text-slate-700">
                    <input
                      type="checkbox"
                      checked={documentoVistoriaForm.estadoInicial.includes(opcao)}
                      onChange={() => alternarOpcaoDocumento("estadoInicial", opcao)}
                    />
                    {opcao}
                  </label>
                ))}
              </div>
              <textarea
                rows="2"
                value={documentoVistoriaForm.anomaliasPreExistentes}
                onChange={(e) =>
                  atualizarDocumentoVistoria("anomaliasPreExistentes", e.target.value)
                }
                className="mt-3 w-full resize-none rounded-lg border border-slate-300 px-3 py-2 text-sm"
                placeholder="Anomalias pré-existentes identificadas"
              />
              <input
                value={documentoVistoriaForm.protocoloComunicacao}
                onChange={(e) =>
                  atualizarDocumentoVistoria("protocoloComunicacao", e.target.value)
                }
                className="mt-3 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                placeholder="Protocolo de comunicação, se aplicável"
              />
            </div>

            <div className="rounded-xl border border-slate-200 p-4">
              <h3 className="mb-3 text-xs font-black uppercase tracking-wide text-slate-600">
                3. Necessidade de Substituição do Rack da Comarca
              </h3>
              <p className="mb-3 text-xs text-slate-500">
                Após a realização da vistoria técnica, registre a necessidade e a disponibilidade do rack de substituição.
              </p>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <fieldset className="space-y-2">
                  <legend className="mb-2 text-xs font-bold text-slate-700">Substituição necessária</legend>
                  {RACK_NECESSIDADE_OPCOES.map(([valor, label]) => (
                    <label key={valor} className="flex items-start gap-2 text-xs font-semibold text-slate-700">
                      <input
                        type="radio"
                        name="rackNecessidade"
                        checked={documentoVistoriaForm.rackNecessidade === valor}
                        onChange={() => atualizarDocumentoVistoria("rackNecessidade", valor)}
                      />
                      {label}
                    </label>
                  ))}
                </fieldset>
                <fieldset className="space-y-2">
                  <legend className="mb-2 text-xs font-bold text-slate-700">Rack de substituição</legend>
                  {RACK_DISPONIBILIDADE_OPCOES.map(([valor, label]) => (
                    <label key={valor} className="flex items-start gap-2 text-xs font-semibold text-slate-700">
                      <input
                        type="radio"
                        name="rackDisponibilidade"
                        checked={documentoVistoriaForm.rackDisponibilidade === valor}
                        onChange={() => atualizarDocumentoVistoria("rackDisponibilidade", valor)}
                      />
                      {label}
                    </label>
                  ))}
                </fieldset>
              </div>
              <textarea
                rows="2"
                value={documentoVistoriaForm.rackObservacoes}
                onChange={(e) =>
                  atualizarDocumentoVistoria("rackObservacoes", e.target.value)
                }
                className="mt-3 w-full resize-none rounded-lg border border-slate-300 px-3 py-2 text-sm"
                placeholder="Observações/Justificativa, quando aplicável"
              />
              <input
                value={documentoVistoriaForm.rackLocalData}
                onChange={(e) =>
                  atualizarDocumentoVistoria("rackLocalData", e.target.value)
                }
                className="mt-3 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                placeholder="Local e data"
              />
            </div>

            <div className="rounded-xl border border-slate-200 p-4">
              <h3 className="mb-3 text-xs font-black uppercase tracking-wide text-slate-600">
                4. Declaração de Conformidade Técnica - Estado Final
              </h3>
              <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
                {ESTADO_FINAL_OPCOES.map((opcao) => (
                  <label key={opcao} className="flex items-start gap-2 text-xs font-semibold text-slate-700">
                    <input
                      type="checkbox"
                      checked={documentoVistoriaForm.estadoFinal.includes(opcao)}
                      onChange={() => alternarOpcaoDocumento("estadoFinal", opcao)}
                    />
                    {opcao}
                  </label>
                ))}
              </div>
              <textarea
                rows="3"
                value={documentoVistoriaForm.observacoesFinais}
                onChange={(e) =>
                  atualizarDocumentoVistoria("observacoesFinais", e.target.value)
                }
                className="mt-3 w-full resize-none rounded-lg border border-slate-300 px-3 py-2 text-sm"
                placeholder="Observações finais"
              />
              <textarea
                rows="2"
                value={documentoVistoriaForm.ressalvas}
                onChange={(e) =>
                  atualizarDocumentoVistoria("ressalvas", e.target.value)
                }
                className="mt-3 w-full resize-none rounded-lg border border-slate-300 px-3 py-2 text-sm"
                placeholder="Ressalvas, caso existam"
              />
            </div>

            <div className="rounded-xl border border-slate-200 p-4">
              <h3 className="mb-3 text-xs font-black uppercase tracking-wide text-slate-600">
                Responsável designado para acompanhamento
              </h3>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                <input
                  value={documentoVistoriaForm.responsavelDesignadoNome}
                  readOnly={documentoSimulacao}
                  onChange={(e) =>
                    atualizarDocumentoVistoria(
                      "responsavelDesignadoNome",
                      e.target.value,
                    )
                  }
                  className="rounded-lg border border-slate-300 px-3 py-2 text-sm"
                  placeholder="Nome"
                />
                <input
                  value={documentoVistoriaForm.responsavelDesignadoCargo}
                  readOnly={documentoSimulacao}
                  onChange={(e) =>
                    atualizarDocumentoVistoria(
                      "responsavelDesignadoCargo",
                      e.target.value,
                    )
                  }
                  className="rounded-lg border border-slate-300 px-3 py-2 text-sm"
                  placeholder="Cargo/Função"
                />
                <input
                  type="date"
                  value={documentoVistoriaForm.responsavelDesignadoData}
                  onChange={(e) =>
                    atualizarDocumentoVistoria(
                      "responsavelDesignadoData",
                      e.target.value,
                    )
                  }
                  className="rounded-lg border border-slate-300 px-3 py-2 text-sm"
                  aria-label="Data do responsável designado"
                />
                <input
                  value={documentoVistoriaForm.gerenteDesignanteNome}
                  readOnly={documentoSimulacao}
                  onChange={(e) =>
                    atualizarDocumentoVistoria(
                      "gerenteDesignanteNome",
                      e.target.value,
                    )
                  }
                  className="rounded-lg border border-slate-300 px-3 py-2 text-sm"
                  placeholder="Gerente que realizou a designação"
                />
                <input
                  type="date"
                  value={documentoVistoriaForm.declaracaoDesignacaoData}
                  onChange={(e) =>
                    atualizarDocumentoVistoria(
                      "declaracaoDesignacaoData",
                      e.target.value,
                    )
                  }
                  className="rounded-lg border border-slate-300 px-3 py-2 text-sm"
                  aria-label="Data da declaração de designação"
                />
              </div>
              <p className="mt-3 text-xs text-slate-500">
                {documentoSimulacao ? "Designação fictícia para TESTE, sem representação ou autorização institucional." : "A declaração de designação segue o texto institucional fixo do documento."}
              </p>
            </div>

            <div className="rounded-xl border border-slate-200 p-4">
              <h3 className="mb-3 text-xs font-black uppercase tracking-wide text-slate-600">
                Assinaturas digitais do documento
              </h3>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                {ASSINATURAS_DOCUMENTO.map((assinatura) => {
                  const documento = documentoVistoria.documentoSalvo;
                  const imagem = documento?.[assinatura.campoAssinatura];
                  return (
                    <div key={assinatura.papel} className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                      <p className="text-xs font-black text-slate-700">{documentoSimulacao ? assinatura.label.replace(/projeto RC/i, "projeto fictício") + " — TESTE" : assinatura.label}</p>
                      {imagem ? (
                        <>
                          <img
                            src={imagem}
                            alt={`Assinatura de ${assinatura.label}`}
                            className="mt-2 h-14 w-full rounded border border-emerald-200 bg-white object-contain"
                          />
                          <p className="mt-1 truncate text-[10px] font-bold text-emerald-700">
                            {documento[assinatura.campoNome] || "Assinado"}
                          </p>
                          <p className="text-[10px] text-slate-400">
                            {documento[assinatura.campoData]
                              ? new Date(documento[assinatura.campoData]).toLocaleString("pt-BR")
                              : ""}
                          </p>
                        </>
                      ) : (
                        <p className="my-3 text-xs text-amber-700">Assinatura pendente</p>
                      )}
                      {podeEditarDocumento && <button
                        type="button"
                        disabled={documentoSimulacao && (!!imagem || documentoVistoria.documentoSalvo?.status === "INVALIDADO")}
                        onClick={() => assinarDocumentoVistoria(assinatura.papel)}
                        className="mt-2 inline-flex w-full items-center justify-center gap-1 rounded-md bg-emerald-600 px-2 py-1.5 text-xs font-bold text-white hover:bg-emerald-700"
                      >
                        <ShieldCheck size={13} />
                        {imagem ? documentoSimulacao ? "Assinado" : "Substituir" : documentoSimulacao ? "Assinar TESTE" : "Assinar"}
                      </button>}
                    </div>
                  );
                })}
              </div>
            </div>
            </fieldset>

            <div className="sticky bottom-0 flex flex-col gap-2 border-t border-slate-200 bg-white pt-3 sm:flex-row sm:justify-end">
              <div className="mr-auto self-center text-xs font-semibold text-slate-500">
                {documentoMensagem || (documentoVistoria.documentoSalvo ? "Versão salva." : "Documento ainda não salvo.")}
              </div>
              {podeEditarDocumento && <button
                type="button"
                onClick={handleSalvarDocumentoVistoria}
                disabled={salvandoDocumento || ["PARCIALMENTE_ASSINADO", "REGISTRADO", "INVALIDADO"].includes(documentoVistoria.documentoSalvo?.status)}
                className="inline-flex items-center justify-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-bold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                <Save size={16} />
                {salvandoDocumento ? "Salvando..." : "Salvar documento"}
              </button>}
              {podeEditarDocumento && <button
                type="button"
                onClick={abrirPdfServidor}
                title="Salva o conteúdo atual e abre o PDF gerado pelo backend. Após todas as assinaturas, essa versão fica arquivada e protegida por hash."
                className="inline-flex items-center justify-center gap-2 rounded-lg border border-blue-200 bg-blue-50 px-4 py-2 text-sm font-bold text-blue-700 hover:bg-blue-100"
              >
                <Printer size={16} />
                {documentoSimulacao ? "Salvar e abrir PDF de TESTE" : "Salvar e abrir PDF oficial"}
              </button>}
              <button
                type="button"
                onClick={imprimirDocumentoVistoria}
                className="inline-flex items-center justify-center gap-2 rounded-lg border border-slate-300 px-4 py-2 text-sm font-bold text-slate-700 hover:bg-slate-50"
              >
                <Printer size={16} />
                {documentoSimulacao ? "Pré-visualizar PDF de TESTE" : "Pré-visualizar e imprimir"}
              </button>
            </div>
          </div>
        )}
      </Modal>

      <Modal
        isOpen={showAssinaturaModal}
        onClose={fecharModalAssinatura}
        title={`${documentoAssinaturaAtual ? ASSINATURAS_DOCUMENTO.find((item) => item.papel === papelAssinaturaAtual)?.label || "Assinar Documento" : "Coletar Assinatura"} - ${comarcaAssinaturaAtual?.nomeComarca || ""}`}
      >
        <div className="space-y-4">
          {(documentoAssinaturaAtual?.simulacao || comarcaAssinaturaAtual?.ordemServico?.simulacao) &&
            <p className="rounded border border-red-300 bg-red-50 p-3 font-bold text-red-800">
              {MARCA_SIMULACAO}. Desenhe a palavra TESTE no campo abaixo. Nunca use assinatura ou identidade real.
            </p>}
          {documentoAssinaturaAtual && (
            <label className="block">
              <span className="mb-1 block text-xs font-black uppercase tracking-wide text-slate-500">
                Nome de quem está assinando
              </span>
              <input
                value={nomeAssinanteAtual}
                readOnly={documentoAssinaturaAtual?.simulacao}
                onChange={(e) => setNomeAssinanteAtual(e.target.value)}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
                placeholder="Nome completo"
              />
            </label>
          )}
          <canvas
            ref={assinaturaCanvasRef}
            className="w-full rounded-lg border border-slate-300 bg-white touch-none"
            onMouseDown={iniciarAssinatura}
            onMouseMove={desenharAssinatura}
            onMouseUp={finalizarAssinatura}
            onMouseLeave={finalizarAssinatura}
            onTouchStart={iniciarAssinatura}
            onTouchMove={desenharAssinatura}
            onTouchEnd={finalizarAssinatura}
          />

          <div className="flex flex-col sm:flex-row justify-end gap-3">
            <button
              type="button"
              onClick={limparAssinatura}
              className="px-5 py-2 border rounded-lg text-slate-700 font-semibold hover:bg-slate-50 transition-colors"
            >
              Limpar
            </button>
            <button
              type="button"
              onClick={confirmarAssinatura}
              disabled={!assinaturaEmEdicao || (documentoAssinaturaAtual && !nomeAssinanteAtual.trim())}
              className="px-5 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:bg-slate-300 disabled:cursor-not-allowed text-white font-semibold rounded-lg transition-colors"
            >
              Confirmar
            </button>
          </div>
        </div>
      </Modal>

      <Modal
        isOpen={!!documentoVisualizacao}
        onClose={() => setDocumentoVisualizacao(null)}
        title={
          documentoVisualizacao?.tipo === "retirada"
            ? "Ordem de Retirada"
            : "Ordem de Serviço"
        }
      >
        {documentoVisualizacao &&
          (() => {
            const comarca = documentoVisualizacao.comarca;
            const os = comarca.ordemServico || {};
            const materiais = Array.isArray(comarca.materiais)
              ? comarca.materiais
              : [];
            const orsDaComarca = ordensRetirada.filter(
              (or) =>
                or.comarca?.id === comarca.id ||
                or.ordemServico?.id === os.id,
            );

            if (documentoVisualizacao.tipo === "retirada") {
              return (
                <div className="space-y-4 text-sm">
                  <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                    <p className="text-xs font-black uppercase tracking-wide text-slate-500">
                      OS / OR
                    </p>
                    <p className="font-bold text-slate-800">
                      {os.numeroOs || `OS-2026-0${comarca.id}`}
                    </p>
                    <p className="text-slate-500">{comarca.nomeComarca}</p>
                  </div>
                  {orsDaComarca.map((or) => (
                    <div key={or.id} className="rounded-lg border border-slate-200">
                      <div className="flex flex-col gap-2 border-b border-slate-100 bg-slate-50 px-3 py-2 sm:flex-row sm:items-center sm:justify-between">
                        <div>
                          <p className="font-black text-slate-800">{or.numeroOr}</p>
                          <p className="text-xs text-slate-500">
                            Gerada: {formatarDataHora(or.dataGeracao)} | Status: {or.status}
                          </p>
                        </div>
                        <div className="flex items-center gap-3">
                          <div className="text-xs text-slate-500">
                            {or.levadoPor && <p>Levou: {or.levadoPor}</p>}
                            {or.devolvidoPor && <p>Devolveu: {or.devolvidoPor}</p>}
                          </div>
                          <button
                            type="button"
                            title={`Abrir PDF de ${or.numeroOr}`}
                            onClick={() =>
                              window.open(
                                `${API_BASE_URL}/ordens-retirada/${or.id}/pdf`,
                                "_blank",
                                "noopener,noreferrer",
                              )
                            }
                            className="inline-flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-md border border-blue-200 bg-white text-blue-700 transition-colors hover:bg-blue-50"
                            aria-label={`Abrir PDF de ${or.numeroOr}`}
                          >
                            <Printer size={16} />
                          </button>
                        </div>
                      </div>
                      <div className="overflow-x-auto">
                        <table className="min-w-full text-left text-xs">
                          <thead className="bg-white text-[10px] font-black uppercase tracking-wide text-slate-400">
                            <tr>
                              <th className="px-3 py-2">Material</th>
                              <th className="px-3 py-2">Solic.</th>
                              <th className="px-3 py-2">Ret.</th>
                              <th className="px-3 py-2">Dev.</th>
                              <th className="px-3 py-2">Categoria</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-slate-100">
                            {(or.itens || []).map((item) => (
                              <tr key={item.id}>
                                <td className="px-3 py-2 font-semibold text-slate-700">
                                  {item.nomeMaterial}
                                </td>
                                <td className="px-3 py-2">{item.quantidadeSolicitada}</td>
                                <td className="px-3 py-2">{item.quantidadeRetirada}</td>
                                <td className="px-3 py-2">{item.quantidadeDevolvida}</td>
                                <td className="px-3 py-2">{getCategoriaMaterialLabel(item.categoria)}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  ))}
                  {orsDaComarca.length === 0 && (
                    <p className="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-4 text-center text-xs text-slate-400">
                      Nenhuma OR encontrada para esta OS.
                    </p>
                  )}
                  <div className="overflow-x-auto rounded-lg border border-slate-200">
                    <table className="min-w-full text-left text-xs">
                      <thead className="bg-slate-50 text-[10px] font-black uppercase tracking-wide text-slate-500">
                        <tr>
                          <th className="px-3 py-2">Material</th>
                          <th className="px-3 py-2">Qtd.</th>
                          <th className="px-3 py-2">Retirada</th>
                          <th className="px-3 py-2">Uso</th>
                          <th className="px-3 py-2">Status</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100">
                        {materiais.map((material) => (
                          <tr key={material.id || material.nomeMaterial}>
                            <td className="px-3 py-2 font-semibold text-slate-700">
                              {material.nomeMaterial}
                            </td>
                            <td className="px-3 py-2">
                              {material.quantidadePrevista}
                            </td>
                            <td className="px-3 py-2">
                              {formatarDataHora(material.dataHoraRetirada)}
                            </td>
                            <td className="px-3 py-2">
                              {formatarDataHora(material.dataHoraUso)}
                            </td>
                            <td className="px-3 py-2">
                              {material.estoqueBaixado
                                ? "Baixado"
                                : material.estoqueReservado
                                  ? "Reservado"
                                  : "Pendente"}
                            </td>
                          </tr>
                        ))}
                        {materiais.length === 0 && (
                          <tr>
                            <td className="px-3 py-6 text-center text-slate-400" colSpan="5">
                              Nenhum material previsto nesta OS.
                            </td>
                          </tr>
                        )}
                      </tbody>
                    </table>
                  </div>
                </div>
              );
            }

            return (
              <div className="space-y-4 text-sm text-slate-700">
                <div className="rounded-lg border border-blue-100 bg-blue-50 p-4">
                  <p className="text-xs font-black uppercase tracking-wide text-blue-500">
                    Identificador
                  </p>
                  <p className="text-lg font-black text-blue-900">
                    {os.numeroOs || `OS-2026-0${comarca.id}`}
                  </p>
                  <p className="font-semibold">{comarca.nomeComarca}</p>
                </div>
                <div className="grid gap-3 md:grid-cols-3">
                  <div className="rounded-lg border border-slate-200 p-3">
                    <p className="text-[10px] font-black uppercase text-slate-400">
                      Início
                    </p>
                    <p>{formatarDataHora(os.dataHoraInicio)}</p>
                  </div>
                  <div className="rounded-lg border border-slate-200 p-3">
                    <p className="text-[10px] font-black uppercase text-slate-400">
                      Fim
                    </p>
                    <p>{formatarDataHora(os.dataHoraFim)}</p>
                  </div>
                  <div className="rounded-lg border border-slate-200 p-3">
                    <p className="text-[10px] font-black uppercase text-slate-400">
                      Deadline
                    </p>
                    <p>{formatarDataHora(os.deadline)}</p>
                  </div>
                </div>
                <div className="rounded-lg border border-slate-200 p-3">
                  <p className="text-[10px] font-black uppercase text-slate-400">
                    Descrição
                  </p>
                  <p className="whitespace-pre-line">
                    {os.descricao || "Sem descrição informada."}
                  </p>
                </div>
              </div>
            );
          })()}
      </Modal>

      {/* Modal Histórico */}
      <Modal
        isOpen={!!comarcaHistorico}
        onClose={() => setComarcaHistorico(null)}
        title={`Atividades - ${comarcaHistorico?.nomeComarca || ""}`}
      >
        {comarcaHistorico && (
          <HistoricoAtividadesComarca comarcaId={comarcaHistorico.id} />
        )}
      </Modal>

      <Modal
        isOpen={!!encerramentoResumo}
        onClose={() => setEncerramentoResumo(null)}
        title="Obra concluída"
      >
        {encerramentoResumo && (
          <div className="space-y-4 text-sm text-slate-700">
            <div className="flex items-center gap-3 rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-emerald-800">
              <CheckCircle2 size={28} />
              <div>
                <p className="font-black">{encerramentoResumo.nomeObra}</p>
                <p>O ciclo operacional foi encerrado com sucesso.</p>
              </div>
            </div>
            <dl className="grid gap-3 sm:grid-cols-2">
              <div className="rounded-lg border border-slate-200 p-3">
                <dt className="text-xs font-bold uppercase text-slate-400">Ordem de Serviço</dt>
                <dd className="font-semibold">{encerramentoResumo.numeroOs}</dd>
              </div>
              <div className="rounded-lg border border-slate-200 p-3">
                <dt className="text-xs font-bold uppercase text-slate-400">As-Built</dt>
                <dd className="font-semibold">
                  {encerramentoResumo.statusAsBuilt === "HOMOLOGADO_COM_DIVERGENCIA"
                    ? "Homologado com divergência"
                    : "Homologado"}
                </dd>
              </div>
              <div className="rounded-lg border border-slate-200 p-3">
                <dt className="text-xs font-bold uppercase text-slate-400">Ordens devolvidas</dt>
                <dd className="font-semibold">{encerramentoResumo.ordensRetiradaDevolvidas}</dd>
              </div>
              <div className="rounded-lg border border-slate-200 p-3">
                <dt className="text-xs font-bold uppercase text-slate-400">Concluída por</dt>
                <dd className="font-semibold">{encerramentoResumo.concluidaPor}</dd>
                <dd className="mt-1 text-xs text-slate-500">{formatarDataHora(encerramentoResumo.concluidaEm)}</dd>
              </div>
            </dl>
            {encerramentoResumo.documentoFinalPdfPath && (
              <button
                type="button"
                onClick={() => window.open(buildApiFileUrl(encerramentoResumo.documentoFinalPdfPath), "_blank", "noopener,noreferrer")}
                className="flex w-full items-center justify-center gap-2 rounded-lg border border-blue-200 px-4 py-2.5 font-bold text-blue-700 hover:bg-blue-50"
              >
                <FileText size={16} /> Abrir documento final arquivado
              </button>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}
