import { Fragment, useState, useEffect, useMemo, useRef } from "react";
import {
  Plus,
  Minus,
  Package,
  AlertCircle,
  History,
  ArrowUpRight,
  ArrowDownLeft,
  FolderPlus,
  Search,
  Edit2,
  SlidersHorizontal,
  ArrowRightLeft,
  Camera,
  Download,
  Eye,
  FileClock,
  Upload,
  Receipt,
  Trash2,
  Undo2,
  Calculator,
} from "lucide-react";
import api, { getApiErrorMessage } from "../services/api";
import Modal from "../components/Modal";
import LoadingSpinner from "../components/LoadingSpinner";
import Alert from "../components/Alert";
import FilaPendenciasOperacionais from "../components/FilaPendenciasOperacionais";
import { useAuth } from "../contexts/AuthContext";
import {
  arredondarQuantidadeEstoque,
  celulaPossuiFormulaSemResultado,
  custoPlanilhaParaEstoque,
  ehCaboEmBobina305,
  extrairAtualizacaoCustos,
  extrairSincronizacaoSaldos,
  extrairOrdensRetiradaAvulsas,
  interpretarSaldoPlanilha,
  localizarCabecalhoEstoque,
  normalizarTextoPlanilha,
  quantidadePlanilhaParaEstoque,
  resolverSaldoBaseCadastro,
  resumirAvisosImportacao,
  saldoCadastroIncluiRetornos,
  valorDaCelula,
} from "../utils/planilhaEstoque";
import {
  calcularSimulacaoRetirada,
  consolidarRetiradasPorObra,
} from "../utils/estoqueOperacional";

const CATEGORIAS_MATERIAL = [
  { value: "MATERIAL_CONSUMO", label: "Materiais de Consumo" },
  { value: "FERRAMENTA", label: "Ferramentas" },
];

const TIPOS_CONTROLE = [
  { value: "UNIDADE", label: "Por unidade" },
  { value: "FRACIONADO", label: "Quantidade fracionada" },
  { value: "PECA_COM_COMPRIMENTO", label: "Peça com comprimento" },
  { value: "METRAGEM", label: "Por metragem" },
  { value: "ROLO", label: "Por rolo" },
  { value: "BOBINA", label: "Por bobina" },
];

const UNIDADES_MEDIDA = [
  { value: "UNIDADE", label: "un" },
  { value: "PECA", label: "peça" },
  { value: "METRO", label: "m" },
  { value: "ROLO", label: "rolo" },
  { value: "PACOTE", label: "pacote" },
  { value: "BOBINA", label: "bobina" },
];

const unidadePadraoPorControle = {
  UNIDADE: "UNIDADE",
  FRACIONADO: "UNIDADE",
  PECA_COM_COMPRIMENTO: "PECA",
  METRAGEM: "METRO",
  ROLO: "ROLO",
  BOBINA: "BOBINA",
};

const dataPlanilhaParaIso = (valor) => {
  if (valor instanceof Date && !Number.isNaN(valor.getTime())) {
    return valor.toISOString().slice(0, 10);
  }
  if (typeof valor === "number" && Number.isFinite(valor)) {
    const data = new Date(Date.UTC(1899, 11, 30) + valor * 86400000);
    return data.toISOString().slice(0, 10);
  }
  if (typeof valor === "string" && valor.trim()) {
    const iso = valor.trim().match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (iso) return `${iso[1]}-${iso[2]}-${iso[3]}`;
    const partes = valor.trim().split(/[/-]/);
    if (partes.length === 3) {
      const [dia, mes, ano] = partes;
      if (ano?.length === 4) return `${ano}-${mes.padStart(2, "0")}-${dia.padStart(2, "0")}`;
    }
  }
  return null;
};

const lerImagemComoDataUrl = (arquivo) =>
  new Promise((resolve, reject) => {
    if (!arquivo) {
      resolve({ base64: "", nome: "" });
      return;
    }
    if (!["image/jpeg", "image/png"].includes(arquivo.type)) {
      reject(new Error("Envie uma foto JPG ou PNG."));
      return;
    }
    if (arquivo.size > 10 * 1024 * 1024) {
      reject(new Error("A foto deve ter no máximo 10 MB."));
      return;
    }
    const reader = new FileReader();
    reader.onload = () => resolve({ base64: reader.result, nome: arquivo.name });
    reader.onerror = () => reject(new Error("Não foi possível ler a foto selecionada."));
    reader.readAsDataURL(arquivo);
  });

const novaLinhaSimulacao = () => ({
  id: `${Date.now()}-${Math.random()}`,
  materialId: "",
  quantidade: "",
});

function SignatureBox({ label, value, onChange }) {
  const canvasRef = useRef(null);
  const drawingRef = useRef(false);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const ratio = window.devicePixelRatio || 1;
    canvas.width = rect.width * ratio;
    canvas.height = rect.height * ratio;
    const context = canvas.getContext("2d");
    context.scale(ratio, ratio);
    context.lineWidth = 2;
    context.lineCap = "round";
    context.lineJoin = "round";
    context.strokeStyle = "#0f172a";
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, rect.width, rect.height);

    if (value?.startsWith("data:image")) {
      const image = new Image();
      image.onload = () => {
        context.drawImage(image, 0, 0, rect.width, rect.height);
      };
      image.src = value;
    }
  }, [value]);

  const getPoint = (event) => {
    const canvas = canvasRef.current;
    const rect = canvas.getBoundingClientRect();
    const sourceEvent = event.touches?.[0] || event;
    return {
      x: sourceEvent.clientX - rect.left,
      y: sourceEvent.clientY - rect.top,
    };
  };

  const startDrawing = (event) => {
    event.preventDefault();
    const canvas = canvasRef.current;
    const context = canvas.getContext("2d");
    const point = getPoint(event);
    drawingRef.current = true;
    context.beginPath();
    context.moveTo(point.x, point.y);
  };

  const draw = (event) => {
    if (!drawingRef.current) return;
    event.preventDefault();
    const context = canvasRef.current.getContext("2d");
    const point = getPoint(event);
    context.lineTo(point.x, point.y);
    context.stroke();
  };

  const stopDrawing = () => {
    if (!drawingRef.current) return;
    drawingRef.current = false;
    onChange(canvasRef.current.toDataURL("image/png"));
  };

  const clear = () => {
    const canvas = canvasRef.current;
    const context = canvas.getContext("2d");
    const rect = canvas.getBoundingClientRect();
    context.clearRect(0, 0, rect.width, rect.height);
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, rect.width, rect.height);
    onChange("");
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <label className="block text-sm font-semibold text-slate-700">
          {label}
        </label>
        <button
          type="button"
          onClick={clear}
          className="rounded border border-slate-200 px-2 py-1 text-xs font-bold text-slate-500 hover:bg-slate-50"
        >
          Limpar
        </button>
      </div>
      <canvas
        ref={canvasRef}
        className="h-32 w-full rounded-lg border border-slate-300 bg-white touch-none"
        onMouseDown={startDrawing}
        onMouseMove={draw}
        onMouseUp={stopDrawing}
        onMouseLeave={stopDrawing}
        onTouchStart={startDrawing}
        onTouchMove={draw}
        onTouchEnd={stopDrawing}
      />
      {!value && (
        <p className="text-xs text-slate-400">
          Assine no campo acima com mouse ou toque.
        </p>
      )}
    </div>
  );
}

export default function PainelEstoque() {
  const { usuario } = useAuth();
  const podeGerenciarEstoque = ["ADMIN", "ESTOQUE"].includes(usuario?.perfil);
  const [materiais, setMateriais] = useState([]);
  const [materiaisRemovidos, setMateriaisRemovidos] = useState([]);
  const [funcionarios, setFuncionarios] = useState([]);
  const [contratos, setContratos] = useState([]);
  const [comarcas, setComarcas] = useState([]);
  const [historico, setHistorico] = useState([]);
  const [ordensRetirada, setOrdensRetirada] = useState([]);
  const [unidadesRastreaveis, setUnidadesRastreaveis] = useState([]);
  const [locaisEstoque, setLocaisEstoque] = useState([]);
  const [saldosLocais, setSaldosLocais] = useState([]);
  const [importacoesPlanilha, setImportacoesPlanilha] = useState([]);
  const [importacoesNotaFiscal, setImportacoesNotaFiscal] = useState([]);
  const [retiradasImportadas, setRetiradasImportadas] = useState([]);
  const [reconciliacoesRetiradas, setReconciliacoesRetiradas] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [successMessage, setSuccessMessage] = useState(null);
  const [historicoFiltro, setHistoricoFiltro] = useState("");
  const [historicoTipoFiltro, setHistoricoTipoFiltro] = useState("");
  const [historicoComarcaFiltro, setHistoricoComarcaFiltro] = useState("");
  const [historicoProjetoFiltro, setHistoricoProjetoFiltro] = useState("");
  const [historicoMaterialFiltro, setHistoricoMaterialFiltro] = useState("");
  const [historicoDataInicio, setHistoricoDataInicio] = useState("");
  const [historicoDataFim, setHistoricoDataFim] = useState("");
  const [historicoPessoaFiltro, setHistoricoPessoaFiltro] = useState("");
  const [estoqueBusca, setEstoqueBusca] = useState("");
  const [estoqueCategoriaFiltro, setEstoqueCategoriaFiltro] = useState("");
  const [estoqueSituacaoFiltro, setEstoqueSituacaoFiltro] = useState("");
  const [estoqueDisponivelMinimo, setEstoqueDisponivelMinimo] = useState("");
  const [estoqueDisponivelMaximo, setEstoqueDisponivelMaximo] = useState("");
  const [estoqueValorMinimo, setEstoqueValorMinimo] = useState("");
  const [estoqueValorMaximo, setEstoqueValorMaximo] = useState("");
  const [estoqueFaltaMinima, setEstoqueFaltaMinima] = useState("");
  const [estoqueOrdenacao, setEstoqueOrdenacao] = useState("NOME_ASC");

  // Modais de controle
  const [showEntradaModal, setShowEntradaModal] = useState(false);
  const [showSaidaModal, setShowSaidaModal] = useState(false);
  const [showNovoMaterialModal, setShowNovoMaterialModal] = useState(false); //   NOVO MODAL
  const [showUnidadeRastreavelModal, setShowUnidadeRastreavelModal] = useState(false);
  const [showAjusteModal, setShowAjusteModal] = useState(false);
  const [showTransferenciaModal, setShowTransferenciaModal] = useState(false);
  const [showLocalEstoqueModal, setShowLocalEstoqueModal] = useState(false);
  const [showTransferenciaUnidadeModal, setShowTransferenciaUnidadeModal] = useState(false);
  const [showMinimoLocalModal, setShowMinimoLocalModal] = useState(false);
  const [showHistoricoImportacoesModal, setShowHistoricoImportacoesModal] = useState(false);
  const [showSimulacaoModal, setShowSimulacaoModal] = useState(false);
  const [importacaoDetalhe, setImportacaoDetalhe] = useState(null);
  const [retiradaHistoricaEdicao, setRetiradaHistoricaEdicao] = useState(null);
  const [retiradaHistoricaSalvando, setRetiradaHistoricaSalvando] = useState(false);
  const [reconciliacaoPreview, setReconciliacaoPreview] = useState(null);
  const [reconciliacaoProcessando, setReconciliacaoProcessando] = useState(false);
  const [notaFiscalDetalhe, setNotaFiscalDetalhe] = useState(null);
  const [abaHistoricoImportacoes, setAbaHistoricoImportacoes] = useState("notas-fiscais");
  const [retiradaHistoricaBusca, setRetiradaHistoricaBusca] = useState("");
  const [retiradaHistoricaSituacao, setRetiradaHistoricaSituacao] = useState("");
  const [fotoExpandida, setFotoExpandida] = useState(null);
  const [materialEmEdicao, setMaterialEmEdicao] = useState(null);
  const [ordemRetiradaAtual, setOrdemRetiradaAtual] = useState(null);
  const [acaoOr, setAcaoOr] = useState("retirada");

  // Formulários
  const [formData, setFormData] = useState({
    materialId: "",
    quantidade: "",
    custoUnitarioEntrada: "",
    funcionarioId: "",
    comarcaId: "",
    localEstoqueId: "",
  });

  const [novoMaterialData, setNovoMaterialData] = useState({
    //   NOVO FORMULÁRIO
    nome: "",
    partNumber: "",
    categoria: "MATERIAL_CONSUMO",
    descricao: "",
    fotoProdutoUrl: "",
    fabricante: "",
    fornecedor: "",
    localizacao: "",
    quantidadeDisponivel: "0",
    tipoControle: "UNIDADE",
    unidadeMedida: "UNIDADE",
    dimensao: "",
    comprimentoPorPeca: "",
    metragemDisponivel: "0",
    estoqueMinimo: "0",
    custoMedio: "0",
  });
  const [orForm, setOrForm] = useState({
    conferidoPor: "",
    levadoPor: "",
    assinaturaConferente: "",
    assinaturaRetirante: "",
    devolvidoPor: "",
    recebidoPor: "",
    assinaturaRecebimento: "",
    devolucoes: {},
    alocacoes: {},
    devolucoesAlocacao: {},
    evidenciasDevolucao: {},
  });
  const [unidadeForm, setUnidadeForm] = useState({
    materialId: "",
    codigo: "",
    metragemInicial: "",
    observacao: "",
    localEstoqueId: "",
  });
  const [materialOperacao, setMaterialOperacao] = useState(null);
  const [ajusteForm, setAjusteForm] = useState({
    tipo: "AJUSTE_POSITIVO",
    localEstoqueId: "",
    valor: "",
    motivo: "",
    lancadoPor: "",
    autorizadoPor: "",
  });
  const [transferenciaForm, setTransferenciaForm] = useState({
    origemId: "",
    destinoId: "",
    valor: "",
    motivo: "",
    lancadoPor: "",
    autorizadoPor: "",
  });
  const [localForm, setLocalForm] = useState({ nome: "", endereco: "" });
  const [unidadeOperacao, setUnidadeOperacao] = useState(null);
  const [transferenciaUnidadeForm, setTransferenciaUnidadeForm] = useState({
    destinoId: "",
    motivo: "",
    lancadoPor: "",
    autorizadoPor: "",
  });
  const [minimoLocalForm, setMinimoLocalForm] = useState({ saldoId: "", estoqueMinimo: "" });
  const [abaEstoque, setAbaEstoque] = useState("geral");
  const [abaOrdemEstoque, setAbaOrdemEstoque] = useState("resumo");
  const [simulacaoItens, setSimulacaoItens] = useState([novaLinhaSimulacao()]);
  const [importacaoPreview, setImportacaoPreview] = useState(null);
  const [importacaoLocalId, setImportacaoLocalId] = useState("");
  const [importacaoContratoId, setImportacaoContratoId] = useState("");
  const [importacaoResponsavelId, setImportacaoResponsavelId] = useState("");
  const [importacaoProcessando, setImportacaoProcessando] = useState(false);
  const importacaoInputRef = useRef(null);
  const sincronizacaoSaldosInputRef = useRef(null);
  const [notaFiscalPreview, setNotaFiscalPreview] = useState(null);
  const [notaFiscalLocalId, setNotaFiscalLocalId] = useState("");
  const [notaFiscalProcessando, setNotaFiscalProcessando] = useState(false);
  const notaFiscalInputRef = useRef(null);

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    try {
      setLoading(true);
      const [materiaisResponse, removidosResponse] = await Promise.all([
        api.get("/estoque/materiais"),
        podeGerenciarEstoque
          ? api.get("/estoque/materiais/removidos")
          : Promise.resolve({ data: [] }),
      ]);
      setMateriais(materiaisResponse.data);
      setMateriaisRemovidos(removidosResponse.data || []);

      const [funcionariosResponse, contratosResponse] = await Promise.all([
        api.get("/funcionarios"),
        api.get(usuario?.perfil === "ESTOQUE" ? "/contratos/opcoes-estoque" : "/contratos"),
      ]);
      setFuncionarios(funcionariosResponse.data);
      setContratos(contratosResponse.data || []);

      const comarcasResponse = await api.get("/comarcas");
      setComarcas(comarcasResponse.data);

      const historicoResponse = await api.get("/estoque/historico");
      setHistorico(historicoResponse.data);

      const ordensRetiradaResponse = await api.get("/ordens-retirada");
      setOrdensRetirada(ordensRetiradaResponse.data || []);

      const unidadesResponse = await api.get("/estoque/unidades-rastreaveis");
      setUnidadesRastreaveis(unidadesResponse.data || []);

      const [locaisResponse, saldosResponse] = await Promise.all([
        api.get("/estoque/locais"),
        api.get("/estoque/saldos-locais"),
      ]);
      setLocaisEstoque(locaisResponse.data || []);
      setSaldosLocais(saldosResponse.data || []);

      const [
        importacoesResponse,
        retiradasImportadasResponse,
        notasFiscaisResponse,
        reconciliacoesResponse,
      ] = await Promise.all([
        api.get("/estoque/importacoes/planilha"),
        api.get("/estoque/importacoes/planilha/retiradas"),
        api.get("/estoque/importacoes/notas-fiscais"),
        api.get("/estoque/importacoes/planilha/retiradas/reconciliacoes"),
      ]);
      setImportacoesPlanilha(importacoesResponse.data || []);
      setRetiradasImportadas(retiradasImportadasResponse.data || []);
      setImportacoesNotaFiscal(notasFiscaisResponse.data || []);
      setReconciliacoesRetiradas(reconciliacoesResponse.data || []);

      setError(null);
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao carregar dados do estoque."));
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const handleCloseModal = () => {
    setShowEntradaModal(false);
    setShowSaidaModal(false);
    setShowNovoMaterialModal(false);
    setShowUnidadeRastreavelModal(false);
    setShowAjusteModal(false);
    setShowTransferenciaModal(false);
    setShowLocalEstoqueModal(false);
    setShowTransferenciaUnidadeModal(false);
    setShowMinimoLocalModal(false);
    setShowHistoricoImportacoesModal(false);
    setShowSimulacaoModal(false);
    setImportacaoDetalhe(null);
    setRetiradaHistoricaEdicao(null);
    setRetiradaHistoricaSalvando(false);
    setReconciliacaoPreview(null);
    setNotaFiscalDetalhe(null);
    setImportacaoPreview(null);
    setImportacaoLocalId("");
    setImportacaoProcessando(false);
    setNotaFiscalPreview(null);
    setNotaFiscalLocalId("");
    setNotaFiscalProcessando(false);
    setMaterialEmEdicao(null);
    setOrdemRetiradaAtual(null);
    setFormData({ materialId: "", quantidade: "", custoUnitarioEntrada: "", funcionarioId: "", comarcaId: "", localEstoqueId: "" });
    setOrForm({
      conferidoPor: "",
      levadoPor: "",
      assinaturaConferente: "",
      assinaturaRetirante: "",
      devolvidoPor: "",
      recebidoPor: "",
      assinaturaRecebimento: "",
      devolucoes: {},
      alocacoes: {},
      devolucoesAlocacao: {},
      evidenciasDevolucao: {},
    });
    setUnidadeForm({ materialId: "", codigo: "", metragemInicial: "", observacao: "", localEstoqueId: "" });
    setMaterialOperacao(null);
    setAjusteForm({ tipo: "AJUSTE_POSITIVO", localEstoqueId: "", valor: "", motivo: "", lancadoPor: "", autorizadoPor: "" });
    setTransferenciaForm({ origemId: "", destinoId: "", valor: "", motivo: "", lancadoPor: "", autorizadoPor: "" });
    setLocalForm({ nome: "", endereco: "" });
    setUnidadeOperacao(null);
    setTransferenciaUnidadeForm({ destinoId: "", motivo: "", lancadoPor: "", autorizadoPor: "" });
    setMinimoLocalForm({ saldoId: "", estoqueMinimo: "" });
    setNovoMaterialData({
      nome: "",
      partNumber: "",
      categoria: "MATERIAL_CONSUMO",
      descricao: "",
      fotoProdutoUrl: "",
      fabricante: "",
      fornecedor: "",
      localizacao: "",
      quantidadeDisponivel: "0",
      tipoControle: "UNIDADE",
      unidadeMedida: "UNIDADE",
      dimensao: "",
      comprimentoPorPeca: "",
      metragemDisponivel: "0",
      estoqueMinimo: "0",
      custoMedio: "0",
    });
    setFotoExpandida(null);
    setSimulacaoItens([novaLinhaSimulacao()]);
  };

  const selecionarPlanilhaEstoque = async (event) => {
    const arquivo = event.target.files?.[0];
    event.target.value = "";
    if (!arquivo) return;
    if (!arquivo.name.toLowerCase().endsWith(".xlsx")) {
      setError("Selecione uma planilha no formato .xlsx.");
      return;
    }
    if (arquivo.size > 10 * 1024 * 1024) {
      setError("A planilha excede o limite de 10 MB.");
      return;
    }

    try {
      setImportacaoProcessando(true);
      setError(null);
      setReconciliacaoPreview(null);
      const buffer = await arquivo.arrayBuffer();
      const ExcelJS = (await import("exceljs")).default;
      const workbook = new ExcelJS.Workbook();
      await workbook.xlsx.load(buffer);

      const origem = workbook.worksheets.find(
        (sheet) => normalizarTextoPlanilha(sheet.name) === "estoque atual",
      );
      const cabecalho = origem ? localizarCabecalhoEstoque(origem) : null;
      if (!origem || !cabecalho) {
        const atualizacaoCustos = extrairAtualizacaoCustos(workbook, materiais);
        if (atualizacaoCustos?.itens.length > 0) {
          const digest = await crypto.subtle.digest("SHA-256", buffer);
          const hashSha256 = Array.from(new Uint8Array(digest))
            .map((byte) => byte.toString(16).padStart(2, "0"))
            .join("");
          const custosPorMaterial = new Map(
            atualizacaoCustos.itens
              .filter((item) => item.materialId && item.erros.length === 0)
              .map((item) => [item.materialId, item.custoUnitario]),
          );
          const valorTotalAnterior = materiais.reduce(
            (total, material) => total + valorTotalMaterial(material),
            0,
          );
          const valorTotal = materiais.reduce((total, material) => {
            const custoNovo = custosPorMaterial.get(material.id);
            return total + (custoNovo == null
              ? valorTotalMaterial(material)
              : getSaldoEmEstoque(material) * custoNovo);
          }, 0);
          setImportacaoPreview({
            modo: "CUSTOS",
            nomeArquivo: arquivo.name,
            hashSha256,
            abaOrigem: atualizacaoCustos.abaOrigem,
            abaCatalogo: atualizacaoCustos.abaOrigem,
            abasRetiradas: [],
            retornos: [],
            simulacao: [],
            entradas: [],
            estoqueMinimoPadrao: null,
            itens: atualizacaoCustos.itens,
            avisos: atualizacaoCustos.avisos,
            avisosRetiradas: [],
            valorTotal,
            valorTotalAnterior,
          });
          return;
        }
        const ordemAvulsa = extrairOrdensRetiradaAvulsas(workbook, materiais);
        if (ordemAvulsa.abasRetiradas.length === 0 && ordemAvulsa.avisos.length === 0) {
          throw new Error(
            "Não foi encontrada uma aba válida de ESTOQUE ATUAL ou ORDEM DE RETIRADA.",
          );
        }
        const digest = await crypto.subtle.digest("SHA-256", buffer);
        const hashSha256 = Array.from(new Uint8Array(digest))
          .map((byte) => byte.toString(16).padStart(2, "0"))
          .join("");
        const depositoPadrao = locaisEstoque.find(
          (local) => normalizarTextoPlanilha(local.nome) === "estoque principal",
        ) || locaisEstoque.find((local) => local.ativo !== false);
        const contratoPadrao = contratos.find(
          (contrato) => String(contrato.contrato || "").trim() === "0001",
        ) || contratos.find((contrato) => !contrato.arquivado);
        const responsavelPadrao = funcionarios.find(
          (funcionario) => funcionario.nome === usuario?.nome && funcionario.ativo !== false,
        ) || funcionarios.find((funcionario) => funcionario.ativo !== false);
        setImportacaoLocalId(depositoPadrao?.id ? String(depositoPadrao.id) : "");
        setImportacaoContratoId(contratoPadrao?.id ? String(contratoPadrao.id) : "");
        setImportacaoResponsavelId(
          responsavelPadrao?.id ? String(responsavelPadrao.id) : "",
        );
        setImportacaoPreview({
          modo: "OR_AVULSA",
          nomeArquivo: arquivo.name,
          hashSha256,
          abaOrigem: null,
          abaCatalogo: null,
          abasRetiradas: ordemAvulsa.abasRetiradas,
          retornos: [],
          simulacao: [],
          estoqueMinimoPadrao: null,
          itens: ordemAvulsa.itens,
          entradas: [],
          avisos: ordemAvulsa.avisos,
          avisosRetiradas: ordemAvulsa.avisos,
          valorTotal: materiais.reduce(
            (total, material) => total + valorTotalMaterial(material),
            0,
          ) - ordemAvulsa.abasRetiradas.flatMap((aba) => aba.itens).reduce(
            (total, item) => total
              + Math.min(item.saldoInicial, item.quantidadeRetirada) * item.custoUnitario,
            0,
          ),
          valorTotalAnterior: materiais.reduce(
            (total, material) => total + valorTotalMaterial(material),
            0,
          ),
        });
        return;
      }

      const configuracoes = workbook.worksheets.find(
        (sheet) => normalizarTextoPlanilha(sheet.name) === "configuracoes",
      );
      const minimoConfigurado = Number(
        configuracoes ? valorDaCelula(configuracoes.getCell("B3")) : 0,
      );
      const estoqueMinimoPadrao = Number.isFinite(minimoConfigurado)
        ? Math.max(0, minimoConfigurado)
        : 0;

      const itens = [];
      const avisos = [];
      const avisosRetiradas = [];
      const adicionarAvisoRetirada = (mensagem) => {
        avisos.push(mensagem);
        avisosRetiradas.push(mensagem);
      };
      for (let linha = cabecalho.linha + 1; linha <= origem.rowCount; linha += 1) {
        const nomeBruto = valorDaCelula(origem.getCell(linha, cabecalho.produto));
        const nome = typeof nomeBruto === "string" ? nomeBruto.trim() : "";
        const nomeNormalizado = normalizarTextoPlanilha(nome);
        if (!nomeNormalizado || nomeNormalizado.startsWith("valor total") || nomeNormalizado === "total geral") {
          continue;
        }
        const celulaSaldo = origem.getCell(linha, cabecalho.quantidade);
        const celulaCusto = origem.getCell(linha, cabecalho.custo);
        const saldoInterpretado = interpretarSaldoPlanilha(
          nome,
          valorDaCelula(celulaSaldo),
        );
        const custoUnitario = custoPlanilhaParaEstoque(
          nome,
          valorDaCelula(celulaCusto),
        );
        if (!saldoInterpretado.valido) {
          if (!celulaPossuiFormulaSemResultado(celulaSaldo)) {
            avisos.push(`Linha ${linha}: saldo inválido para ${nome}.`);
          }
          continue;
        }
        if (!Number.isFinite(custoUnitario) || custoUnitario < 0) {
          if (!celulaPossuiFormulaSemResultado(celulaCusto)) {
            avisos.push(`Linha ${linha}: custo unitário inválido para ${nome}.`);
          }
          continue;
        }
        itens.push({
          nome,
          saldo: saldoInterpretado.saldo,
          quantidadeFaltante: saldoInterpretado.quantidadeFaltante,
          custoUnitario,
          linhaOrigem: linha,
        });
      }
      const estoqueAtualPorMaterial = new Map(
        itens.map((item) => [normalizarTextoPlanilha(item.nome), item]),
      );

      const cadastroProdutos = workbook.worksheets.find(
        (sheet) => normalizarTextoPlanilha(sheet.name) === "cadastro produtos",
      );
      const entradas = [];
      const catalogoItens = [];
      let retornosIncluidosNoSaldoBase = false;
      if (cadastroProdutos) {
        let cabecalhoCadastro = null;
        for (let linha = 1; linha <= Math.min(cadastroProdutos.rowCount, 20); linha += 1) {
          const mapa = {};
          for (let coluna = 1; coluna <= cadastroProdutos.columnCount; coluna += 1) {
            const texto = normalizarTextoPlanilha(
              valorDaCelula(cadastroProdutos.getCell(linha, coluna)),
            );
            if (texto === "produto") mapa.produto = coluna;
            if (texto === "estoque inicial") mapa.inicial = coluna;
            if (texto === "estoque apos adicoes") mapa.aposAdicoes = coluna;
            if (texto === "valor unitario") mapa.custo = coluna;
            if (texto === "valor total dos itens") mapa.valorTotal = coluna;
          }
          if (mapa.produto && mapa.inicial && mapa.aposAdicoes && mapa.custo) {
            cabecalhoCadastro = { linha, ...mapa };
            break;
          }
        }

        if (!cabecalhoCadastro) {
          avisos.push("A aba CADASTRO_PRODUTOS não possui os cabeçalhos esperados.");
        } else {
          retornosIncluidosNoSaldoBase = saldoCadastroIncluiRetornos(
            cadastroProdutos,
            cabecalhoCadastro,
          );
          const colunasAdicoes = [];
          for (
            let coluna = (cabecalhoCadastro.valorTotal || cabecalhoCadastro.custo) + 1;
            coluna <= cadastroProdutos.columnCount;
            coluna += 1
          ) {
            const cabecalhoOrigem = String(
              valorDaCelula(cadastroProdutos.getCell(cabecalhoCadastro.linha, coluna)) || "",
            ).trim();
            if (!cabecalhoOrigem) continue;
            const dataEncontrada = cabecalhoOrigem.match(/(\d{1,2})\/(\d{1,2})\/(\d{2,4})/);
            let dataEntrada = null;
            if (dataEncontrada) {
              const ano = dataEncontrada[3].length === 2
                ? `20${dataEncontrada[3]}`
                : dataEncontrada[3];
              dataEntrada = `${ano}-${dataEncontrada[2].padStart(2, "0")}-${dataEncontrada[1].padStart(2, "0")}`;
            }
            colunasAdicoes.push({
              coluna,
              cabecalhoOrigem,
              fornecedor: cabecalhoOrigem.split("|")[0].trim(),
              dataEntrada,
            });
          }

          for (
            let linha = cabecalhoCadastro.linha + 1;
            linha <= cadastroProdutos.rowCount;
            linha += 1
          ) {
            const nome = String(
              valorDaCelula(cadastroProdutos.getCell(linha, cabecalhoCadastro.produto)) || "",
            ).trim();
            const chaveMaterial = normalizarTextoPlanilha(nome);
            if (!chaveMaterial || chaveMaterial.startsWith("total")
              || chaveMaterial.startsWith("valor total")) continue;
            const estoqueInicial = quantidadePlanilhaParaEstoque(
              nome,
              valorDaCelula(cadastroProdutos.getCell(linha, cabecalhoCadastro.inicial)),
            );
            const celulaEstoqueAposAdicoes = cadastroProdutos.getCell(
              linha,
              cabecalhoCadastro.aposAdicoes,
            );
            const estoqueAposAdicoesInformado = quantidadePlanilhaParaEstoque(
              nome,
              valorDaCelula(celulaEstoqueAposAdicoes),
            );
            const custoUnitario = custoPlanilhaParaEstoque(
              nome,
              valorDaCelula(cadastroProdutos.getCell(linha, cabecalhoCadastro.custo)),
            );
            if (!Number.isFinite(estoqueInicial) || estoqueInicial < 0
              || !Number.isFinite(custoUnitario) || custoUnitario < 0) {
              avisos.push(`CADASTRO_PRODUTOS, linha ${linha}: valores inválidos para ${nome}.`);
              continue;
            }

            const adicoesMaterial = colunasAdicoes.map((origem) => ({
              ...origem,
              quantidade: quantidadePlanilhaParaEstoque(
                nome,
                valorDaCelula(cadastroProdutos.getCell(linha, origem.coluna)),
              ),
            })).filter((entrada) => Number.isFinite(entrada.quantidade) && entrada.quantidade > 0);
            const totalCalculado = adicoesMaterial.reduce(
              (total, entrada) => total + entrada.quantidade,
              estoqueInicial,
            );
            if (!retornosIncluidosNoSaldoBase
              && Number.isFinite(estoqueAposAdicoesInformado)
              && Math.abs(totalCalculado - estoqueAposAdicoesInformado) > 0.001) {
              avisos.push(
                `CADASTRO_PRODUTOS, linha ${linha}: estoque após adições não confere para ${nome}.`,
              );
            }
            if (!Number.isFinite(estoqueAposAdicoesInformado)
              && !celulaPossuiFormulaSemResultado(celulaEstoqueAposAdicoes)) {
              avisos.push(
                `CADASTRO_PRODUTOS, linha ${linha}: estoque após adições inválido para ${nome}.`,
              );
            }
            catalogoItens.push({
              nome,
              saldoBase: resolverSaldoBaseCadastro(
                totalCalculado,
                estoqueAposAdicoesInformado,
                retornosIncluidosNoSaldoBase,
              ),
              custoUnitario,
              linhaOrigem: linha,
            });
            if (estoqueInicial > 0) {
              entradas.push({
                tipo: "ESTOQUE_INICIAL",
                cabecalhoOrigem: "ESTOQUE INICIAL",
                fornecedor: null,
                dataEntrada: null,
                nomeMaterial: nome,
                quantidade: estoqueInicial,
                custoUnitario,
                linhaOrigem: linha,
                colunaOrigem: cabecalhoCadastro.inicial,
              });
            }
            adicoesMaterial.forEach((entrada) => entradas.push({
              tipo: "ADICAO",
              cabecalhoOrigem: entrada.cabecalhoOrigem,
              fornecedor: entrada.fornecedor || null,
              dataEntrada: entrada.dataEntrada,
              nomeMaterial: nome,
              quantidade: entrada.quantidade,
              custoUnitario,
              linhaOrigem: linha,
              colunaOrigem: entrada.coluna,
            }));
          }
        }
      } else {
        avisos.push("Não foi encontrada a aba CADASTRO_PRODUTOS.");
      }

      if (catalogoItens.length === 0) {
        throw new Error("A aba CADASTRO_PRODUTOS não possui materiais válidos para importar.");
      }

      const catalogoPorMaterial = new Map(
        catalogoItens.map((item) => [normalizarTextoPlanilha(item.nome), item]),
      );
      const saldoHistoricoPorMaterial = new Map(
        catalogoItens.map((item) => [normalizarTextoPlanilha(item.nome), item.saldoBase]),
      );

      const identificarCidade = (sheet) => {
        for (let linha = 1; linha <= Math.min(sheet.rowCount, 10); linha += 1) {
          for (let coluna = 1; coluna <= Math.min(sheet.columnCount, 10); coluna += 1) {
            const bruto = valorDaCelula(sheet.getCell(linha, coluna));
            if (typeof bruto !== "string") continue;
            if (normalizarTextoPlanilha(bruto).startsWith("controle de retiradas e estoque")) {
              const cidade = bruto.split("-").slice(1).join("-").trim();
              if (cidade) return cidade.replaceAll("_", " ");
            }
          }
        }
        return sheet.name
          .replace(/^ORDEM DE RETIRADA\s*-\s*/i, "")
          .replace(/\d+$/, "")
          .trim();
      };
      const abasRetiradas = workbook.worksheets
        .filter((sheet) => sheet.id !== origem.id)
        .map((sheet) => ({ sheet, cabecalho: localizarCabecalhoEstoque(sheet) }))
        .filter(({ cabecalho: cabecalhoRetirada }) =>
          cabecalhoRetirada?.retirada && cabecalhoRetirada?.saldoFinal)
        .map(({ sheet, cabecalho: cabecalhoRetirada }) => {
          const itensRetirada = [];
          for (
            let linha = cabecalhoRetirada.linha + 1;
            linha <= sheet.rowCount;
            linha += 1
          ) {
            const nomeBruto = valorDaCelula(
              sheet.getCell(linha, cabecalhoRetirada.produto),
            );
            const nome = typeof nomeBruto === "string" ? nomeBruto.trim() : "";
            if (!nome) continue;
            const chaveMaterial = normalizarTextoPlanilha(nome);
            if (chaveMaterial.startsWith("total") || chaveMaterial.startsWith("valor total")) {
              continue;
            }
            const saldoInicialInformado = quantidadePlanilhaParaEstoque(
              nome,
              valorDaCelula(sheet.getCell(linha, cabecalhoRetirada.quantidade)),
            );
            const retiradaInformada = quantidadePlanilhaParaEstoque(
              nome,
              valorDaCelula(sheet.getCell(linha, cabecalhoRetirada.retirada)),
            );
            const quantidadeRetirada = Number.isFinite(retiradaInformada)
              ? retiradaInformada
              : 0;
            const custoInformado = custoPlanilhaParaEstoque(
              nome,
              valorDaCelula(sheet.getCell(linha, cabecalhoRetirada.custo)),
            );
            const custoUnitario = Number.isFinite(custoInformado)
              ? custoInformado
              : catalogoPorMaterial.get(chaveMaterial)?.custoUnitario;
            const itemCatalogo = catalogoPorMaterial.get(chaveMaterial);
            if (!itemCatalogo) {
              adicionarAvisoRetirada(
                `Aba ${sheet.name}, linha ${linha}: ${nome} não existe no CADASTRO_PRODUTOS.`,
              );
              continue;
            }
            const saldoInicial = saldoHistoricoPorMaterial.get(chaveMaterial) ?? 0;
            if (
              quantidadeRetirada < 0
              || !Number.isFinite(custoUnitario)
              || custoUnitario < 0
            ) {
              adicionarAvisoRetirada(
                `Aba ${sheet.name}, linha ${linha}: retirada inválida para ${nome}.`,
              );
              continue;
            }
            const saldoFinalInformado = quantidadePlanilhaParaEstoque(
              nome,
              valorDaCelula(sheet.getCell(linha, cabecalhoRetirada.saldoFinal)),
            );
            const saldoFinal = arredondarQuantidadeEstoque(
              saldoInicial - quantidadeRetirada,
            );
            if (quantidadeRetirada === 0) continue;
            if (Number.isFinite(saldoInicialInformado)
              && Math.abs(saldoInicialInformado - saldoInicial) > 0.001) {
              adicionarAvisoRetirada(
                `Aba ${sheet.name}, linha ${linha}: saldo inicial não confere para ${nome}.`,
              );
              continue;
            }
            if (Number.isFinite(saldoFinalInformado)
              && Math.abs(saldoFinalInformado - saldoFinal) > 0.001) {
              adicionarAvisoRetirada(
                `Aba ${sheet.name}, linha ${linha}: saldo final não confere para ${nome}.`,
              );
              continue;
            }
            saldoHistoricoPorMaterial.set(chaveMaterial, saldoFinal);
            itensRetirada.push({
              nomeMaterial: nome,
              linhaOrigem: linha,
              saldoInicial,
              quantidadeRetirada,
              saldoFinal,
              quantidadeFaltante: arredondarQuantidadeEstoque(
                saldoFinal < 0 ? Math.abs(saldoFinal) : 0,
              ),
              custoUnitario,
              dataRetirada: cabecalhoRetirada.dataRetirada
                ? dataPlanilhaParaIso(
                    valorDaCelula(sheet.getCell(linha, cabecalhoRetirada.dataRetirada)),
                  )
                : null,
            });
          }
          return {
            nome: sheet.name,
            cidade: identificarCidade(sheet),
            itens: itensRetirada,
            totalRetirado: itensRetirada.reduce(
              (total, item) => total + item.quantidadeRetirada,
              0,
            ),
            faltas: itensRetirada.filter((item) => item.quantidadeFaltante > 0).length,
          };
        })
        .filter((aba) => aba.itens.length > 0);

      const abaRetornos = workbook.worksheets.find(
        (sheet) => normalizarTextoPlanilha(sheet.name) === "sobras retornos",
      );
      const retornos = [];
      if (abaRetornos) {
        let linhaCabecalho = null;
        for (let linha = 1; linha <= Math.min(abaRetornos.rowCount, 20); linha += 1) {
          if (normalizarTextoPlanilha(
            valorDaCelula(abaRetornos.getCell(linha, 1)),
          ) === "produto") {
            linhaCabecalho = linha;
            break;
          }
        }
        if (linhaCabecalho) {
          for (let coluna = 2; coluna <= abaRetornos.columnCount; coluna += 1) {
            const cabecalhoCidade = String(
              valorDaCelula(abaRetornos.getCell(linhaCabecalho, coluna)) || "",
            ).trim();
            const cidade = cabecalhoCidade.replace(/^COMARCA\s*-\s*/i, "").trim();
            if (!cidade || normalizarTextoPlanilha(cidade) === "total") continue;
            for (let linha = linhaCabecalho + 1; linha <= abaRetornos.rowCount; linha += 1) {
              const nome = String(
                valorDaCelula(abaRetornos.getCell(linha, 1)) || "",
              ).trim();
              const quantidadeRetornada = quantidadePlanilhaParaEstoque(
                nome,
                valorDaCelula(abaRetornos.getCell(linha, coluna)),
              );
              if (!nome || !Number.isFinite(quantidadeRetornada) || quantidadeRetornada <= 0) {
                continue;
              }
              const chaveMaterial = normalizarTextoPlanilha(nome);
              if (!catalogoPorMaterial.has(chaveMaterial)) {
                avisos.push(
                  `Aba ${abaRetornos.name}, linha ${linha}: ${nome} não existe no CADASTRO_PRODUTOS.`,
                );
                continue;
              }
              retornos.push({
                aba: abaRetornos.name,
                cidade,
                nomeMaterial: nome,
                quantidadeRetornada,
                linhaOrigem: linha,
              });
              if (!retornosIncluidosNoSaldoBase) {
                saldoHistoricoPorMaterial.set(
                  chaveMaterial,
                  (saldoHistoricoPorMaterial.get(chaveMaterial) || 0) + quantidadeRetornada,
                );
              }
            }
          }
        }
      }

      const nomesExistentes = materiais.reduce((mapa, material) => {
        const chave = normalizarTextoPlanilha(material.nome);
        mapa.set(chave, [...(mapa.get(chave) || []), material]);
        return mapa;
      }, new Map());
      const nomesPlanilha = new Set();
      const itensPreview = catalogoItens.map((item) => {
        const chave = normalizarTextoPlanilha(item.nome);
        const correspondencias = nomesExistentes.get(chave) || [];
        const erros = [];
        if (nomesPlanilha.has(chave)) erros.push("Nome duplicado na planilha");
        nomesPlanilha.add(chave);
        if (correspondencias.length > 1) erros.push("Nome duplicado no sistema");
        const existente = correspondencias[0];
        if (existente && ["BOBINA", "ROLO"].includes(existente.tipoControle)) {
          erros.push("Bobina/rolo rastreável exige cadastro físico individual");
        }
        const saldoAtualBruto = existente
          ? controlaMetragem(existente)
            ? Number(existente.metragemDisponivel || 0)
            : Number(existente.quantidadeDisponivel || 0)
          : 0;
        const saldoAtual = existente && ehCaboEmBobina305(existente.nome)
          && existente.tipoControle === "FRACIONADO"
          ? quantidadePlanilhaParaEstoque(existente.nome, saldoAtualBruto)
          : saldoAtualBruto;
        const saldoCalculado = Math.max(0, saldoHistoricoPorMaterial.get(chave) ?? item.saldoBase);
        const itemConsolidado = estoqueAtualPorMaterial.get(chave);
        const consolidadoInformado = itemConsolidado?.saldo;
        if (Number.isFinite(consolidadoInformado)
          && Math.abs(consolidadoInformado - saldoCalculado) > 0.001) {
          avisos.push(`ESTOQUE ATUAL: saldo não confere para ${item.nome}.`);
        }
        return {
          nome: item.nome,
          saldo: saldoCalculado,
          quantidadeFaltante: itemConsolidado?.quantidadeFaltante || 0,
          custoUnitario: item.custoUnitario,
          linhaOrigem: item.linhaOrigem,
          materialId: existente?.id,
          saldoAtual,
          tipoControle: ehCaboEmBobina305(item.nome)
            ? "METRAGEM"
            : Number.isInteger(saldoCalculado) ? "UNIDADE" : "FRACIONADO",
          acao: existente ? "ATUALIZAR" : "CRIAR",
          erros,
        };
      });
      const dadosBasePorMaterial = new Map(
        itensPreview.map((item) => [normalizarTextoPlanilha(item.nome), item]),
      );

      const abaSimulacao = workbook.worksheets.find(
        (sheet) => normalizarTextoPlanilha(sheet.name) === "simulacao",
      );
      const simulacao = [];
      if (abaSimulacao) {
        const linhaCabecalho = Array.from(
          { length: Math.min(20, abaSimulacao.rowCount) },
          (_, indice) => indice + 1,
        ).find((linha) => normalizarTextoPlanilha(
          valorDaCelula(abaSimulacao.getCell(linha, 1)),
        ) === "produto");
        if (linhaCabecalho) {
          for (let linha = linhaCabecalho + 1; linha <= abaSimulacao.rowCount; linha += 1) {
            const nome = String(
              valorDaCelula(abaSimulacao.getCell(linha, 1)) || "",
            ).trim();
            const quantidadeSimulada = quantidadePlanilhaParaEstoque(
              nome,
              valorDaCelula(abaSimulacao.getCell(linha, 3)),
            );
            const itemBase = dadosBasePorMaterial.get(normalizarTextoPlanilha(nome));
            if (!nome || !itemBase || !Number.isFinite(quantidadeSimulada)
              || quantidadeSimulada <= 0) {
              continue;
            }
            const saldoFinal = arredondarQuantidadeEstoque(
              itemBase.saldo - quantidadeSimulada,
            );
            simulacao.push({
              nomeMaterial: nome,
              estoqueAtual: itemBase.saldo,
              quantidadeSimulada,
              saldoFinal,
              quantidadeFaltante: arredondarQuantidadeEstoque(Math.max(0, -saldoFinal)),
              linhaOrigem: linha,
            });
          }
        }
      }
      const digest = await crypto.subtle.digest("SHA-256", buffer);
      const hashSha256 = Array.from(new Uint8Array(digest))
        .map((byte) => byte.toString(16).padStart(2, "0"))
        .join("");
      const depositoPadrao =
        locaisEstoque.find(
          (local) => normalizarTextoPlanilha(local.nome) === "estoque principal",
        ) || locaisEstoque.find((local) => local.ativo !== false);

      setImportacaoLocalId(depositoPadrao?.id ? String(depositoPadrao.id) : "");
      const contratoPadrao = contratos.find(
        (contrato) => String(contrato.contrato || "").trim() === "0001",
      ) || contratos.find((contrato) => !contrato.arquivado);
      const responsavelPadrao = funcionarios.find(
        (funcionario) => funcionario.nome === usuario?.nome && funcionario.ativo !== false,
      ) || funcionarios.find((funcionario) => funcionario.ativo !== false);
      setImportacaoContratoId(contratoPadrao?.id ? String(contratoPadrao.id) : "");
      setImportacaoResponsavelId(
        responsavelPadrao?.id ? String(responsavelPadrao.id) : "",
      );
      setImportacaoPreview({
        nomeArquivo: arquivo.name,
        hashSha256,
        abaOrigem: origem.name,
        abaCatalogo: cadastroProdutos?.name,
        abasRetiradas,
        retornos,
        simulacao,
        estoqueMinimoPadrao,
        itens: itensPreview,
        entradas,
        avisos,
        avisosRetiradas,
        valorTotal: itensPreview.reduce(
          (total, item) => total + item.saldo * item.custoUnitario,
          0,
        ),
        valorTotalAnterior: materiais.reduce(
          (total, material) => total + valorTotalMaterial(material),
          0,
        ),
      });
    } catch (err) {
      setError(err.message || "Não foi possível ler a planilha.");
    } finally {
      setImportacaoProcessando(false);
    }
  };

  const confirmarImportacaoPlanilha = async () => {
    if (!importacaoPreview) return;
    if (importacaoPreview.itens.some((item) => item.erros.length > 0)) {
      setError("Corrija os itens bloqueados antes de importar.");
      return;
    }
    if (importacaoPreview.avisos.length > 0) {
      setError("Corrija todas as linhas inválidas da planilha antes de importar.");
      return;
    }
    try {
      setImportacaoProcessando(true);
      if (importacaoPreview.modo === "CUSTOS") {
        const itensVinculados = importacaoPreview.itens.filter(
          (item) => item.materialId && item.erros.length === 0,
        );
        if (itensVinculados.length === 0) {
          setError("Nenhum material da planilha foi encontrado no estoque.");
          return;
        }
        const response = await api.post("/estoque/importacoes/custos", {
          nomeArquivo: importacaoPreview.nomeArquivo,
          hashSha256: importacaoPreview.hashSha256,
          itens: itensVinculados.map((item) => ({
            materialId: item.materialId,
            nomePlanilha: item.nome,
            custoUnitario: item.custoUnitario,
            linhaOrigem: item.linhaOrigem,
          })),
        });
        const resultado = response.data;
        setSuccessMessage(
          `Custos processados: ${resultado.materiaisAtualizados} alterados e `
          + `${resultado.itensProcessados - resultado.materiaisAtualizados} mantidos. `
          + "As quantidades do estoque não foram modificadas.",
        );
        setImportacaoPreview(null);
        await fetchData();
        return;
      }
      if (importacaoPreview.modo === "SALDOS") {
        if (!importacaoLocalId) {
          setError("Selecione o depósito de referência antes de sincronizar.");
          return;
        }
        const response = await api.post("/estoque/importacoes/saldos", {
          nomeArquivo: importacaoPreview.nomeArquivo,
          hashSha256: importacaoPreview.hashSha256,
          localEstoqueId: Number(importacaoLocalId),
          itens: importacaoPreview.itens.map((item) => ({
            materialId: item.materialId,
            nomePlanilha: item.nome,
            saldo: item.saldo,
            linhaOrigem: item.linhaOrigem,
          })),
        });
        const resultado = response.data;
        setSuccessMessage(
          `Saldos sincronizados: ${resultado.ajustesPositivos} aumentos, `
          + `${resultado.ajustesNegativos} reduções e `
          + `${resultado.itensProcessados - resultado.materiaisAtualizados} mantidos. `
          + "Custos, reservas, OS e OR não foram alterados.",
        );
        setImportacaoPreview(null);
        setImportacaoLocalId("");
        await fetchData();
        return;
      }
      if (!importacaoLocalId || !importacaoContratoId || !importacaoResponsavelId) {
        setError("Selecione depósito, contrato e responsável antes de importar.");
        return;
      }
      const response = await api.post("/estoque/importacoes/planilha", {
        nomeArquivo: importacaoPreview.nomeArquivo,
        hashSha256: importacaoPreview.hashSha256,
        localEstoqueId: Number(importacaoLocalId),
        saldoConsolidado: importacaoPreview.modo !== "OR_AVULSA",
        autoCriarOperacoes: true,
        contratoId: Number(importacaoContratoId),
        responsavelId: Number(importacaoResponsavelId),
        estoqueMinimoPadrao: importacaoPreview.estoqueMinimoPadrao,
        avisos: importacaoPreview.avisos,
        entradas: importacaoPreview.entradas,
        itens: importacaoPreview.itens.map(({ nome, saldo, custoUnitario, linhaOrigem }) => ({
          nome,
          saldo,
          custoUnitario,
          linhaOrigem,
        })),
        retiradas: importacaoPreview.abasRetiradas.flatMap((aba) =>
          aba.itens.map((item) => ({
            aba: aba.nome,
            cidade: aba.cidade,
            nomeMaterial: item.nomeMaterial,
            saldoInicial: item.saldoInicial,
            quantidadeRetirada: item.quantidadeRetirada,
            saldoFinal: item.saldoFinal,
            custoUnitario: item.custoUnitario,
            dataRetirada: item.dataRetirada,
            linhaOrigem: item.linhaOrigem,
          }))),
        retornos: importacaoPreview.retornos,
        simulacao: importacaoPreview.simulacao,
      });
      const resultado = response.data;
      setSuccessMessage(importacaoPreview.modo === "OR_AVULSA"
        ? `Ordem de retirada importada: ${resultado.retiradasImportadas} itens registrados, `
          + `${resultado.ordensServicoCriadas} OS e ${resultado.ordensRetiradaCriadas} OR criadas. `
          + `${resultado.faltasIdentificadas} faltas identificadas; estoque baixado até o saldo disponível.`
        : `Planilha importada: ${resultado.materiaisCriados} materiais criados, `
          + `${resultado.materiaisAtualizados} atualizados e `
          + `${resultado.entradasImportadas} entradas históricas preservadas. `
          + `${resultado.retiradasImportadas} retiradas históricas registradas. `
          + `${resultado.ordensServicoCriadas} OS e ${resultado.ordensRetiradaCriadas} OR criadas. `
          + `${resultado.retornosImportados} retornos preservados; estoque consolidado aplicado.`,
      );
      setImportacaoPreview(null);
      setImportacaoLocalId("");
      setImportacaoContratoId("");
      setImportacaoResponsavelId("");
      await fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Não foi possível importar a planilha."));
    } finally {
      setImportacaoProcessando(false);
    }
  };

  const reconciliarHistoricoRetiradas = async (confirmar = false) => {
    if (!importacaoPreview?.abasRetiradas?.length) return;
    const validacao = resumirAvisosImportacao(
      importacaoPreview.avisos,
      importacaoPreview.avisosRetiradas,
    );
    if (validacao.bloqueiaReconciliacao) {
      setError(
        `${validacao.retiradas} inconsistência(s) nas abas de retirada impedem a comparação. `
        + "Corrija esses itens na planilha antes de reconciliar o histórico.",
      );
      return;
    }
    const historicoPorOrigem = new Map();
    retiradasImportadas.forEach((retirada) => {
      const chave = `${normalizarTextoPlanilha(retirada.aba)}|${normalizarTextoPlanilha(retirada.material)}`;
      if (!historicoPorOrigem.has(chave)) historicoPorOrigem.set(chave, retirada);
    });

    const itens = [];
    const ausentes = [];
    importacaoPreview.abasRetiradas.forEach((aba) => {
      aba.itens.forEach((item) => {
        const chave = `${normalizarTextoPlanilha(aba.nome)}|${normalizarTextoPlanilha(item.nomeMaterial)}`;
        const atual = historicoPorOrigem.get(chave);
        if (!atual?.retiradaImportadaId) {
          ausentes.push(`${aba.nome}: ${item.nomeMaterial}`);
          return;
        }
        itens.push({
          retiradaImportadaId: atual.retiradaImportadaId,
          saldoInicial: item.saldoInicial,
          quantidadeRetirada: item.quantidadeRetirada,
          saldoFinal: item.saldoFinal,
          dataRetirada: item.dataRetirada,
        });
      });
    });

    if (ausentes.length > 0) {
      setError(
        `${ausentes.length} itens não possuem retirada histórica vinculada. `
        + `Importe as ORs ausentes antes de reconciliar: ${ausentes.slice(0, 3).join("; ")}.`,
      );
      return;
    }

    try {
      setReconciliacaoProcessando(true);
      setError(null);
      const response = await api.post("/estoque/importacoes/planilha/retiradas/reconciliar", {
        nomeArquivo: importacaoPreview.nomeArquivo,
        hashSha256: importacaoPreview.hashSha256,
        confirmar,
        itens,
      });
      const resultado = response.data;
      if (confirmar) {
        setSuccessMessage(
          `${resultado.divergencias} correção(ões) aplicada(s) ao histórico de OR. `
          + "O saldo atual do estoque não foi movimentado.",
        );
        setImportacaoPreview(null);
        setReconciliacaoPreview(null);
        await fetchData();
      } else {
        setReconciliacaoPreview(resultado);
        if (resultado.divergencias === 0) {
          setSuccessMessage("As retiradas da planilha já correspondem ao histórico das ORs.");
        }
      }
    } catch (err) {
      setError(getApiErrorMessage(err, "Não foi possível reconciliar o histórico das ORs."));
    } finally {
      setReconciliacaoProcessando(false);
    }
  };

  const selecionarNotaFiscal = async (event) => {
    const arquivo = event.target.files?.[0];
    event.target.value = "";
    if (!arquivo) return;
    const extensaoValida = /\.(xml|pdf)$/i.test(arquivo.name);
    if (!extensaoValida) {
      setError("Selecione uma nota fiscal nos formatos XML ou PDF.");
      return;
    }
    if (arquivo.size > 10 * 1024 * 1024) {
      setError("A nota fiscal excede o limite de 10 MB.");
      return;
    }
    try {
      setNotaFiscalProcessando(true);
      setError(null);
      const arquivoBase64 = await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = () => reject(new Error("Não foi possível ler a nota fiscal."));
        reader.readAsDataURL(arquivo);
      });
      const response = await api.post("/estoque/importacoes/notas-fiscais/analisar", {
        nomeArquivo: arquivo.name,
        contentType: arquivo.type,
        arquivoBase64,
      });
      const depositoPadrao = locaisEstoque.find(
        (local) => normalizarTextoPlanilha(local.nome) === "estoque principal",
      ) || locaisEstoque.find((local) => local.ativo !== false);
      setNotaFiscalLocalId(depositoPadrao?.id ? String(depositoPadrao.id) : "");
      setNotaFiscalPreview({
        ...response.data,
        arquivoBase64,
        contentType: arquivo.type,
        itens: (response.data.itens || []).map((item) => {
          const existente = materiais.find((material) => material.id === item.materialSugeridoId);
          return {
            ...item,
            importar: true,
            materialId: item.materialSugeridoId ? String(item.materialSugeridoId) : "",
            nome: existente?.nome || item.descricao || "",
            partNumber: item.codigoProduto || "",
            categoria: existente?.categoria || "MATERIAL_CONSUMO",
            tipoControle: existente?.tipoControle || item.tipoControleSugerido || "UNIDADE",
            unidadeMedida: existente?.unidadeMedida || item.unidadeMedidaSugerida || "UNIDADE",
          };
        }),
      });
    } catch (err) {
      setError(getApiErrorMessage(err, "Não foi possível analisar a nota fiscal."));
    } finally {
      setNotaFiscalProcessando(false);
    }
  };

  const atualizarItemNotaFiscal = (indice, campo, valor) => {
    setNotaFiscalPreview((atual) => ({
      ...atual,
      itens: atual.itens.map((item, posicao) => {
        if (posicao !== indice) return item;
        if (campo === "materialId") {
          const material = materiais.find((registro) => String(registro.id) === String(valor));
          return {
            ...item,
            materialId: valor,
            nome: material?.nome || item.nome,
            tipoControle: material?.tipoControle || item.tipoControle,
            unidadeMedida: material?.unidadeMedida || item.unidadeMedida,
          };
        }
        if (campo === "codigoProduto") {
          return {
            ...item,
            codigoProduto: valor,
            partNumber: !item.partNumber || item.partNumber === item.codigoProduto
              ? valor
              : item.partNumber,
          };
        }
        if (campo === "tipoControle") {
          return {
            ...item,
            tipoControle: valor,
            unidadeMedida: unidadePadraoPorControle[valor] || "UNIDADE",
          };
        }
        return { ...item, [campo]: valor };
      }),
    }));
  };

  const adicionarItemNotaFiscal = () => {
    setNotaFiscalPreview((atual) => ({
      ...atual,
      itens: [...atual.itens, {
        importar: true,
        materialId: "",
        codigoProduto: "",
        nome: "",
        partNumber: "",
        descricao: "",
        ncm: "",
        cfop: "",
        unidadeFiscal: "UN",
        quantidade: 1,
        valorUnitario: 0,
        valorTotal: 0,
        categoria: "MATERIAL_CONSUMO",
        tipoControle: "UNIDADE",
        unidadeMedida: "UNIDADE",
      }],
    }));
  };

  const confirmarImportacaoNotaFiscal = async () => {
    if (!notaFiscalPreview || !notaFiscalLocalId) return;
    const selecionados = notaFiscalPreview.itens.filter((item) => item.importar);
    if (selecionados.length === 0) {
      setError("Selecione ao menos um item da nota fiscal.");
      return;
    }
    try {
      setNotaFiscalProcessando(true);
      setError(null);
      const response = await api.post("/estoque/importacoes/notas-fiscais", {
        nomeArquivo: notaFiscalPreview.nomeArquivo,
        contentType: notaFiscalPreview.contentType,
        arquivoBase64: notaFiscalPreview.arquivoBase64,
        hashSha256: notaFiscalPreview.hashSha256,
        localEstoqueId: Number(notaFiscalLocalId),
        chaveAcesso: notaFiscalPreview.chaveAcesso,
        numero: notaFiscalPreview.numero,
        serie: notaFiscalPreview.serie,
        emitenteNome: notaFiscalPreview.emitenteNome,
        emitenteCnpj: notaFiscalPreview.emitenteCnpj,
        dataEmissao: notaFiscalPreview.dataEmissao || null,
        itens: notaFiscalPreview.itens.map((item) => ({
          ...item,
          materialId: item.materialId ? Number(item.materialId) : null,
          quantidade: Number(item.quantidade),
          valorUnitario: Number(item.valorUnitario),
        })),
      });
      setSuccessMessage(
        `NF ${response.data.numero || "sem número"} importada: ${response.data.itensProcessados} itens, `
        + `${response.data.materiaisCriados} materiais criados e ${response.data.materiaisExistentes} vinculados.`,
      );
      setNotaFiscalPreview(null);
      setNotaFiscalLocalId("");
      await fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Não foi possível importar a nota fiscal."));
    } finally {
      setNotaFiscalProcessando(false);
    }
  };

  const abrirDetalheImportacao = async (importacaoId) => {
    try {
      setImportacaoDetalhe({ carregando: true });
      const response = await api.get(`/estoque/importacoes/planilha/${importacaoId}`);
      setImportacaoDetalhe(response.data);
    } catch (err) {
      setImportacaoDetalhe(null);
      setError(getApiErrorMessage(err, "Não foi possível abrir os detalhes da importação."));
    }
  };

  const abrirDetalheNotaFiscal = async (importacaoId) => {
    try {
      setNotaFiscalDetalhe({ carregando: true });
      const response = await api.get(`/estoque/importacoes/notas-fiscais/${importacaoId}`);
      setNotaFiscalDetalhe(response.data);
    } catch (err) {
      setNotaFiscalDetalhe(null);
      setError(getApiErrorMessage(err, "Não foi possível abrir os detalhes da nota fiscal."));
    }
  };

  const baixarArquivoNotaFiscal = async (importacao) => {
    try {
      const response = await api.get(
        `/estoque/importacoes/notas-fiscais/${importacao.id}/arquivo`,
        { responseType: "blob" },
      );
      const url = URL.createObjectURL(response.data);
      const link = document.createElement("a");
      link.href = url;
      link.download = importacao.nomeArquivo || `nota-fiscal-${importacao.numero || importacao.id}`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(getApiErrorMessage(err, "Não foi possível baixar o arquivo original da nota fiscal."));
    }
  };

  const abrirModalNovoMaterial = () => {
    setMaterialEmEdicao(null);
    setNovoMaterialData({
      nome: "",
      partNumber: "",
      categoria: "MATERIAL_CONSUMO",
      descricao: "",
      fotoProdutoUrl: "",
      fabricante: "",
      fornecedor: "",
      localizacao: "",
      quantidadeDisponivel: "0",
      tipoControle: "UNIDADE",
      unidadeMedida: "UNIDADE",
      dimensao: "",
      comprimentoPorPeca: "",
      metragemDisponivel: "0",
      estoqueMinimo: "0",
      custoMedio: "0",
    });
    setShowNovoMaterialModal(true);
  };

  const abrirModalEditarMaterial = (material) => {
    setMaterialEmEdicao(material);
    setNovoMaterialData({
      nome: material.nome || "",
      partNumber: material.partNumber || "",
      categoria: material.categoria || "MATERIAL_CONSUMO",
      descricao: material.descricao || "",
      fotoProdutoUrl: material.fotoProdutoUrl || "",
      fabricante: material.fabricante || "",
      fornecedor: material.fornecedor || "",
      localizacao: material.localizacao || "",
      quantidadeDisponivel: String(material.quantidadeDisponivel ?? 0),
      tipoControle: material.tipoControle || "UNIDADE",
      unidadeMedida: material.unidadeMedida || "UNIDADE",
      dimensao: material.dimensao || "",
      comprimentoPorPeca: String(material.comprimentoPorPeca ?? ""),
      metragemDisponivel: String(material.metragemDisponivel ?? 0),
      estoqueMinimo: String(material.estoqueMinimo ?? 0),
      custoMedio: String(material.custoMedio ?? 0),
    });
    setShowNovoMaterialModal(true);
  };

  const removerMaterial = async (material) => {
    const saldoDisponivel = Number(material.quantidadeDisponivel || 0);
    const saldoReservado = Number(material.quantidadeReservada || 0);
    const metragemDisponivel = Number(material.metragemDisponivel || 0);
    if (saldoDisponivel > 0 || saldoReservado > 0 || metragemDisponivel > 0) {
      setError("Zere o saldo disponível, a metragem e as reservas antes de remover o material.");
      return;
    }
    const confirmou = window.confirm(
      `Remover "${material.nome}" do estoque? O histórico de movimentações será preservado.`,
    );
    if (!confirmou) return;
    try {
      setError(null);
      await api.delete(`/estoque/materiais/${material.id}`);
      setSuccessMessage(`Material "${material.nome}" removido do estoque.`);
      await fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Não foi possível remover o material."));
    }
  };

  const restaurarMaterial = async (material) => {
    const confirmou = window.confirm(
      `Restaurar "${material.nome}" para o catálogo operacional? O item continuará com saldo zero.`,
    );
    if (!confirmou) return;
    try {
      setError(null);
      await api.patch(`/estoque/materiais/${material.id}/restaurar`);
      setSuccessMessage(`Material "${material.nome}" restaurado no catálogo.`);
      await fetchData();
      setAbaEstoque("geral");
    } catch (err) {
      setError(getApiErrorMessage(err, "Não foi possível restaurar o material."));
    }
  };

  const handleInputChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const handleNovoMaterialChange = (e) => {
    const { name, value } = e.target;
    setNovoMaterialData((prev) => ({
      ...prev,
      [name]: value,
      ...(name === "tipoControle"
        ? { unidadeMedida: unidadePadraoPorControle[value] || "UNIDADE" }
        : {}),
    }));
  };

  const handleNovoMaterialFotoChange = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (!["image/jpeg", "image/png", "image/webp"].includes(file.type)) {
      setError("Selecione uma imagem válida nos formatos JPEG, PNG ou WEBP.");
      e.target.value = "";
      return;
    }

    const reader = new FileReader();
    reader.onload = () => {
      setNovoMaterialData((prev) => ({
        ...prev,
        fotoProdutoUrl: reader.result,
      }));
    };
    reader.readAsDataURL(file);
  };

  //   SUBMIT DO PRODUTO NOVO (CATÁLOGO)
  const handleSubmitNovoMaterial = async (e) => {
    e.preventDefault();
    try {
      const payload = {
        ...novoMaterialData,
        quantidadeDisponivel:
          parseInt(novoMaterialData.quantidadeDisponivel, 10) || 0,
        comprimentoPorPeca:
          parseFloat(novoMaterialData.comprimentoPorPeca) || null,
        metragemDisponivel:
          parseFloat(novoMaterialData.metragemDisponivel) || 0,
        estoqueMinimo: parseFloat(novoMaterialData.estoqueMinimo) || 0,
        custoMedio: parseFloat(novoMaterialData.custoMedio) || 0,
      };
      if (materialEmEdicao) {
        await api.put(`/estoque/materiais/${materialEmEdicao.id}`, payload);
      } else {
        await api.post("/estoque/materiais", payload);
      }
      setSuccessMessage(
        materialEmEdicao
          ? "Material atualizado com sucesso!"
          : "Novo material cadastrado no catálogo com sucesso!",
      );
      setTimeout(() => setSuccessMessage(null), 4000);
      handleCloseModal();
      fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao cadastrar novo material."));
      console.error(err);
    }
  };

  const handleSubmitEntrada = async (e) => {
    e.preventDefault();
    try {
      await api.post("/estoque/entrada", {
        materialId: parseInt(formData.materialId),
        quantidade:
          materialEntradaSelecionado?.tipoControle === "METRAGEM"
            ? null
            : parseInt(formData.quantidade, 10),
        metragem:
          materialEntradaSelecionado?.tipoControle === "METRAGEM"
            ? parseFloat(formData.quantidade)
            : null,
        custoUnitarioEntrada:
          formData.custoUnitarioEntrada === ""
            ? null
            : parseFloat(formData.custoUnitarioEntrada),
        funcionarioId: parseInt(formData.funcionarioId),
        localEstoqueId: Number(formData.localEstoqueId),
      });
      setSuccessMessage("Entrada de material registrada com sucesso!");
      setTimeout(() => setSuccessMessage(null), 4000);
      handleCloseModal();
      fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao registrar entrada."));
      console.error(err);
    }
  };

  const abrirModalOr = (ordemRetirada, acao) => {
    setOrdemRetiradaAtual(ordemRetirada);
    setAcaoOr(acao);
    setOrForm({
      conferidoPor: "",
      levadoPor: "",
      assinaturaConferente: "",
      assinaturaRetirante: "",
      devolvidoPor: "",
      recebidoPor: "",
      assinaturaRecebimento: "",
      devolucoes: Object.fromEntries(
        (ordemRetirada.itens || []).map((item) => [
          item.id,
          item.categoria === "FERRAMENTA"
            ? item.quantidadeRetirada || item.quantidadeSolicitada || 0
            : 0,
        ]),
      ),
      alocacoes: Object.fromEntries(
        (ordemRetirada.itens || [])
          .filter((item) => ["BOBINA", "ROLO"].includes(item.material?.tipoControle))
          .map((item) => [
            item.id,
            [{
              unidadeRastreavelId: "",
              metragem: String(item.quantidadeSolicitada || ""),
              evidenciaFotoBase64: "",
              evidenciaFotoNome: "",
            }],
          ]),
      ),
      devolucoesAlocacao: Object.fromEntries(
        (ordemRetirada.itens || []).flatMap((item) =>
          (item.alocacoes || []).map((alocacao) => [alocacao.id, "0"]),
        ),
      ),
      evidenciasDevolucao: {},
    });
    setShowSaidaModal(true);
  };

  const abrirPrimeiraOrPendente = () => {
    const ordem = ordensRetirada.find((or) => or.status === "GERADA");
    if (!ordem) {
      alert("Não há OR gerada pendente de retirada.");
      return;
    }
    abrirModalOr(ordem, "retirada");
  };

  const handleSubmitOr = async (e) => {
    e.preventDefault();
    if (!ordemRetiradaAtual) return;

    try {
      if (acaoOr === "retirada") {
        if (!orForm.assinaturaConferente || !orForm.assinaturaRetirante) {
          alert("As duas assinaturas desenhadas são obrigatórias para executar a OR.");
          return;
        }
        await api.patch(`/ordens-retirada/${ordemRetiradaAtual.id}/executar`, {
          conferidoPor: orForm.conferidoPor,
          levadoPor: orForm.levadoPor,
          assinaturaConferenteBase64: orForm.assinaturaConferente,
          assinaturaRetiranteBase64: orForm.assinaturaRetirante,
          alocacoes: Object.entries(orForm.alocacoes).flatMap(([itemId, alocacoes]) =>
            alocacoes.map((alocacao) => ({
              itemId: Number(itemId),
              unidadeRastreavelId: Number(alocacao.unidadeRastreavelId),
              metragem: parseFloat(alocacao.metragem),
              evidenciaFotoBase64: alocacao.evidenciaFotoBase64,
              evidenciaFotoNome: alocacao.evidenciaFotoNome,
            })),
          ),
        });
        setSuccessMessage("Retirada por OR executada e assinada com sucesso!");
      } else {
        if (!orForm.assinaturaRecebimento) {
          alert("A assinatura desenhada de recebimento é obrigatória para devolver a OR.");
          return;
        }
        await api.patch(`/ordens-retirada/${ordemRetiradaAtual.id}/devolver`, {
          devolvidoPor: orForm.devolvidoPor,
          recebidoPor: orForm.recebidoPor,
          assinaturaRecebimentoBase64: orForm.assinaturaRecebimento,
          itens: (ordemRetiradaAtual.itens || []).map((item) => ({
            itemId: item.id,
            quantidadeDevolvida: parseFloat(orForm.devolucoes[item.id] || 0),
          })),
          alocacoes: Object.entries(orForm.devolucoesAlocacao).map(
            ([alocacaoId, metragemDevolvida]) => ({
              alocacaoId: Number(alocacaoId),
              metragemDevolvida: parseFloat(metragemDevolvida || 0),
              evidenciaFotoBase64:
                orForm.evidenciasDevolucao[alocacaoId]?.base64 || "",
              evidenciaFotoNome:
                orForm.evidenciasDevolucao[alocacaoId]?.nome || "",
            }),
          ),
        });
        setSuccessMessage("Devolução da OR registrada com sucesso!");
      }
      setTimeout(() => setSuccessMessage(null), 4000);
      handleCloseModal();
      fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao processar OR."));
      console.error(err);
    }
  };

  const caboLegadoEmBobinas = (material) =>
    ehCaboEmBobina305(material?.nome)
    && material?.tipoControle === "FRACIONADO";
  const getReservado = (material) => {
    const reservado = ["FRACIONADO", "METRAGEM", "BOBINA", "ROLO"]
      .includes(material?.tipoControle)
      ? Number(material.metragemReservada ?? 0)
      : Number(material.quantidadeReservada ?? 0);
    return caboLegadoEmBobinas(material)
      ? quantidadePlanilhaParaEstoque(material.nome, reservado)
      : reservado;
  };

  const abrirEdicaoRetiradaHistorica = (retirada) => {
    setRetiradaHistoricaEdicao({
      ...retirada,
      importacaoId: importacaoDetalhe?.importacaoId,
      quantidadeRetirada: String(retirada.quantidadeRetirada ?? ""),
      dataRetirada: retirada.dataRetirada || "",
      motivo: "",
    });
  };

  const salvarEdicaoRetiradaHistorica = async (event) => {
    event.preventDefault();
    const retirada = retiradaHistoricaEdicao;
    if (!retirada?.retiradaImportadaId) return;
    try {
      setRetiradaHistoricaSalvando(true);
      setError(null);
      await api.patch(
        `/estoque/importacoes/planilha/retiradas/${retirada.retiradaImportadaId}`,
        {
          quantidadeRetirada: Number(retirada.quantidadeRetirada),
          dataRetirada: retirada.dataRetirada || null,
          motivo: retirada.motivo,
        },
      );
      const importacaoId = retirada.importacaoId;
      setRetiradaHistoricaEdicao(null);
      setSuccessMessage(
        "Retirada histórica corrigida. A OR e o consolidado da obra foram atualizados sem movimentar o estoque atual.",
      );
      await fetchData();
      if (importacaoId) await abrirDetalheImportacao(importacaoId);
    } catch (err) {
      setError(getApiErrorMessage(err, "Não foi possível corrigir a retirada histórica."));
    } finally {
      setRetiradaHistoricaSalvando(false);
    }
  };

  const selecionarPlanilhaSaldos = async (event) => {
    const arquivo = event.target.files?.[0];
    event.target.value = "";
    if (!arquivo) return;
    if (!arquivo.name.toLowerCase().endsWith(".xlsx")) {
      setError("Selecione uma planilha no formato .xlsx.");
      return;
    }
    if (arquivo.size > 10 * 1024 * 1024) {
      setError("A planilha excede o limite de 10 MB.");
      return;
    }

    try {
      setImportacaoProcessando(true);
      setError(null);
      setReconciliacaoPreview(null);
      const buffer = await arquivo.arrayBuffer();
      const ExcelJS = (await import("exceljs")).default;
      const workbook = new ExcelJS.Workbook();
      await workbook.xlsx.load(buffer);
      const sincronizacao = extrairSincronizacaoSaldos(workbook, materiais);
      if (!sincronizacao) {
        throw new Error("Não foi encontrada a tabela consolidada da aba ESTOQUE ATUAL.");
      }
      const digest = await crypto.subtle.digest("SHA-256", buffer);
      const hashSha256 = Array.from(new Uint8Array(digest))
        .map((byte) => byte.toString(16).padStart(2, "0"))
        .join("");
      const depositoPadrao = locaisEstoque.find(
        (local) => normalizarTextoPlanilha(local.nome) === "estoque principal",
      ) || locaisEstoque.find((local) => local.ativo !== false);
      const idsSincronizados = new Set(
        sincronizacao.itens.filter((item) => item.materialId).map((item) => item.materialId),
      );
      const valorTotalAnterior = materiais.reduce(
        (total, material) => total + valorTotalMaterial(material),
        0,
      );
      const valorTotal = sincronizacao.itens.reduce(
        (total, item) => total + item.valorTotal,
        0,
      ) + materiais
        .filter((material) => !idsSincronizados.has(material.id))
        .reduce((total, material) => total + valorTotalMaterial(material), 0);

      setImportacaoLocalId(depositoPadrao?.id ? String(depositoPadrao.id) : "");
      setImportacaoContratoId("");
      setImportacaoResponsavelId("");
      setImportacaoPreview({
        modo: "SALDOS",
        nomeArquivo: arquivo.name,
        hashSha256,
        abaOrigem: sincronizacao.abaOrigem,
        abaCatalogo: null,
        abasRetiradas: [],
        retornos: [],
        simulacao: [],
        entradas: [],
        estoqueMinimoPadrao: null,
        itens: sincronizacao.itens,
        avisos: sincronizacao.avisos,
        valorTotal,
        valorTotalAnterior,
      });
    } catch (err) {
      setError(err.message || "Não foi possível ler os saldos da planilha.");
    } finally {
      setImportacaoProcessando(false);
    }
  };
  const controlaMetragem = (material) =>
    ["FRACIONADO", "METRAGEM", "BOBINA", "ROLO"].includes(material?.tipoControle);
  const getSaldoEmEstoque = (material) => {
    const saldo = controlaMetragem(material)
      ? Number(material.metragemDisponivel ?? 0)
      : Number(material.quantidadeDisponivel ?? 0);
    return caboLegadoEmBobinas(material)
      ? quantidadePlanilhaParaEstoque(material.nome, saldo)
      : saldo;
  };
  const getLivre = (material) => Math.max(
    0,
    getSaldoEmEstoque(material) - getReservado(material),
  );
  const isCriticalStock = (material) =>
    getLivre(material) <= Number(material.estoqueMinimo ?? 0);
  const formatarNumero = (valor) =>
    Number(valor || 0).toLocaleString("pt-BR", { maximumFractionDigits: 3 });
  const formatarMoeda = (valor) =>
    new Intl.NumberFormat("pt-BR", {
      style: "currency",
      currency: "BRL",
      minimumFractionDigits: 2,
    }).format(Number(valor) || 0);
  const valorTotalMaterial = (material) =>
    Number(
      material?.valorTotalEstoque ??
      (controlaMetragem(material)
        ? Number(material?.metragemDisponivel || 0)
        : Number(material?.quantidadeDisponivel || 0)) * Number(material?.custoMedio || 0),
    );
  const unidadeMaterial = (material) =>
    ehCaboEmBobina305(material?.nome)
      ? "m"
      : material?.tipoControle === "FRACIONADO"
      ? UNIDADES_MEDIDA.find((item) => item.value === material.unidadeMedida)?.label || "un"
      : controlaMetragem(material)
      ? "m"
      : UNIDADES_MEDIDA.find((item) => item.value === material.unidadeMedida)?.label || "un";
  const getCustoUnitarioExibido = (material) => caboLegadoEmBobinas(material)
    ? custoPlanilhaParaEstoque(material.nome, material.custoMedio)
    : Number(material?.custoMedio || 0);
  const getSaldoLocalExibido = (material, saldo) => caboLegadoEmBobinas(material)
    ? quantidadePlanilhaParaEstoque(material.nome, saldoLocalValor(saldo))
    : saldoLocalValor(saldo);
  const rastreavel = (material) => ["BOBINA", "ROLO"].includes(material?.tipoControle);
  const materiaisRastreaveis = materiais.filter((material) => rastreavel(material));
  const unidadesDisponiveis = (materialId) =>
    unidadesRastreaveis.filter(
      (unidade) =>
        String(unidade.material?.id) === String(materialId) && Number(unidade.metragemAtual || 0) > 0,
    );

  const atualizarAlocacao = (itemId, index, campo, valor) => {
    setOrForm((prev) => ({
      ...prev,
      alocacoes: {
        ...prev.alocacoes,
        [itemId]: (prev.alocacoes[itemId] || []).map((alocacao, indice) =>
          indice === index ? { ...alocacao, [campo]: valor } : alocacao,
        ),
      },
    }));
  };

  const adicionarAlocacao = (itemId) => {
    setOrForm((prev) => ({
      ...prev,
      alocacoes: {
        ...prev.alocacoes,
        [itemId]: [
          ...(prev.alocacoes[itemId] || []),
          {
            unidadeRastreavelId: "",
            metragem: "",
            evidenciaFotoBase64: "",
            evidenciaFotoNome: "",
          },
        ],
      },
    }));
  };

  const selecionarEvidenciaRetirada = async (itemId, index, arquivo) => {
    try {
      const evidencia = await lerImagemComoDataUrl(arquivo);
      atualizarAlocacao(itemId, index, "evidenciaFotoBase64", evidencia.base64);
      atualizarAlocacao(itemId, index, "evidenciaFotoNome", evidencia.nome);
    } catch (err) {
      setError(err.message);
    }
  };

  const selecionarEvidenciaDevolucao = async (alocacaoId, arquivo) => {
    try {
      const evidencia = await lerImagemComoDataUrl(arquivo);
      setOrForm((prev) => ({
        ...prev,
        evidenciasDevolucao: {
          ...prev.evidenciasDevolucao,
          [alocacaoId]: evidencia,
        },
      }));
    } catch (err) {
      setError(err.message);
    }
  };

  const removerAlocacao = (itemId, index) => {
    setOrForm((prev) => ({
      ...prev,
      alocacoes: {
        ...prev.alocacoes,
        [itemId]: (prev.alocacoes[itemId] || []).filter((_, indice) => indice !== index),
      },
    }));
  };

  const cadastrarUnidadeRastreavel = async (event) => {
    event.preventDefault();
    try {
      await api.post("/estoque/unidades-rastreaveis", {
        materialId: Number(unidadeForm.materialId),
        codigo: unidadeForm.codigo,
        metragemInicial: parseFloat(unidadeForm.metragemInicial),
        observacao: unidadeForm.observacao,
        localEstoqueId: Number(unidadeForm.localEstoqueId),
      });
      setSuccessMessage("Bobina/rolo cadastrado com sucesso!");
      handleCloseModal();
      fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao cadastrar bobina/rolo."));
    }
  };

  const abrirAjuste = (material) => {
    setMaterialOperacao(material);
    const primeiroSaldo = saldosLocais.find((saldo) => saldo.material?.id === material.id);
    setAjusteForm({
      tipo: "AJUSTE_POSITIVO",
      localEstoqueId: primeiroSaldo?.localEstoque?.id ? String(primeiroSaldo.localEstoque.id) : "",
      valor: "",
      motivo: "",
      lancadoPor: "",
      autorizadoPor: "",
    });
    setShowAjusteModal(true);
  };

  const abrirTransferencia = (material) => {
    setMaterialOperacao(material);
    const primeiroSaldo = saldosLocais.find((saldo) => saldo.material?.id === material.id);
    setTransferenciaForm({
      origemId: primeiroSaldo?.localEstoque?.id ? String(primeiroSaldo.localEstoque.id) : "",
      destinoId: "",
      valor: "",
      motivo: "",
      lancadoPor: "",
      autorizadoPor: "",
    });
    setShowTransferenciaModal(true);
  };

  const registrarAjuste = async (event) => {
    event.preventDefault();
    try {
      await api.post("/estoque/ajustes", {
        materialId: materialOperacao.id,
        localEstoqueId: Number(ajusteForm.localEstoqueId),
        tipo: ajusteForm.tipo,
        valor: parseFloat(ajusteForm.valor),
        motivo: ajusteForm.motivo,
        lancadoPor: ajusteForm.lancadoPor,
        autorizadoPor: ajusteForm.autorizadoPor,
      });
      setSuccessMessage("Ajuste registrado no livro de movimentações.");
      handleCloseModal();
      fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao registrar ajuste."));
    }
  };

  const registrarTransferencia = async (event) => {
    event.preventDefault();
    try {
      await api.post("/estoque/transferencias", {
        materialId: materialOperacao.id,
        origemId: Number(transferenciaForm.origemId),
        destinoId: Number(transferenciaForm.destinoId),
        valor: parseFloat(transferenciaForm.valor),
        motivo: transferenciaForm.motivo,
        lancadoPor: transferenciaForm.lancadoPor,
        autorizadoPor: transferenciaForm.autorizadoPor,
      });
      setSuccessMessage("Transferência registrada no livro de movimentações.");
      handleCloseModal();
      fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao transferir material."));
    }
  };

  const cadastrarLocalEstoque = async (event) => {
    event.preventDefault();
    try {
      await api.post("/estoque/locais", localForm);
      setSuccessMessage("Depósito cadastrado com sucesso.");
      handleCloseModal();
      fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao cadastrar depósito."));
    }
  };

  const abrirTransferenciaUnidade = (unidade) => {
    setUnidadeOperacao(unidade);
    setTransferenciaUnidadeForm({ destinoId: "", motivo: "", lancadoPor: "", autorizadoPor: "" });
    setShowTransferenciaUnidadeModal(true);
  };

  const transferirUnidadeRastreavel = async (event) => {
    event.preventDefault();
    try {
      await api.patch(`/estoque/unidades-rastreaveis/${unidadeOperacao.id}/transferir`, {
        destinoId: Number(transferenciaUnidadeForm.destinoId),
        motivo: transferenciaUnidadeForm.motivo,
        lancadoPor: transferenciaUnidadeForm.lancadoPor,
        autorizadoPor: transferenciaUnidadeForm.autorizadoPor,
      });
      setSuccessMessage("Bobina/rolo transferido com rastreabilidade completa.");
      handleCloseModal();
      fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao transferir bobina/rolo."));
    }
  };
  const materialEntradaSelecionado = materiais.find(
    (material) => String(material.id) === String(formData.materialId),
  );
  const saldosDoMaterial = (materialId) =>
    saldosLocais.filter((saldo) => String(saldo.material?.id) === String(materialId));
  const saldoLocalValor = (saldo) =>
    controlaMetragem(saldo?.material)
      ? Number(saldo?.metragemDisponivel || 0)
      : Number(saldo?.quantidadeDisponivel || 0);
  const saldoLocalLivre = (saldo) =>
    controlaMetragem(saldo?.material)
      ? Number(saldo?.metragemDisponivel || 0) - Number(saldo?.metragemReservada || 0)
      : Number(saldo?.quantidadeDisponivel || 0) - Number(saldo?.quantidadeReservada || 0);
  const minimoDoSaldo = (saldo) =>
    Number(saldo?.estoqueMinimo ?? saldo?.material?.estoqueMinimo ?? 0);

  const abrirMinimosLocais = () => {
    const primeiroSaldo = saldosLocais[0];
    setMinimoLocalForm({
      saldoId: primeiroSaldo?.id ? String(primeiroSaldo.id) : "",
      estoqueMinimo: primeiroSaldo?.estoqueMinimo == null ? "" : String(primeiroSaldo.estoqueMinimo),
    });
    setShowMinimoLocalModal(true);
  };

  const selecionarSaldoMinimo = (saldoId) => {
    const saldo = saldosLocais.find((item) => String(item.id) === String(saldoId));
    setMinimoLocalForm({
      saldoId,
      estoqueMinimo: saldo?.estoqueMinimo == null ? "" : String(saldo.estoqueMinimo),
    });
  };

  const salvarMinimoLocal = async (event) => {
    event.preventDefault();
    try {
      await api.patch(`/estoque/saldos-locais/${minimoLocalForm.saldoId}/estoque-minimo`, {
        estoqueMinimo: minimoLocalForm.estoqueMinimo === "" ? null : Number(minimoLocalForm.estoqueMinimo),
      });
      setSuccessMessage("Estoque mínimo do depósito atualizado.");
      handleCloseModal();
      await fetchData();
    } catch (err) {
      setError(getApiErrorMessage(err, "Não foi possível atualizar o estoque mínimo local."));
    }
  };
  const getCategoriaMaterialLabel = (categoria) =>
    CATEGORIAS_MATERIAL.find((item) => item.value === categoria)?.label ||
    "Materiais de Consumo";
  const faltasRegistradasPorMaterial = useMemo(
    () => retiradasImportadas.reduce((mapa, retirada) => {
      const chave = String(retirada.materialId || "");
      if (!chave) return mapa;
      mapa.set(
        chave,
        (mapa.get(chave) || 0) + Number(retirada.quantidadeFaltante || 0),
      );
      return mapa;
    }, new Map()),
    [retiradasImportadas],
  );
  const getFaltaRegistrada = (material) =>
    faltasRegistradasPorMaterial.get(String(material?.id)) || 0;
  const retiradasHistoricasFiltradas = useMemo(() => {
    const termo = normalizarTextoPlanilha(retiradaHistoricaBusca);
    return retiradasImportadas
      .filter((retirada) => {
        const texto = normalizarTextoPlanilha([
          retirada.numeroOr,
          retirada.numeroOs,
          retirada.comarca,
          retirada.aba,
          retirada.material,
        ].filter(Boolean).join(" "));
        const falta = Number(retirada.quantidadeFaltante || 0);
        if (termo && !texto.includes(termo)) return false;
        if (retiradaHistoricaSituacao === "COM_FALTA" && falta <= 0) return false;
        if (retiradaHistoricaSituacao === "SEM_FALTA" && falta > 0) return false;
        return true;
      })
      .sort((a, b) => {
        const dataA = a.dataRetirada ? new Date(`${a.dataRetirada}T00:00:00`).getTime() : 0;
        const dataB = b.dataRetirada ? new Date(`${b.dataRetirada}T00:00:00`).getTime() : 0;
        return dataB - dataA || String(a.numeroOr || a.aba).localeCompare(String(b.numeroOr || b.aba), "pt-BR");
      });
  }, [retiradasImportadas, retiradaHistoricaBusca, retiradaHistoricaSituacao]);
  const numeroFiltro = (valor) => {
    if (valor === "") return null;
    const numero = Number(valor);
    return Number.isFinite(numero) ? numero : null;
  };
  const materiaisFiltrados = useMemo(() => {
    const termo = normalizarTextoPlanilha(estoqueBusca);
    const disponivelMinimo = numeroFiltro(estoqueDisponivelMinimo);
    const disponivelMaximo = numeroFiltro(estoqueDisponivelMaximo);
    const valorMinimo = numeroFiltro(estoqueValorMinimo);
    const valorMaximo = numeroFiltro(estoqueValorMaximo);
    const faltaMinima = numeroFiltro(estoqueFaltaMinima);
    const resultado = materiais.filter((material) => {
      const textoMaterial = normalizarTextoPlanilha([
        material.nome,
        material.partNumber,
        material.fabricante,
        material.descricao,
      ].filter(Boolean).join(" "));
      const disponivel = getLivre(material);
      const valorTotal = valorTotalMaterial(material);
      const falta = getFaltaRegistrada(material);
      const categoria = material.categoria || "MATERIAL_CONSUMO";
      if (termo && !textoMaterial.includes(termo)) return false;
      if (estoqueCategoriaFiltro && categoria !== estoqueCategoriaFiltro) return false;
      if (estoqueSituacaoFiltro === "COM_ESTOQUE" && disponivel <= 0) return false;
      if (estoqueSituacaoFiltro === "SEM_ESTOQUE" && disponivel > 0) return false;
      if (estoqueSituacaoFiltro === "ABAIXO_MINIMO" && !isCriticalStock(material)) return false;
      if (estoqueSituacaoFiltro === "EM_FALTA" && falta <= 0) return false;
      if (estoqueSituacaoFiltro === "SEM_FALTA" && falta > 0) return false;
      if (disponivelMinimo != null && disponivel < disponivelMinimo) return false;
      if (disponivelMaximo != null && disponivel > disponivelMaximo) return false;
      if (valorMinimo != null && valorTotal < valorMinimo) return false;
      if (valorMaximo != null && valorTotal > valorMaximo) return false;
      if (faltaMinima != null && falta < faltaMinima) return false;
      return true;
    });
    return resultado.sort((materialA, materialB) => {
      if (estoqueOrdenacao === "NOME_DESC") {
        return materialB.nome.localeCompare(materialA.nome, "pt-BR");
      }
      if (estoqueOrdenacao === "DISPONIVEL_ASC") {
        return getLivre(materialA) - getLivre(materialB);
      }
      if (estoqueOrdenacao === "DISPONIVEL_DESC") {
        return getLivre(materialB) - getLivre(materialA);
      }
      if (estoqueOrdenacao === "VALOR_ASC") {
        return valorTotalMaterial(materialA) - valorTotalMaterial(materialB);
      }
      if (estoqueOrdenacao === "VALOR_DESC") {
        return valorTotalMaterial(materialB) - valorTotalMaterial(materialA);
      }
      if (estoqueOrdenacao === "FALTA_DESC") {
        return getFaltaRegistrada(materialB) - getFaltaRegistrada(materialA);
      }
      return materialA.nome.localeCompare(materialB.nome, "pt-BR");
    });
  }, [
    materiais,
    faltasRegistradasPorMaterial,
    estoqueBusca,
    estoqueCategoriaFiltro,
    estoqueSituacaoFiltro,
    estoqueDisponivelMinimo,
    estoqueDisponivelMaximo,
    estoqueValorMinimo,
    estoqueValorMaximo,
    estoqueFaltaMinima,
    estoqueOrdenacao,
  ]);
  const materiaisPorCategoria = CATEGORIAS_MATERIAL.map((categoria) => ({
    ...categoria,
    materiais: materiaisFiltrados.filter(
      (material) => (material.categoria || "MATERIAL_CONSUMO") === categoria.value,
    ),
  }));
  const filtrosEstoqueAtivos = Boolean(
    estoqueBusca || estoqueCategoriaFiltro || estoqueSituacaoFiltro
    || estoqueDisponivelMinimo || estoqueDisponivelMaximo
    || estoqueValorMinimo || estoqueValorMaximo || estoqueFaltaMinima
    || estoqueOrdenacao !== "NOME_ASC",
  );
  const limparFiltrosEstoque = () => {
    setEstoqueBusca("");
    setEstoqueCategoriaFiltro("");
    setEstoqueSituacaoFiltro("");
    setEstoqueDisponivelMinimo("");
    setEstoqueDisponivelMaximo("");
    setEstoqueValorMinimo("");
    setEstoqueValorMaximo("");
    setEstoqueFaltaMinima("");
    setEstoqueOrdenacao("NOME_ASC");
  };
  const valorMovimentacao = (mov) =>
    mov.metragem != null ? `${formatarNumero(mov.metragem)} m` : `${mov.quantidade ?? 0} un`;
  const historicoFiltrado = historico.filter((mov) => {
    const termo = historicoFiltro.trim().toLowerCase();
    const correspondeTipo = !historicoTipoFiltro || mov.tipo === historicoTipoFiltro;
    const correspondeComarca =
      !historicoComarcaFiltro ||
      String(mov.comarca?.id || "") === String(historicoComarcaFiltro);
    const correspondeProjeto =
      !historicoProjetoFiltro ||
      String(mov.projeto?.id || "") === String(historicoProjetoFiltro);
    const correspondeMaterial =
      !historicoMaterialFiltro ||
      String(mov.material?.id || "") === String(historicoMaterialFiltro);
    const dataMovimentacao = mov.dataMovimentacao ? new Date(mov.dataMovimentacao) : null;
    const correspondeInicio =
      !historicoDataInicio ||
      (dataMovimentacao && dataMovimentacao >= new Date(`${historicoDataInicio}T00:00:00`));
    const correspondeFim =
      !historicoDataFim ||
      (dataMovimentacao && dataMovimentacao <= new Date(`${historicoDataFim}T23:59:59`));
    const pessoasMovimentacao = [
      mov.lancadoPor,
      mov.retiradoPor,
      mov.autorizadoPor,
      mov.funcionario?.nome,
    ].filter(Boolean);
    const correspondePessoa =
      !historicoPessoaFiltro || pessoasMovimentacao.includes(historicoPessoaFiltro);
    const correspondeTexto =
      !termo ||
      [
      mov.tipo,
      mov.material?.nome,
      mov.material?.partNumber,
      mov.funcionario?.nome,
      mov.projeto?.id ? `Projeto ${mov.projeto.id}` : null,
      mov.ordemServico?.numeroOs,
      mov.comarca?.nomeComarca,
      mov.observacao,
      mov.motivo,
      mov.lancadoPor,
      mov.autorizadoPor,
      mov.retiradoPor,
      mov.unidadeRastreavel?.codigo,
      ]
        .filter(Boolean)
        .some((valor) => String(valor).toLowerCase().includes(termo));

    return correspondeTipo && correspondeComarca && correspondeProjeto && correspondeMaterial
      && correspondeInicio && correspondeFim && correspondePessoa && correspondeTexto;
  });

  const exportarHistoricoXlsx = async () => {
    const ExcelJS = (await import("exceljs")).default;
    const workbook = new ExcelJS.Workbook();
    workbook.creator = "RC Operations Hub";
    workbook.created = new Date();
    const worksheet = workbook.addWorksheet("Movimentações", {
      views: [{ state: "frozen", ySplit: 1 }],
    });
    worksheet.columns = [
      ["ID", "id"], ["Data", "data"], ["Hora", "hora"], ["Tipo", "tipo"],
      ["Código do material", "codigoMaterial"], ["Descrição do material", "material"],
      ["Categoria", "categoria"], ["Quantidade", "quantidade"], ["Unidade", "unidade"],
      ["Metragem", "metragem"], ["Bobina ou rolo", "unidadeRastreavel"],
      ["Saldo anterior", "saldoAnterior"], ["Saldo posterior", "saldoPosterior"],
      ["Custo unitário", "custoUnitario"], ["Valor total da movimentação", "valorTotalMovimentacao"],
      ["OS", "os"], ["OR", "or"], ["Autorizado por", "autorizadoPor"],
      ["Retirado por", "retiradoPor"], ["Adicionado/Lançado por", "lancadoPor"],
      ["Origem", "origem"], ["Destino", "destino"], ["Motivo", "motivo"],
      ["Observações", "observacoes"],
    ].map(([header, key]) => ({ header, key, width: 12 }));

    historicoFiltrado.forEach((mov) => {
      const data = mov.dataMovimentacao ? new Date(mov.dataMovimentacao) : null;
      worksheet.addRow({
        id: mov.id,
        data: data?.toLocaleDateString("pt-BR"),
        hora: data?.toLocaleTimeString("pt-BR"),
        tipo: mov.tipo,
        codigoMaterial: mov.material?.partNumber,
        material: mov.material?.nome,
        categoria: mov.material?.categoria,
        quantidade: mov.quantidade,
        unidade: mov.unidadeMedida,
        metragem: mov.metragem,
        unidadeRastreavel: mov.unidadeRastreavel?.codigo,
        saldoAnterior: mov.saldoAnterior,
        saldoPosterior: mov.saldoPosterior,
        custoUnitario: Number(mov.custoUnitario || 0),
        valorTotalMovimentacao: Number(mov.valorTotalMovimentacao || 0),
        os: mov.ordemServico?.numeroOs,
        or: mov.ordemRetirada?.numeroOr,
        autorizadoPor: mov.autorizadoPor,
        retiradoPor: mov.retiradoPor,
        lancadoPor: mov.lancadoPor || mov.funcionario?.nome,
        origem: mov.estoqueOrigem,
        destino: mov.estoqueDestino,
        motivo: mov.motivo,
        observacoes: mov.observacao,
      });
    });

    worksheet.getRow(1).eachCell((cell) => {
      cell.font = { bold: true, color: { argb: "FFFFFFFF" } };
      cell.fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FF1E293B" } };
      cell.alignment = { vertical: "middle", horizontal: "center", wrapText: true };
    });
    worksheet.getRow(1).height = 30;
    worksheet.autoFilter = {
      from: "A1",
      to: `${worksheet.getColumn(worksheet.columnCount).letter}1`,
    };
    worksheet.getColumn("custoUnitario").numFmt = "R$ #,##0.0000";
    worksheet.getColumn("valorTotalMovimentacao").numFmt = "R$ #,##0.00";

    worksheet.columns.forEach((column) => {
      let maior = String(column.header || "").length;
      column.eachCell({ includeEmpty: true }, (cell, rowNumber) => {
        if (rowNumber > 1) maior = Math.max(maior, String(cell.value ?? "").length);
        cell.alignment = { vertical: "top", wrapText: true };
      });
      column.width = Math.min(45, Math.max(10, maior + 2));
    });
    worksheet.eachRow((row, rowNumber) => {
      if (rowNumber === 1) return;
      let linhas = 1;
      row.eachCell({ includeEmpty: true }, (cell, columnNumber) => {
        const largura = worksheet.getColumn(columnNumber).width || 12;
        linhas = Math.max(linhas, Math.ceil(String(cell.value ?? "").length / largura));
      });
      row.height = Math.min(90, Math.max(20, linhas * 15));
    });

    const estoqueSheet = workbook.addWorksheet("Estoque Atual", {
      views: [{ state: "frozen", ySplit: 1 }],
    });
    estoqueSheet.columns = [
      { header: "Código", key: "codigo", width: 20 },
      { header: "Produto", key: "produto", width: 34 },
      { header: "Categoria", key: "categoria", width: 22 },
      { header: "Saldo atual", key: "saldo", width: 16 },
      { header: "Unidade", key: "unidade", width: 13 },
      { header: "Custo médio unitário", key: "custoMedio", width: 22 },
      { header: "Valor em estoque", key: "valorTotal", width: 20 },
    ];
    materiais.forEach((material) => {
      const saldo = controlaMetragem(material)
        ? Number(material.metragemDisponivel || 0)
        : Number(material.quantidadeDisponivel || 0);
      const row = estoqueSheet.addRow({
        codigo: material.partNumber,
        produto: material.nome,
        categoria: getCategoriaMaterialLabel(material.categoria),
        saldo,
        unidade: controlaMetragem(material) ? "m" : unidadeMaterial(material),
        custoMedio: Number(material.custoMedio || 0),
      });
      row.getCell("valorTotal").value = {
        formula: `D${row.number}*F${row.number}`,
        result: valorTotalMaterial(material),
      };
    });
    const totalRow = estoqueSheet.addRow({
      produto: "VALOR TOTAL DO ESTOQUE",
    });
    totalRow.getCell("valorTotal").value = materiais.length
      ? {
        formula: `SUM(G2:G${totalRow.number - 1})`,
        result: valorTotalEstoque,
      }
      : 0;
    totalRow.font = { bold: true };
    totalRow.fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FFDDEAFE" } };
    estoqueSheet.getColumn("saldo").numFmt = "#,##0.000";
    estoqueSheet.getColumn("custoMedio").numFmt = 'R$ #,##0.0000';
    estoqueSheet.getColumn("valorTotal").numFmt = 'R$ #,##0.00';
    estoqueSheet.autoFilter = { from: "A1", to: "G1" };

    const consumoSheet = workbook.addWorksheet("Consumo por OS-OR");
    consumoSheet.columns = [
      { header: "OS", key: "os", width: 24 },
      { header: "OR", key: "or", width: 28 },
      { header: "Material", key: "material", width: 32 },
      { header: "Consumo líquido", key: "consumo", width: 18 },
      { header: "Unidade", key: "unidade", width: 12 },
    ];
    relatorioConsumo.forEach((item) => consumoSheet.addRow(item));

    const alertasSheet = workbook.addWorksheet("Alertas por Depósito");
    alertasSheet.columns = [
      { header: "Depósito", key: "deposito", width: 26 },
      { header: "Material", key: "material", width: 34 },
      { header: "Saldo", key: "saldo", width: 14 },
      { header: "Estoque mínimo", key: "minimo", width: 16 },
      { header: "Unidade", key: "unidade", width: 12 },
    ];
    alertasReposicao.forEach((item) => alertasSheet.addRow(item));

    [estoqueSheet, consumoSheet, alertasSheet].forEach((sheet) => {
      sheet.autoFilter = { from: "A1", to: sheet === consumoSheet ? "E1" : "E1" };
      if (sheet === estoqueSheet) {
        sheet.autoFilter = { from: "A1", to: "G1" };
      }
      sheet.views = [{ state: "frozen", ySplit: 1 }];
      sheet.getRow(1).height = 28;
      sheet.getRow(1).eachCell((cell) => {
        cell.font = { bold: true, color: { argb: "FFFFFFFF" } };
        cell.fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FF1E293B" } };
        cell.alignment = { vertical: "middle", horizontal: "center", wrapText: true };
      });
      sheet.eachRow((row, rowNumber) => {
        if (rowNumber > 1) row.height = 22;
        row.eachCell((cell) => {
          cell.alignment = { vertical: "top", wrapText: true };
        });
      });
    });

    const buffer = await workbook.xlsx.writeBuffer();
    const url = URL.createObjectURL(new Blob([buffer], {
      type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `movimentacoes-estoque-${new Date().toISOString().slice(0, 10)}.xlsx`;
    link.click();
    URL.revokeObjectURL(url);
  };
  const tiposHistorico = [...new Set(historico.map((mov) => mov.tipo).filter(Boolean))];
  const projetosHistorico = [
    ...new Map(
      historico
        .filter((mov) => mov.projeto?.id)
        .map((mov) => [mov.projeto.id, mov.projeto]),
    ).values(),
  ];
  const pessoasHistorico = [
    ...new Set(
      historico.flatMap((mov) => [
        mov.lancadoPor,
        mov.retiradoPor,
        mov.autorizadoPor,
        mov.funcionario?.nome,
      ]).filter(Boolean),
    ),
  ].sort((a, b) => a.localeCompare(b, "pt-BR"));
  const relatorioConsumo = [
    ...historicoFiltrado
      .filter((mov) => ["RETIRADA_OR", "DEVOLUCAO_OR", "SAIDA"].includes(mov.tipo))
      .reduce((mapa, mov) => {
        const os = mov.ordemServico?.numeroOs || "Sem OS";
        const or = mov.ordemRetirada?.numeroOr || "Sem OR";
        const material = mov.material?.nome || "Material";
        const chave = `${os}|${or}|${mov.material?.id || material}`;
        const valor = Number(mov.metragem ?? mov.quantidade ?? 0)
          * (mov.tipo === "DEVOLUCAO_OR" ? -1 : 1);
        const atual = mapa.get(chave) || {
          os,
          or,
          material,
          unidade: mov.metragem != null ? "m" : "un",
          consumo: 0,
        };
        atual.consumo += valor;
        mapa.set(chave, atual);
        return mapa;
      }, new Map())
      .values(),
  ].filter((item) => item.consumo !== 0).sort((a, b) => b.consumo - a.consumo);

  const materiaisMaisUtilizados = [
    ...relatorioConsumo.reduce((mapa, item) => {
      const chave = `${item.material}|${item.unidade}`;
      mapa.set(chave, {
        material: item.material,
        unidade: item.unidade,
        consumo: (mapa.get(chave)?.consumo || 0) + item.consumo,
      });
      return mapa;
    }, new Map()).values(),
  ].sort((a, b) => b.consumo - a.consumo).slice(0, 5);

  const alertasReposicao = saldosLocais
    .filter((saldo) => minimoDoSaldo(saldo) > 0 && saldoLocalLivre(saldo) <= minimoDoSaldo(saldo))
    .map((saldo) => ({
      deposito: saldo.localEstoque?.nome || "Depósito",
      material: saldo.material?.nome || "Material",
      saldo: saldoLocalLivre(saldo),
      minimo: minimoDoSaldo(saldo),
      unidade: unidadeMaterial(saldo.material),
    }))
    .sort((a, b) => a.saldo - b.saldo);

  const getMovimentacaoStyle = (tipo) => {
    const styles = {
      ENTRADA: {
        label: "Entrada",
        icon: ArrowDownLeft,
        className: "text-green-600 bg-green-50",
      },
      SAIDA: {
        label: "Saída",
        icon: ArrowUpRight,
        className: "text-blue-600 bg-blue-50",
      },
      RESERVA: {
        label: "Reserva",
        icon: Package,
        className: "text-amber-700 bg-amber-50",
      },
      ESTORNO_RESERVA: {
        label: "Estorno Reserva",
        icon: ArrowDownLeft,
        className: "text-slate-600 bg-slate-100",
      },
      BAIXA: {
        label: "Baixa OS",
        icon: ArrowUpRight,
        className: "text-rose-600 bg-rose-50",
      },
      ESTORNO_BAIXA: {
        label: "Estorno Baixa",
        icon: ArrowDownLeft,
        className: "text-purple-700 bg-purple-50",
      },
      RETIRADA_OR: {
        label: "Retirada OR",
        icon: ArrowUpRight,
        className: "text-blue-700 bg-blue-50",
      },
      DEVOLUCAO_OR: {
        label: "Devolução OR",
        icon: ArrowDownLeft,
        className: "text-emerald-700 bg-emerald-50",
      },
      AJUSTE_POSITIVO: {
        label: "Ajuste positivo",
        icon: Plus,
        className: "text-emerald-700 bg-emerald-50",
      },
      AJUSTE_NEGATIVO: {
        label: "Ajuste negativo",
        icon: Minus,
        className: "text-rose-700 bg-rose-50",
      },
      TRANSFERENCIA: {
        label: "Transferência",
        icon: ArrowRightLeft,
        className: "text-cyan-700 bg-cyan-50",
      },
    };
    return styles[tipo] || {
      label: tipo || "Movimento",
      icon: History,
      className: "text-slate-600 bg-slate-100",
    };
  };

  const getComarcaOptionLabel = (comarca) => {
    const numeroOs = comarca.ordemServico?.numeroOs || "OS não vinculada";
    const projeto = comarca.projeto?.id ? `Projeto #${comarca.projeto.id}` : "Projeto não vinculado";
    return `${numeroOs} - ${comarca.nomeComarca} - ${projeto}`;
  };

  const getReferenciaOperacional = (mov) => {
    const partes = [];
    if (mov.ordemRetirada?.numeroOr) partes.push(mov.ordemRetirada.numeroOr);
    if (mov.ordemServico?.numeroOs) partes.push(mov.ordemServico.numeroOs);
    if (mov.comarca?.nomeComarca) partes.push(mov.comarca.nomeComarca);
    if (mov.projeto?.id) partes.push(`Projeto #${mov.projeto.id}`);
    return partes;
  };

  const estoquePorComarca = useMemo(
    () => consolidarRetiradasPorObra({
      comarcas,
      materiais,
      ordensRetirada,
      retiradasImportadas,
    }),
    [comarcas, materiais, ordensRetirada, retiradasImportadas],
  );
  const estoqueComarcaSelecionada = estoquePorComarca.find(
    (item) => String(item.comarca?.id) === String(abaEstoque),
  );
  const estoqueOrdemSelecionada = estoqueComarcaSelecionada?.ordens?.find(
    (ordem) => String(ordem.id) === String(abaOrdemEstoque),
  );
  const estoqueOperacionalSelecionado = abaOrdemEstoque === "resumo"
    ? estoqueComarcaSelecionada
    : estoqueOrdemSelecionada || estoqueComarcaSelecionada;
  const atalhosOrdensEstoque = estoquePorComarca.flatMap((obra) =>
    obra.ordens.map((ordem) => ({
      ...ordem,
      comarcaId: obra.comarca?.id,
      comarcaNome: obra.comarca?.nomeComarca,
    })),
  );
  const resultadoSimulacao = useMemo(
    () => calcularSimulacaoRetirada(materiais, simulacaoItens),
    [materiais, simulacaoItens],
  );

  const inicioMesAtual = new Date(new Date().getFullYear(), new Date().getMonth(), 1);
  const valorTotalEstoque = materiais.reduce(
    (total, material) => total + valorTotalMaterial(material),
    0,
  );
  const indicadoresEstoque = [
    { label: "Materiais cadastrados", valor: materiais.length },
    { label: "Valor total em estoque", valor: formatarMoeda(valorTotalEstoque) },
    {
      label: "Metragem disponível",
      valor: `${formatarNumero(
        materiais.filter(controlaMetragem).reduce(
          (total, material) => total + getSaldoEmEstoque(material),
          0,
        ),
      )} m`,
    },
    { label: "Abaixo do mínimo", valor: materiais.filter(isCriticalStock).length },
    {
      label: "Materiais em falta",
      valor: materiais.filter((material) => getFaltaRegistrada(material) > 0).length,
    },
    {
      label: "Bobinas/rolos lacrados",
      valor: unidadesRastreaveis.filter((unidade) => unidade.status === "LACRADA").length,
    },
    {
      label: "Parcialmente utilizados",
      valor: unidadesRastreaveis.filter((unidade) =>
        ["PARCIALMENTE_UTILIZADA", "DEVOLVIDA_ESTOQUE"].includes(unidade.status),
      ).length,
    },
    {
      label: "Saídas no mês",
      valor: historico.filter((mov) =>
        ["RETIRADA_OR", "SAIDA", "AJUSTE_NEGATIVO"].includes(mov.tipo)
        && mov.dataMovimentacao
        && new Date(mov.dataMovimentacao) >= inicioMesAtual,
      ).length,
    },
  ];
  const abasRetiradaImportadas = new Set(
    retiradasImportadas.map((retirada) => retirada.aba).filter(Boolean),
  ).size;
  const ordensHistoricasImportadas = ordensRetirada.filter(
    (ordem) => ordem.status === "HISTORICA_IMPORTADA",
  ).length;

  const atualizarLinhaSimulacao = (id, campo, valor) => {
    setSimulacaoItens((itens) => itens.map((item) => (
      item.id === id ? { ...item, [campo]: valor } : item
    )));
  };

  const removerLinhaSimulacao = (id) => {
    setSimulacaoItens((itens) => {
      const restantes = itens.filter((item) => item.id !== id);
      return restantes.length > 0 ? restantes : [novaLinhaSimulacao()];
    });
  };

  if (loading) return <LoadingSpinner />;

  return (
    <div className="estoque-responsive w-full min-w-0 space-y-6 2xl:space-y-8">
      {/* Header */}
      <div>
        <div className="mb-6 flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
          <div>
            <h1 className="text-3xl font-bold text-slate-800">
              Estoque de Materiais
            </h1>
            <p className="text-slate-600 mt-2">
              Gerenciamento de entrada e saída de materiais
            </p>
          </div>
          <div className="estoque-acoes flex flex-wrap gap-2">
            <input
              ref={importacaoInputRef}
              type="file"
              accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              onChange={selecionarPlanilhaEstoque}
              className="hidden"
            />
            <input
              ref={sincronizacaoSaldosInputRef}
              type="file"
              accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              onChange={selecionarPlanilhaSaldos}
              className="hidden"
            />
            <input
              ref={notaFiscalInputRef}
              type="file"
              accept=".xml,.pdf,application/xml,text/xml,application/pdf"
              onChange={selecionarNotaFiscal}
              className="hidden"
            />
            <button
              type="button"
              onClick={() => importacaoInputRef.current?.click()}
              disabled={importacaoProcessando}
              className="flex items-center gap-2 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 font-semibold text-blue-700 transition-colors hover:bg-blue-100 disabled:opacity-50"
              title="Importar inventário de uma planilha Excel"
            >
              <Upload size={18} />
              {importacaoProcessando ? "Lendo..." : "Importar .xlsx"}
            </button>
            <button
              type="button"
              onClick={() => sincronizacaoSaldosInputRef.current?.click()}
              disabled={importacaoProcessando}
              className="flex items-center gap-2 rounded-lg border border-cyan-200 bg-cyan-50 px-4 py-3 font-semibold text-cyan-800 transition-colors hover:bg-cyan-100 disabled:opacity-50"
              title="Conferir e sincronizar somente os saldos da aba ESTOQUE ATUAL"
            >
              <ArrowRightLeft size={18} />
              {importacaoProcessando ? "Lendo..." : "Sincronizar saldos"}
            </button>
            <button
              type="button"
              onClick={() => notaFiscalInputRef.current?.click()}
              disabled={notaFiscalProcessando}
              className="flex items-center gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 font-semibold text-emerald-800 transition-colors hover:bg-emerald-100 disabled:opacity-50"
              title="Cadastrar e dar entrada em materiais a partir de XML ou PDF de nota fiscal"
            >
              <Receipt size={18} />
              {notaFiscalProcessando ? "Analisando..." : "Importar NF"}
            </button>
            <button
              type="button"
              onClick={() => setShowHistoricoImportacoesModal(true)}
              className="flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-4 py-3 font-semibold text-slate-700 transition-colors hover:bg-slate-50"
              title="Consultar arquivos importados e itens afetados"
            >
              <FileClock size={18} />
              Histórico
            </button>
            <button
              type="button"
              onClick={() => setShowSimulacaoModal(true)}
              className="flex items-center gap-2 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 font-semibold text-amber-900 transition-colors hover:bg-amber-100"
              title="Projetar uma retirada sem alterar o estoque"
            >
              <Calculator size={18} />
              Simular retirada
            </button>
            {/*   BOTAO NOVO: Cadastrar no Catálogo */}
            <button
              onClick={abrirModalNovoMaterial}
              className="flex items-center gap-2 bg-slate-700 hover:bg-slate-800 text-white font-semibold py-3 px-4 rounded-lg transition-colors"
            >
              <FolderPlus size={20} /> + Novo Material
            </button>
            <button
              onClick={() => setShowUnidadeRastreavelModal(true)}
              disabled={materiaisRastreaveis.length === 0}
              className="flex items-center gap-2 bg-cyan-700 hover:bg-cyan-800 disabled:bg-slate-300 text-white font-semibold py-3 px-4 rounded-lg transition-colors"
              title="Cadastrar bobina ou rolo individual"
            >
              <Plus size={20} /> Bobina / Rolo
            </button>
            <button
              onClick={() => setShowLocalEstoqueModal(true)}
              className="flex items-center gap-2 bg-slate-600 hover:bg-slate-700 text-white font-semibold py-3 px-4 rounded-lg transition-colors"
            >
              <Plus size={20} /> Depósito
            </button>
            <button
              onClick={() => setShowEntradaModal(true)}
              className="flex items-center gap-2 bg-green-600 hover:bg-green-700 text-white font-semibold py-3 px-4 rounded-lg transition-colors"
            >
              <Plus size={20} /> Entrada de Material
            </button>
            <button
              onClick={abrirPrimeiraOrPendente}
              className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white font-semibold py-3 px-4 rounded-lg transition-colors"
            >
              <Minus size={20} /> Executar OR
            </button>
          </div>
        </div>
        {error && <Alert type="error" message={error} />}
        {successMessage && <Alert type="success" message={successMessage} />}
      </div>

      <div className="grid grid-cols-1 gap-3 min-[420px]:grid-cols-2 lg:grid-cols-4 2xl:grid-cols-7">
        {indicadoresEstoque.map((indicador) => (
          <div key={indicador.label} className="rounded-md border border-slate-200 bg-white p-4 shadow-sm">
            <span className="block text-xs font-semibold text-slate-500">{indicador.label}</span>
            <strong className="mt-1 block text-xl text-slate-800">{indicador.valor}</strong>
          </div>
        ))}
      </div>

      <FilaPendenciasOperacionais area="ESTOQUE" titulo="Retiradas, devoluções e faltas pendentes" limite={8} />

      <div
        role="tablist"
        aria-label="Estoque geral e por obra"
        className="flex gap-1 overflow-x-auto border-b border-slate-200"
      >
        <button
          type="button"
          role="tab"
          aria-selected={abaEstoque === "geral"}
          onClick={() => {
            setAbaEstoque("geral");
            setAbaOrdemEstoque("resumo");
          }}
          className={`shrink-0 border-b-2 px-4 py-3 text-sm font-bold ${
            abaEstoque === "geral"
              ? "border-blue-600 text-blue-700"
              : "border-transparent text-slate-500 hover:text-slate-800"
          }`}
        >
          Estoque geral
        </button>
        {estoquePorComarca.map((item) => (
          <button
            key={item.comarca.id}
            type="button"
            role="tab"
            aria-selected={String(abaEstoque) === String(item.comarca.id)}
            onClick={() => {
              setAbaEstoque(String(item.comarca.id));
              setAbaOrdemEstoque("resumo");
            }}
            className={`shrink-0 border-b-2 px-4 py-3 text-sm font-bold ${
              String(abaEstoque) === String(item.comarca.id)
                ? "border-blue-600 text-blue-700"
                : "border-transparent text-slate-500 hover:text-slate-800"
            }`}
          >
            {item.comarca.nomeComarca}
            <span className="ml-1 font-medium text-slate-400">
              · {item.comarca.ordemServico?.numeroOs || `Obra #${item.comarca.id}`}
            </span>
            {item.ordens.length > 0 && (
              <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-black text-slate-500">
                {item.ordens.length} OR{item.ordens.length > 1 ? "s" : ""}
              </span>
            )}
          </button>
        ))}
        {podeGerenciarEstoque && (
          <button
            type="button"
            role="tab"
            aria-selected={abaEstoque === "removidos"}
            onClick={() => {
              setAbaEstoque("removidos");
              setAbaOrdemEstoque("resumo");
            }}
            className={`shrink-0 border-b-2 px-4 py-3 text-sm font-bold ${
              abaEstoque === "removidos"
                ? "border-rose-600 text-rose-700"
                : "border-transparent text-slate-500 hover:text-slate-800"
            }`}
          >
            Materiais removidos ({materiaisRemovidos.length})
          </button>
        )}
      </div>

      {/* Tabela de Saldo Atual */}
      {abaEstoque === "geral" ? (
      <>
      <section className="border-y border-slate-200 bg-white px-4 py-4 shadow-sm">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-bold text-slate-800">Localizar materiais</h2>
            <p className="mt-0.5 text-xs text-slate-500">
              {materiaisFiltrados.length} de {materiais.length} itens visíveis
            </p>
          </div>
          {filtrosEstoqueAtivos && (
            <button
              type="button"
              onClick={limparFiltrosEstoque}
              className="rounded-md border border-slate-300 bg-white px-3 py-2 text-xs font-bold text-slate-600 hover:bg-slate-50"
            >
              Limpar filtros
            </button>
          )}
        </div>
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
          <label className="sm:col-span-2 xl:col-span-2">
            <span className="mb-1 block text-xs font-semibold text-slate-600">Nome ou identificação</span>
            <span className="relative block">
              <Search size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
              <input
                type="search"
                value={estoqueBusca}
                onChange={(event) => setEstoqueBusca(event.target.value)}
                placeholder="Nome, part number, fabricante..."
                className="w-full rounded-md border border-slate-300 bg-white py-2.5 pl-9 pr-3 text-sm text-slate-800 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
              />
            </span>
          </label>
          <label>
            <span className="mb-1 block text-xs font-semibold text-slate-600">Categoria</span>
            <select
              value={estoqueCategoriaFiltro}
              onChange={(event) => setEstoqueCategoriaFiltro(event.target.value)}
              className="w-full rounded-md border border-slate-300 bg-white px-3 py-2.5 text-sm text-slate-800"
            >
              <option value="">Todas</option>
              {CATEGORIAS_MATERIAL.map((categoria) => (
                <option key={categoria.value} value={categoria.value}>{categoria.label}</option>
              ))}
            </select>
          </label>
          <label>
            <span className="mb-1 block text-xs font-semibold text-slate-600">Situação</span>
            <select
              value={estoqueSituacaoFiltro}
              onChange={(event) => setEstoqueSituacaoFiltro(event.target.value)}
              className="w-full rounded-md border border-slate-300 bg-white px-3 py-2.5 text-sm text-slate-800"
            >
              <option value="">Todas</option>
              <option value="COM_ESTOQUE">Com disponibilidade</option>
              <option value="SEM_ESTOQUE">Sem disponibilidade</option>
              <option value="ABAIXO_MINIMO">Abaixo do mínimo</option>
              <option value="EM_FALTA">Com falta nas ORs</option>
              <option value="SEM_FALTA">Sem falta nas ORs</option>
            </select>
          </label>
          <label>
            <span className="mb-1 block text-xs font-semibold text-slate-600">Ordenar por</span>
            <select
              value={estoqueOrdenacao}
              onChange={(event) => setEstoqueOrdenacao(event.target.value)}
              className="w-full rounded-md border border-slate-300 bg-white px-3 py-2.5 text-sm text-slate-800"
            >
              <option value="NOME_ASC">Nome: A a Z</option>
              <option value="NOME_DESC">Nome: Z a A</option>
              <option value="DISPONIVEL_DESC">Disponível: maior primeiro</option>
              <option value="DISPONIVEL_ASC">Disponível: menor primeiro</option>
              <option value="VALOR_DESC">Valor total: maior primeiro</option>
              <option value="VALOR_ASC">Valor total: menor primeiro</option>
              <option value="FALTA_DESC">Falta: maior primeiro</option>
            </select>
          </label>
        </div>
        <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
          <label>
            <span className="mb-1 block text-xs font-semibold text-slate-600">Disponível mínimo</span>
            <input
              type="number"
              min="0"
              step="any"
              value={estoqueDisponivelMinimo}
              onChange={(event) => setEstoqueDisponivelMinimo(event.target.value)}
              placeholder="0"
              className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800"
            />
          </label>
          <label>
            <span className="mb-1 block text-xs font-semibold text-slate-600">Disponível máximo</span>
            <input
              type="number"
              min="0"
              step="any"
              value={estoqueDisponivelMaximo}
              onChange={(event) => setEstoqueDisponivelMaximo(event.target.value)}
              placeholder="Sem limite"
              className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800"
            />
          </label>
          <label>
            <span className="mb-1 block text-xs font-semibold text-slate-600">Valor mínimo (R$)</span>
            <input
              type="number"
              min="0"
              step="0.01"
              value={estoqueValorMinimo}
              onChange={(event) => setEstoqueValorMinimo(event.target.value)}
              placeholder="0,00"
              className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800"
            />
          </label>
          <label>
            <span className="mb-1 block text-xs font-semibold text-slate-600">Valor máximo (R$)</span>
            <input
              type="number"
              min="0"
              step="0.01"
              value={estoqueValorMaximo}
              onChange={(event) => setEstoqueValorMaximo(event.target.value)}
              placeholder="Sem limite"
              className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800"
            />
          </label>
          <label className="col-span-2 sm:col-span-1">
            <span className="mb-1 block text-xs font-semibold text-slate-600">Falta mínima</span>
            <input
              type="number"
              min="0"
              step="any"
              value={estoqueFaltaMinima}
              onChange={(event) => setEstoqueFaltaMinima(event.target.value)}
              placeholder="0"
              className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800"
            />
          </label>
        </div>
      </section>
      <div className="space-y-5 md:hidden">
        {materiaisPorCategoria.map((grupo) => grupo.materiais.length > 0 && (
          <section key={grupo.value} aria-labelledby={`categoria-${grupo.value}`}>
            <h2
              id={`categoria-${grupo.value}`}
              className="mb-2 text-xs font-black uppercase text-slate-500"
            >
              {grupo.label} ({grupo.materiais.length})
            </h2>
            <div className="space-y-3">
              {grupo.materiais.map((material) => (
                <article
                  key={material.id}
                  className={`rounded-lg border bg-white p-4 shadow-sm ${
                    isCriticalStock(material) ? "border-red-200 bg-red-50/40" : "border-slate-200"
                  }`}
                >
                  <div className="flex items-start gap-3">
                    {material.fotoProdutoUrl ? (
                      <button
                        type="button"
                        onClick={() => setFotoExpandida(material)}
                        className="h-14 w-14 shrink-0 overflow-hidden rounded-md border border-slate-200 bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500"
                        title="Expandir foto do produto"
                      >
                        <img
                          src={material.fotoProdutoUrl}
                          alt={`Foto de ${material.nome}`}
                          className="h-full w-full object-cover"
                        />
                      </button>
                    ) : (
                      <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-md border border-slate-200 bg-slate-50 text-slate-400">
                        <Package size={20} />
                      </span>
                    )}
                    <div className="min-w-0 flex-1">
                      <h3 className="break-words text-sm font-bold text-slate-900">{material.nome}</h3>
                      <p className="mt-1 break-all font-mono text-xs text-slate-500">
                        {material.partNumber || "Sem part number"}
                      </p>
                      {material.descricao && (
                        <p className="mt-1 break-words text-xs text-slate-500">{material.descricao}</p>
                      )}
                    </div>
                  </div>

                  <dl className="mt-4 grid grid-cols-2 gap-2 text-center sm:grid-cols-4">
                    <div className="rounded-md bg-slate-100 p-2">
                      <dt className="text-[10px] font-bold uppercase text-slate-500">Em estoque</dt>
                      <dd className="mt-1 text-xs font-bold text-slate-800">
                        {formatarNumero(getSaldoEmEstoque(material))} {unidadeMaterial(material)}
                      </dd>
                    </div>
                    <div className="rounded-md bg-amber-50 p-2">
                      <dt className="text-[10px] font-bold uppercase text-amber-700">Reservado</dt>
                      <dd className="mt-1 text-xs font-bold text-amber-800">
                        {formatarNumero(getReservado(material))} {unidadeMaterial(material)}
                      </dd>
                    </div>
                    <div className={`rounded-md p-2 ${isCriticalStock(material) ? "bg-red-100" : "bg-green-50"}`}>
                      <dt className={`text-[10px] font-bold uppercase ${isCriticalStock(material) ? "text-red-700" : "text-green-700"}`}>Disponível</dt>
                      <dd className={`mt-1 text-xs font-bold ${isCriticalStock(material) ? "text-red-800" : "text-green-800"}`}>
                        {formatarNumero(getLivre(material))} {unidadeMaterial(material)}
                      </dd>
                    </div>
                    <div className={`rounded-md p-2 ${getFaltaRegistrada(material) > 0 ? "bg-rose-100" : "bg-slate-50"}`}>
                      <dt className={`text-[10px] font-bold uppercase ${getFaltaRegistrada(material) > 0 ? "text-rose-700" : "text-slate-500"}`}>
                        Em falta nas ORs
                      </dt>
                      <dd className={`mt-1 text-xs font-bold ${getFaltaRegistrada(material) > 0 ? "text-rose-800" : "text-slate-600"}`}>
                        {formatarNumero(getFaltaRegistrada(material))} {unidadeMaterial(material)}
                      </dd>
                    </div>
                  </dl>

                  <div className="mt-3 grid grid-cols-2 gap-3 border-t border-slate-100 pt-3 text-xs">
                    <div>
                      <span className="block text-slate-400">Custo médio</span>
                      <strong className="text-slate-800">{formatarMoeda(getCustoUnitarioExibido(material))}</strong>
                    </div>
                    <div className="text-right">
                      <span className="block text-slate-400">Valor em estoque</span>
                      <strong className="text-slate-900">{formatarMoeda(valorTotalMaterial(material))}</strong>
                    </div>
                  </div>

                  <div className="mt-3 flex items-center justify-between gap-3 border-t border-slate-100 pt-3">
                    <p className="min-w-0 break-words text-xs text-slate-500">
                      {saldosDoMaterial(material.id).map((saldo) => saldo.localEstoque?.nome).filter(Boolean).join(", ") || "Local não informado"}
                    </p>
                    <div className="flex shrink-0 gap-1">
                      <button type="button" onClick={() => abrirModalEditarMaterial(material)} className="rounded-md border border-slate-200 bg-white p-2 text-slate-500" title="Editar cadastro" aria-label={`Editar ${material.nome}`}><Edit2 size={15} /></button>
                      <button type="button" onClick={() => abrirAjuste(material)} disabled={rastreavel(material)} className="rounded-md border border-slate-200 bg-white p-2 text-slate-500 disabled:opacity-30" title="Ajustar saldo" aria-label={`Ajustar saldo de ${material.nome}`}><SlidersHorizontal size={15} /></button>
                      <button type="button" onClick={() => abrirTransferencia(material)} disabled={rastreavel(material)} className="rounded-md border border-slate-200 bg-white p-2 text-slate-500 disabled:opacity-30" title="Transferir localização" aria-label={`Transferir ${material.nome}`}><ArrowRightLeft size={15} /></button>
                      {podeGerenciarEstoque && (
                        <button type="button" onClick={() => removerMaterial(material)} className="rounded-md border border-red-100 bg-white p-2 text-red-600" title="Remover material do estoque" aria-label={`Remover ${material.nome}`}><Trash2 size={15} /></button>
                      )}
                    </div>
                  </div>
                </article>
              ))}
            </div>
          </section>
        ))}
        {materiaisFiltrados.length === 0 && (
          <div className="rounded-lg border border-slate-200 bg-white p-8 text-center text-sm text-slate-400">
            {materiais.length === 0
              ? "Nenhum produto cadastrado no estoque."
              : "Nenhum material encontrado com os filtros aplicados."}
          </div>
        )}
      </div>

      {abasRetiradaImportadas > 0 && (
        <div className="rounded-md border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-900">
          <strong>{abasRetiradaImportadas} abas de retirada</strong> foram importadas em {" "}
          <strong>{ordensHistoricasImportadas} ORs</strong>. As abas CADASTRO_PRODUTOS,
          ESTOQUE ATUAL, SOBRAS - RETORNOS, SIMULACAO e Configurações são auxiliares e não
          geram uma OR própria.
        </div>
      )}

      {atalhosOrdensEstoque.length > 0 && (
        <section className="space-y-2">
          <div className="flex items-center justify-between gap-3">
            <h2 className="text-sm font-bold text-slate-800">Ordens de retirada disponíveis</h2>
            <span className="text-xs font-semibold text-slate-500">
              {atalhosOrdensEstoque.length} ORs
            </span>
          </div>
          <div className="flex gap-2 overflow-x-auto pb-1">
            {atalhosOrdensEstoque.map((ordem) => (
              <button
                key={`atalho-${ordem.id}`}
                type="button"
                onClick={() => {
                  setAbaEstoque(String(ordem.comarcaId));
                  setAbaOrdemEstoque(String(ordem.id));
                }}
                className={`shrink-0 rounded-md border px-3 py-2 text-left text-xs transition-colors ${
                  String(abaEstoque) === String(ordem.comarcaId)
                    && String(abaOrdemEstoque) === String(ordem.id)
                    ? "border-blue-600 bg-blue-50 text-blue-700"
                    : "border-slate-200 bg-white text-slate-600 hover:border-blue-300 hover:bg-blue-50"
                }`}
              >
                <strong className="block">{ordem.numeroOr}</strong>
                <span className="mt-0.5 block text-[10px] text-slate-400">
                  {ordem.comarcaNome}
                  {ordem.abasOrigem?.[0] ? ` · ${ordem.abasOrigem[0]}` : ""}
                </span>
              </button>
            ))}
          </div>
        </section>
      )}
      <div className="hidden overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-md md:block">
        <table className="w-full min-w-[1400px]">
          <thead className="sticky top-0 z-10 border-b border-slate-200 bg-slate-50 shadow-sm">
            <tr>
              <th className="px-6 py-4 text-left text-sm font-semibold text-slate-700">
                Produto
              </th>
              <th className="px-6 py-4 text-left text-sm font-semibold text-slate-700">
                Categoria
              </th>
              <th className="px-6 py-4 text-left text-sm font-semibold text-slate-700">
                Part Number
              </th>
              <th className="px-6 py-4 text-left text-sm font-semibold text-slate-700">
                Fabricante
              </th>
              <th className="px-6 py-4 text-left text-sm font-semibold text-slate-700">
                Localização
              </th>
              <th className="px-6 py-4 text-left text-sm font-semibold text-slate-700">
                Medição
              </th>
              <th className="px-6 py-4 text-center text-sm font-semibold text-slate-700">
                Em estoque
              </th>
              <th className="px-6 py-4 text-center text-sm font-semibold text-slate-700">
                Reservado
              </th>
              <th className="px-6 py-4 text-center text-sm font-semibold text-slate-700">
                Disponível
              </th>
              <th className="px-6 py-4 text-center text-sm font-semibold text-slate-700">
                Em falta nas ORs
              </th>
              <th className="px-6 py-4 text-right text-sm font-semibold text-slate-700">
                Custo médio
              </th>
              <th className="px-6 py-4 text-right text-sm font-semibold text-slate-700">
                Valor em estoque
              </th>
              <th className="px-6 py-4 text-center text-sm font-semibold text-slate-700">
                Ações
              </th>
            </tr>
          </thead>
          <tbody>
            {materiaisPorCategoria.map((grupo) => (
              <Fragment key={grupo.value}>
                <tr className="bg-slate-100/70">
                  <td
                    colSpan="13"
                    className="px-6 py-2 text-xs font-black uppercase tracking-wide text-slate-600"
                  >
                    {grupo.label} ({grupo.materiais.length})
                  </td>
                </tr>
                {grupo.materiais.map((material) => (
                  <tr
                    key={material.id}
                    className={`border-b border-slate-200 hover:bg-slate-50 transition-colors ${isCriticalStock(material) ? "bg-red-50/50" : ""}`}
                  >
                    <td className="px-6 py-4 text-sm text-slate-800">
                      <div className="flex items-center gap-3">
                        {material.fotoProdutoUrl ? (
                          <button
                            type="button"
                            onClick={() => setFotoExpandida(material)}
                            className="h-11 w-11 overflow-hidden rounded-md border border-slate-200 bg-slate-50 transition hover:scale-105 hover:border-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-500"
                            title="Expandir foto do produto"
                          >
                            <img
                              src={material.fotoProdutoUrl}
                              alt={`Foto de ${material.nome}`}
                              className="h-full w-full object-cover"
                            />
                          </button>
                        ) : (
                          <span className="flex h-11 w-11 items-center justify-center rounded-md border border-slate-200 bg-slate-50 text-slate-400">
                            <Package size={18} />
                          </span>
                        )}
                        <span>
                          <strong className="block text-slate-800">
                            {material.nome}
                          </strong>
                          {material.descricao && (
                            <span className="block max-w-xs truncate text-xs text-slate-500">
                              {material.descricao}
                            </span>
                          )}
                        </span>
                      </div>
                    </td>
                    <td className="px-6 py-4 text-xs font-bold uppercase text-slate-500">
                      {getCategoriaMaterialLabel(material.categoria)}
                    </td>
                    <td className="px-6 py-4 text-sm font-mono text-slate-800">
                      {material.partNumber}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-800">
                      {material.fabricante}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-600">
                      <div className="flex max-w-[220px] flex-wrap gap-1">
                        {saldosDoMaterial(material.id).map((saldo) => (
                          <span key={saldo.id} className="rounded bg-slate-100 px-2 py-1 text-[11px]">
                            {saldo.localEstoque?.nome}: {formatarNumero(getSaldoLocalExibido(material, saldo))} {unidadeMaterial(material)}
                          </span>
                        ))}
                        {saldosDoMaterial(material.id).length === 0 && "Não informado"}
                      </div>
                    </td>
                    <td className="px-6 py-4 text-xs text-slate-600">
                      <strong className="block text-slate-700">
                        {TIPOS_CONTROLE.find(
                          (item) => item.value === (material.tipoControle || "UNIDADE"),
                        )?.label || "Por unidade"}
                      </strong>
                      <span>
                        {material.dimensao || unidadeMaterial(material)}
                        {material.comprimentoPorPeca
                          ? ` · ${formatarNumero(material.comprimentoPorPeca)} m/peça`
                          : ""}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-center">
                      <span className="inline-flex min-w-[3rem] items-center justify-center rounded-full bg-slate-100 px-3 py-1 text-sm font-semibold text-slate-800">
                        {formatarNumero(getSaldoEmEstoque(material))} {unidadeMaterial(material)}
                      </span>
                      {!controlaMetragem(material) && Number(material.metragemDisponivel) > 0 && (
                        <span className="mt-1 block text-[11px] text-slate-500">
                          {formatarNumero(material.metragemDisponivel)} m no total
                        </span>
                      )}
                    </td>
                    <td className="px-6 py-4 text-center">
                      <span className="inline-flex min-w-[3rem] items-center justify-center rounded-full bg-amber-100 px-3 py-1 text-sm font-semibold text-amber-800">
                        {formatarNumero(getReservado(material))} {unidadeMaterial(material)}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-center">
                      <span
                        className={`inline-flex min-w-[3rem] items-center justify-center rounded-full px-3 py-1 text-sm font-semibold ${isCriticalStock(material) ? "bg-red-200 text-red-800" : "bg-green-100 text-green-800"}`}
                      >
                        {isCriticalStock(material) && (
                          <AlertCircle size={14} className="mr-1" />
                        )}
                        {formatarNumero(getLivre(material))} {unidadeMaterial(material)}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-center">
                      <span className={`inline-flex min-w-[3rem] items-center justify-center rounded-full px-3 py-1 text-sm font-semibold ${getFaltaRegistrada(material) > 0 ? "bg-rose-100 text-rose-800" : "bg-slate-100 text-slate-500"}`}>
                        {getFaltaRegistrada(material) > 0 && (
                          <AlertCircle size={14} className="mr-1" />
                        )}
                        {formatarNumero(getFaltaRegistrada(material))} {unidadeMaterial(material)}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-right">
                      <strong className="block text-sm text-slate-800">
                        {formatarMoeda(getCustoUnitarioExibido(material))}
                      </strong>
                      <span className="text-[11px] text-slate-500">
                        por {controlaMetragem(material) ? "metro" : unidadeMaterial(material)}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-right text-sm font-bold text-slate-900">
                      {formatarMoeda(valorTotalMaterial(material))}
                    </td>
                    <td className="px-6 py-4 text-center">
                      <div className="flex justify-center gap-1">
                        <button
                          type="button"
                          onClick={() => abrirModalEditarMaterial(material)}
                          className="rounded-md border border-slate-200 bg-white p-2 text-slate-500 hover:text-blue-600"
                          title="Editar cadastro"
                        >
                          <Edit2 size={14} />
                        </button>
                        <button
                          type="button"
                          onClick={() => abrirAjuste(material)}
                          disabled={rastreavel(material)}
                          className="rounded-md border border-slate-200 bg-white p-2 text-slate-500 hover:text-amber-600 disabled:cursor-not-allowed disabled:opacity-30"
                          title="Ajustar saldo"
                        >
                          <SlidersHorizontal size={14} />
                        </button>
                        <button
                          type="button"
                          onClick={() => abrirTransferencia(material)}
                          disabled={rastreavel(material)}
                          className="rounded-md border border-slate-200 bg-white p-2 text-slate-500 hover:text-cyan-600 disabled:cursor-not-allowed disabled:opacity-30"
                          title="Transferir localização"
                        >
                          <ArrowRightLeft size={14} />
                        </button>
                        {podeGerenciarEstoque && (
                          <button
                            type="button"
                            onClick={() => removerMaterial(material)}
                            className="rounded-md border border-slate-200 bg-white p-2 text-slate-500 hover:border-red-200 hover:bg-red-50 hover:text-red-700"
                            title="Remover material do estoque"
                          >
                            <Trash2 size={14} />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </Fragment>
            ))}
            {materiaisFiltrados.length === 0 && (
              <tr>
                <td colSpan="13" className="px-6 py-8 text-center text-slate-400">
                  {materiais.length === 0
                    ? "Nenhum produto cadastrado no estoque."
                    : "Nenhum material encontrado com os filtros aplicados."}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      </>
      ) : abaEstoque === "removidos" ? (
        <section className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
          <div className="border-b border-slate-200 bg-slate-50 px-5 py-4">
            <h2 className="font-bold text-slate-900">Materiais removidos</h2>
            <p className="mt-1 text-sm text-slate-500">
              Itens sem saldo retirados da operação. O histórico de movimentações permanece preservado.
            </p>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[920px]">
              <thead className="border-b border-slate-200 bg-white">
                <tr>
                  <th className="px-5 py-3 text-left text-xs font-semibold uppercase text-slate-500">Produto</th>
                  <th className="px-5 py-3 text-left text-xs font-semibold uppercase text-slate-500">Categoria</th>
                  <th className="px-5 py-3 text-left text-xs font-semibold uppercase text-slate-500">Part number</th>
                  <th className="px-5 py-3 text-left text-xs font-semibold uppercase text-slate-500">Removido por</th>
                  <th className="px-5 py-3 text-left text-xs font-semibold uppercase text-slate-500">Data da remoção</th>
                  <th className="px-5 py-3 text-center text-xs font-semibold uppercase text-slate-500">Ação</th>
                </tr>
              </thead>
              <tbody>
                {materiaisRemovidos.map((material) => (
                  <tr key={material.id} className="border-b border-slate-100 last:border-0">
                    <td className="px-5 py-4 text-sm font-semibold text-slate-800">{material.nome}</td>
                    <td className="px-5 py-4 text-sm text-slate-600">{getCategoriaMaterialLabel(material.categoria)}</td>
                    <td className="px-5 py-4 font-mono text-sm text-slate-700">{material.partNumber}</td>
                    <td className="px-5 py-4 text-sm text-slate-700">{material.removidoPor || "Não informado"}</td>
                    <td className="px-5 py-4 text-sm text-slate-700">
                      {material.removidoEm ? new Date(material.removidoEm).toLocaleString("pt-BR") : "Não informado"}
                    </td>
                    <td className="px-5 py-4 text-center">
                      <button
                        type="button"
                        onClick={() => restaurarMaterial(material)}
                        className="inline-flex items-center gap-2 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm font-semibold text-emerald-700 hover:bg-emerald-100"
                        title="Restaurar material no catálogo"
                      >
                        <Undo2 size={15} /> Restaurar
                      </button>
                    </td>
                  </tr>
                ))}
                {materiaisRemovidos.length === 0 && (
                  <tr>
                    <td colSpan="6" className="px-5 py-10 text-center text-sm text-slate-400">
                      Nenhum material removido.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </section>
      ) : (
        <section className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
          <div className="flex flex-col gap-3 border-b border-slate-200 bg-slate-50 p-5 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h2 className="font-bold text-slate-900">
                {estoqueComarcaSelecionada?.comarca?.nomeComarca || "Obra"}
              </h2>
              <p className="text-xs text-slate-500">
                {estoqueComarcaSelecionada?.comarca?.ordemServico?.numeroOs || "OS não vinculada"}
                {" · "}
                {abaOrdemEstoque === "resumo"
                  ? "Resumo consolidado das ORs"
                  : estoqueOperacionalSelecionado?.numeroOr || "Ordem de Retirada"}
              </p>
              {estoqueOperacionalSelecionado?.abasOrigem?.length > 0 && (
                <p className="mt-1 text-xs font-semibold text-blue-700">
                  Origem: {estoqueOperacionalSelecionado.abasOrigem.join(", ")}
                </p>
              )}
            </div>
            <div className="flex gap-5 text-sm">
              <div>
                <span className="block text-xs text-slate-500">Retirado</span>
                <strong>{formatarNumero(estoqueOperacionalSelecionado?.totalRetirado)}</strong>
              </div>
              <div>
                <span className="block text-xs text-slate-500">Devolvido</span>
                <strong>{formatarNumero(estoqueOperacionalSelecionado?.totalDevolvido)}</strong>
              </div>
              <div>
                <span className="block text-xs text-slate-500">Faltante</span>
                <strong className="text-rose-700">
                  {formatarNumero(estoqueOperacionalSelecionado?.totalFaltante)}
                </strong>
              </div>
              <div>
                <span className="block text-xs text-slate-500">Valor líquido</span>
                <strong>{formatarMoeda(estoqueOperacionalSelecionado?.valorLiquido)}</strong>
              </div>
            </div>
          </div>
          {(estoqueComarcaSelecionada?.ordens || []).length > 0 && (
            <div
              role="tablist"
              aria-label="Ordens de retirada da obra"
              className="flex gap-2 overflow-x-auto border-b border-slate-200 bg-white px-5 py-3"
            >
              <button
                type="button"
                role="tab"
                aria-selected={abaOrdemEstoque === "resumo"}
                onClick={() => setAbaOrdemEstoque("resumo")}
                className={`shrink-0 rounded-md border px-3 py-2 text-xs font-bold ${
                  abaOrdemEstoque === "resumo"
                    ? "border-blue-600 bg-blue-50 text-blue-700"
                    : "border-slate-200 text-slate-600 hover:bg-slate-50"
                }`}
              >
                Resumo da obra
              </button>
              {estoqueComarcaSelecionada.ordens.map((ordem) => (
                <button
                  key={ordem.id}
                  type="button"
                  role="tab"
                  aria-selected={String(abaOrdemEstoque) === String(ordem.id)}
                  onClick={() => setAbaOrdemEstoque(String(ordem.id))}
                  className={`shrink-0 rounded-md border px-3 py-2 text-left text-xs ${
                    String(abaOrdemEstoque) === String(ordem.id)
                      ? "border-blue-600 bg-blue-50 text-blue-700"
                      : "border-slate-200 text-slate-600 hover:bg-slate-50"
                  }`}
                >
                  <strong className="block">{ordem.numeroOr}</strong>
                  {ordem.abasOrigem?.[0] && (
                    <span className="mt-0.5 block max-w-56 truncate text-[10px] text-slate-400">
                      {ordem.abasOrigem[0]}
                    </span>
                  )}
                </button>
              ))}
            </div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full min-w-[820px] text-left text-sm">
              <thead className="bg-white text-xs uppercase text-slate-500">
                <tr>
                  <th className="px-5 py-3">Material</th>
                  <th className="px-5 py-3">ORs</th>
                  <th className="px-5 py-3 text-right">Retirado</th>
                  <th className="px-5 py-3 text-right">Devolvido</th>
                  <th className="px-5 py-3 text-right">Retirada líquida</th>
                  <th className="px-5 py-3 text-right">Faltante</th>
                  <th className="px-5 py-3 text-right">Valor atribuído</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {(estoqueOperacionalSelecionado?.itens || []).map((item) => (
                  <tr key={`${abaEstoque}-${item.material?.id || item.nome}`}>
                    <td className="px-5 py-3">
                      <strong className="block text-slate-800">{item.nome}</strong>
                      <span className="text-xs text-slate-500">
                        {item.material?.partNumber || "Sem código"}
                      </span>
                    </td>
                    <td className="px-5 py-3 text-xs text-slate-600">
                      {item.ordens.join(", ") || "Sem OR"}
                    </td>
                    <td className="px-5 py-3 text-right">{formatarNumero(item.retirada)}</td>
                    <td className="px-5 py-3 text-right">{formatarNumero(item.devolvida)}</td>
                    <td className="px-5 py-3 text-right font-bold text-slate-900">
                      {formatarNumero(item.saldoLiquido)}
                    </td>
                    <td className="px-5 py-3 text-right font-bold text-rose-700">
                      {item.faltante > 0 ? formatarNumero(item.faltante) : "—"}
                    </td>
                    <td className="px-5 py-3 text-right font-bold text-slate-900">
                      {formatarMoeda(
                        item.saldoLiquido * getCustoUnitarioExibido(item.material),
                      )}
                    </td>
                  </tr>
                ))}
                {(estoqueOperacionalSelecionado?.itens || []).length === 0 && (
                  <tr>
                    <td colSpan="7" className="px-5 py-10 text-center text-slate-400">
                      Esta obra ainda não possui retirada de material executada por OR.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <p className="border-t border-slate-200 bg-amber-50 px-5 py-3 text-xs text-amber-800">
            Retirada líquida representa o que saiu do estoque menos o que retornou. O consumo
            efetivamente instalado continua sendo conciliado na auditoria e no As-Built.
          </p>
        </section>
      )}

      {unidadesRastreaveis.length > 0 && (
        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-md">
          <div className="border-b border-slate-200 bg-slate-50/50 p-5">
            <h2 className="font-bold text-slate-800">Bobinas e Rolos</h2>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px]">
              <thead className="bg-slate-50 text-left text-xs font-bold uppercase text-slate-500">
                <tr>
                  <th className="px-5 py-3">Código</th>
                  <th className="px-5 py-3">Material</th>
                  <th className="px-5 py-3">Tipo</th>
                  <th className="px-5 py-3">Depósito</th>
                  <th className="px-5 py-3 text-right">Inicial</th>
                  <th className="px-5 py-3 text-right">Atual</th>
                  <th className="px-5 py-3">Status</th>
                  <th className="px-5 py-3">Entrada</th>
                  <th className="px-5 py-3 text-center">Ações</th>
                </tr>
              </thead>
              <tbody>
                {unidadesRastreaveis.map((unidade) => (
                  <tr key={unidade.id} className="border-t border-slate-100 text-sm">
                    <td className="px-5 py-3 font-mono font-bold text-slate-800">{unidade.codigo}</td>
                    <td className="px-5 py-3 text-slate-700">{unidade.material?.nome}</td>
                    <td className="px-5 py-3 text-slate-500">{unidade.tipo}</td>
                    <td className="px-5 py-3 text-slate-600">{unidade.localEstoque?.nome || "—"}</td>
                    <td className="px-5 py-3 text-right">{formatarNumero(unidade.metragemInicial)} m</td>
                    <td className="px-5 py-3 text-right font-bold">{formatarNumero(unidade.metragemAtual)} m</td>
                    <td className="px-5 py-3">
                      <span className="rounded bg-slate-100 px-2 py-1 text-xs font-bold text-slate-600">
                        {String(unidade.status || "").replaceAll("_", " ")}
                      </span>
                    </td>
                    <td className="px-5 py-3 text-slate-500">
                      {unidade.dataEntrada ? new Date(unidade.dataEntrada).toLocaleString("pt-BR") : "—"}
                    </td>
                    <td className="px-5 py-3 text-center">
                      <button
                        type="button"
                        onClick={() => abrirTransferenciaUnidade(unidade)}
                        disabled={!unidade.localEstoque || Number(unidade.metragemAtual || 0) <= 0}
                        className="rounded-md border border-slate-200 bg-white p-2 text-slate-500 hover:text-cyan-600 disabled:opacity-30"
                        title="Transferir bobina/rolo completo"
                      >
                        <ArrowRightLeft size={15} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="bg-white rounded-lg shadow-md overflow-hidden border border-slate-200 mb-8">
        <div className="p-5 border-b border-slate-200 bg-slate-50/50 flex items-center justify-between gap-3">
          <div>
            <h2 className="font-bold text-slate-800">Ordens de Retirada</h2>
            <p className="text-xs text-slate-500">
              Documento obrigatório para qualquer retirada ou devolução de material.
            </p>
          </div>
          <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-bold text-slate-600">
            {ordensRetirada.length} ORs
          </span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-slate-50 text-left text-xs font-bold uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-6 py-3">OR</th>
                <th className="px-6 py-3">OS / Obra</th>
                <th className="px-6 py-3">Itens</th>
                <th className="px-6 py-3">Log</th>
                <th className="px-6 py-3 text-center">Status</th>
                <th className="px-6 py-3 text-center">Ações</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {ordensRetirada.map((or) => (
                <tr key={or.id} className="text-sm">
                  <td className="px-6 py-4 font-black text-slate-800">
                    {or.numeroOr}
                  </td>
                  <td className="px-6 py-4 text-slate-600">
                    <span className="block font-semibold text-slate-800">
                      {or.ordemServico?.numeroOs || "OS não vinculada"}
                    </span>
                    <span className="text-xs">{or.comarca?.nomeComarca}</span>
                  </td>
                  <td className="px-6 py-4 text-xs text-slate-600">
                    {(or.itens || []).map((item) => (
                      <div key={item.id} className="mb-1">
                        <span className="mr-1 inline-flex rounded bg-slate-100 px-2 py-1">
                          {item.nomeMaterial}: {item.quantidadeSolicitada}
                        </span>
                        {(item.alocacoes || []).map((alocacao) => (
                          <span key={alocacao.id} className="inline-flex gap-1">
                            {alocacao.evidenciaRetiradaPath && (
                              <a
                                href={`/api/ordens-retirada/${or.id}/alocacoes/${alocacao.id}/evidencia/retirada`}
                                target="_blank"
                                rel="noreferrer"
                                className="inline-flex items-center gap-1 rounded px-1.5 py-1 font-semibold text-cyan-700 hover:bg-cyan-50"
                              >
                                <Camera size={12} /> Retirada
                              </a>
                            )}
                            {alocacao.evidenciaDevolucaoPath && (
                              <a
                                href={`/api/ordens-retirada/${or.id}/alocacoes/${alocacao.id}/evidencia/devolucao`}
                                target="_blank"
                                rel="noreferrer"
                                className="inline-flex items-center gap-1 rounded px-1.5 py-1 font-semibold text-emerald-700 hover:bg-emerald-50"
                              >
                                <Camera size={12} /> Retorno
                              </a>
                            )}
                          </span>
                        ))}
                      </div>
                    ))}
                  </td>
                  <td className="px-6 py-4 text-xs text-slate-500">
                    {or.levadoPor && <p>Levou: {or.levadoPor}</p>}
                    {or.dataRetirada && (
                      <p>Retirada: {new Date(or.dataRetirada).toLocaleString("pt-BR")}</p>
                    )}
                    {or.devolvidoPor && <p>Devolveu: {or.devolvidoPor}</p>}
                    {or.dataDevolucao && (
                      <p>Devolução: {new Date(or.dataDevolucao).toLocaleString("pt-BR")}</p>
                    )}
                  </td>
                  <td className="px-6 py-4 text-center">
                    <span className="rounded-full bg-blue-50 px-3 py-1 text-xs font-black uppercase text-blue-700">
                      {or.status}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-center">
                    {or.status === "GERADA" && (
                      <button
                        type="button"
                        onClick={() => abrirModalOr(or, "retirada")}
                        className="rounded-lg bg-blue-600 px-3 py-2 text-xs font-bold text-white hover:bg-blue-700"
                      >
                        Executar Retirada
                      </button>
                    )}
                    {or.status === "RETIRADA" && (
                      <button
                        type="button"
                        onClick={() => abrirModalOr(or, "devolucao")}
                        className="rounded-lg bg-emerald-600 px-3 py-2 text-xs font-bold text-white hover:bg-emerald-700"
                      >
                        Registrar Devolução
                      </button>
                    )}
                    {or.status === "DEVOLVIDA" && (
                      <span className="text-xs font-bold text-slate-400">Concluída</span>
                    )}
                  </td>
                </tr>
              ))}
              {ordensRetirada.length === 0 && (
                <tr>
                  <td colSpan="6" className="px-6 py-8 text-center text-slate-400">
                    Nenhuma Ordem de Retirada gerada.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <section className="mb-8 border-y border-slate-200 bg-white">
        <div className="grid gap-0 xl:grid-cols-[0.8fr_1.35fr_1fr]">
          <div className="border-b border-slate-200 p-5 xl:border-b-0 xl:border-r">
            <h2 className="font-bold text-slate-800">Materiais mais utilizados</h2>
            <p className="mb-4 text-xs text-slate-500">Consumo líquido no período filtrado.</p>
            <div className="space-y-3">
              {materiaisMaisUtilizados.map((item, indice) => (
                <div key={item.material} className="flex items-center justify-between gap-3 text-sm">
                  <span className="min-w-0 truncate text-slate-700">
                    <strong className="mr-2 text-slate-400">{indice + 1}.</strong>
                    {item.material}
                  </span>
                  <strong className="shrink-0 text-slate-900">
                    {formatarNumero(item.consumo)} {item.unidade}
                  </strong>
                </div>
              ))}
              {materiaisMaisUtilizados.length === 0 && (
                <p className="text-sm text-slate-400">Sem consumo registrado para os filtros atuais.</p>
              )}
            </div>
          </div>

          <div className="border-b border-slate-200 p-5 xl:border-b-0 xl:border-r">
            <h2 className="font-bold text-slate-800">Consumo por OS e OR</h2>
            <p className="mb-3 text-xs text-slate-500">Retiradas menos devoluções, agrupadas por documento.</p>
            <div className="max-h-64 overflow-auto">
              <table className="w-full text-left text-sm">
                <thead className="sticky top-0 bg-white text-xs uppercase text-slate-500">
                  <tr>
                    <th className="py-2 pr-3">OS / OR</th>
                    <th className="py-2 pr-3">Material</th>
                    <th className="py-2 text-right">Consumo</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {relatorioConsumo.map((item) => (
                    <tr key={`${item.os}-${item.or}-${item.material}`}>
                      <td className="py-2 pr-3 text-xs text-slate-600">
                        <span className="block font-semibold text-slate-800">{item.os}</span>
                        {item.or}
                      </td>
                      <td className="py-2 pr-3 text-slate-700">{item.material}</td>
                      <td className="py-2 text-right font-bold text-slate-900">
                        {formatarNumero(item.consumo)} {item.unidade}
                      </td>
                    </tr>
                  ))}
                  {relatorioConsumo.length === 0 && (
                    <tr><td colSpan="3" className="py-6 text-center text-slate-400">Nenhum consumo encontrado.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>

          <div className="p-5">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="font-bold text-slate-800">Alertas de reposição</h2>
                <p className="mb-3 text-xs text-slate-500">Saldo local igual ou abaixo do mínimo cadastrado.</p>
              </div>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={abrirMinimosLocais}
                  disabled={saldosLocais.length === 0}
                  title="Configurar mínimos por depósito"
                  className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-slate-200 text-slate-600 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  <SlidersHorizontal size={15} />
                </button>
                <span className={`rounded-full px-2.5 py-1 text-xs font-bold ${alertasReposicao.length ? "bg-red-100 text-red-700" : "bg-emerald-100 text-emerald-700"}`}>
                  {alertasReposicao.length}
                </span>
              </div>
            </div>
            <div className="max-h-64 space-y-2 overflow-auto">
              {alertasReposicao.map((item) => (
                <div key={`${item.deposito}-${item.material}`} className="border-l-2 border-red-400 pl-3 text-sm">
                  <span className="block font-semibold text-slate-800">{item.material}</span>
                  <span className="text-xs text-slate-500">
                    {item.deposito}: {formatarNumero(item.saldo)} em estoque / mínimo {formatarNumero(item.minimo)}
                  </span>
                </div>
              ))}
              {alertasReposicao.length === 0 && (
                <p className="text-sm text-emerald-700">Nenhum depósito abaixo do mínimo.</p>
              )}
            </div>
          </div>
        </div>
      </section>

      {/* Histórico */}
      <div className="bg-white rounded-lg shadow-md overflow-hidden border border-slate-200">
        <div className="p-5 border-b border-slate-200 bg-slate-50/50 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
          <div className="flex items-center gap-2">
            <History size={18} className="text-slate-500" />
            <h2 className="font-bold text-slate-800">
              Histórico de Movimentações
            </h2>
          </div>
          <div className="grid w-full gap-2 md:grid-cols-4 xl:grid-cols-[210px_140px_170px_130px_150px_150px_135px_135px_44px]">
            <div className="relative">
              <Search className="absolute left-3 top-2.5 text-slate-400 w-4 h-4" />
              <input
                type="text"
                value={historicoFiltro}
                onChange={(e) => setHistoricoFiltro(e.target.value)}
                placeholder="Material, OS, comarca..."
                className="w-full pl-9 pr-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            <select
              value={historicoTipoFiltro}
              onChange={(e) => setHistoricoTipoFiltro(e.target.value)}
              className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="">Todos os tipos</option>
              {tiposHistorico.map((tipo) => (
                <option key={tipo} value={tipo}>
                  {getMovimentacaoStyle(tipo).label}
                </option>
              ))}
            </select>
            <select
              value={historicoComarcaFiltro}
              onChange={(e) => setHistoricoComarcaFiltro(e.target.value)}
              className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="">Todas as OS/comarcas</option>
              {comarcas.map((comarca) => (
                <option key={comarca.id} value={comarca.id}>
                  {getComarcaOptionLabel(comarca)}
                </option>
              ))}
            </select>
            <select
              value={historicoProjetoFiltro}
              onChange={(e) => setHistoricoProjetoFiltro(e.target.value)}
              className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="">Todos os projetos</option>
              {projetosHistorico.map((projeto) => (
                <option key={projeto.id} value={projeto.id}>
                  Projeto #{projeto.id}
                </option>
              ))}
            </select>
            <select
              value={historicoMaterialFiltro}
              onChange={(e) => setHistoricoMaterialFiltro(e.target.value)}
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="">Todos os materiais</option>
              {materiais.map((material) => (
                <option key={material.id} value={material.id}>{material.nome}</option>
              ))}
            </select>
            <select
              value={historicoPessoaFiltro}
              onChange={(e) => setHistoricoPessoaFiltro(e.target.value)}
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="">Todas as pessoas</option>
              {pessoasHistorico.map((pessoa) => (
                <option key={pessoa} value={pessoa}>{pessoa}</option>
              ))}
            </select>
            <input
              type="date"
              value={historicoDataInicio}
              onChange={(e) => setHistoricoDataInicio(e.target.value)}
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm"
              title="Data inicial"
            />
            <input
              type="date"
              value={historicoDataFim}
              onChange={(e) => setHistoricoDataFim(e.target.value)}
              className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm"
              title="Data final"
            />
            <button
              type="button"
              onClick={exportarHistoricoXlsx}
              className="flex h-10 w-11 items-center justify-center rounded-lg bg-slate-800 text-white hover:bg-slate-700"
              title="Exportar movimentações filtradas em Excel"
            >
              <Download size={17} />
            </button>
          </div>
        </div>
        <table className="w-full min-w-[1420px] text-left text-sm">
          <thead className="bg-slate-50 text-slate-600 border-b border-slate-200 text-xs font-bold uppercase">
            <tr>
              <th className="px-4 py-4">Data</th>
              <th className="px-6 py-4">Tipo</th>
              <th className="px-6 py-4">Material</th>
              <th className="px-4 py-4 text-center">Mov.</th>
              <th className="px-4 py-4 text-center">Saldo</th>
              <th className="px-4 py-4 text-right">Custo unitário</th>
              <th className="px-4 py-4 text-right">Valor total</th>
              <th className="px-4 py-4">Responsáveis</th>
              <th className="px-6 py-4">Referência</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {historicoFiltrado.map((mov) => {
              const style = getMovimentacaoStyle(mov.tipo);
              const Icon = style.icon;
              const referencias = getReferenciaOperacional(mov);

              return (
                <tr
                  key={mov.id}
                  className="hover:bg-slate-50/50 transition-colors"
                >
                  <td className="px-4 py-4 whitespace-nowrap text-xs text-slate-500">
                    {mov.dataMovimentacao
                      ? new Date(mov.dataMovimentacao).toLocaleString("pt-BR")
                      : "—"}
                  </td>
                  <td className="px-6 py-4">
                    <span
                      className={`inline-flex items-center gap-1 font-bold px-2 py-0.5 rounded text-xs ${style.className}`}
                    >
                      <Icon size={14} /> {style.label}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-slate-700 font-medium">
                    {mov.material?.nome || "Insumo"}
                    {mov.material?.partNumber && (
                      <span className="block text-[10px] font-mono text-slate-400">
                        {mov.material.partNumber}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-4 text-center font-semibold text-slate-800">
                    {valorMovimentacao(mov)}
                  </td>
                  <td className="px-4 py-4 text-center text-xs text-slate-600">
                    {mov.saldoAnterior != null && mov.saldoPosterior != null
                      ? `${formatarNumero(mov.saldoAnterior)} → ${formatarNumero(mov.saldoPosterior)}`
                      : "Legado"}
                  </td>
                  <td className="px-4 py-4 text-right text-xs text-slate-600">
                    {formatarMoeda(mov.custoUnitario)}
                  </td>
                  <td className="px-4 py-4 text-right font-semibold text-slate-800">
                    {formatarMoeda(mov.valorTotalMovimentacao)}
                    {mov.custoEstimado && (
                      <span className="block text-[10px] font-normal uppercase text-amber-700">
                        estimado
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-4 text-xs text-slate-600">
                    <span className="block">
                      {mov.tipo === "ENTRADA" ? "Adicionou" : "Lançou"}: {mov.lancadoPor || mov.funcionario?.nome || "Sistema"}
                    </span>
                    {mov.autorizadoPor && <span className="block">Autorizou: {mov.autorizadoPor}</span>}
                    {mov.retiradoPor && (
                      <span className="block">
                        {mov.tipo === "DEVOLUCAO_OR" ? "Devolveu" : "Retirou"}: {mov.retiradoPor}
                      </span>
                    )}
                  </td>
                  <td className="px-6 py-4 text-xs text-slate-500 max-w-sm">
                    {referencias.length > 0 && (
                      <div className="mb-1 flex flex-wrap gap-1">
                        {referencias.map((referencia) => (
                          <span
                            key={referencia}
                            className="rounded bg-slate-100 px-2 py-0.5 font-semibold text-slate-700"
                          >
                            {referencia}
                          </span>
                        ))}
                      </div>
                    )}
                    <span>{mov.observacao || "Movimentação manual"}</span>
                  </td>
                </tr>
              );
            })}
            {historicoFiltrado.length === 0 && (
              <tr>
                <td
                  colSpan="9"
                  className="px-6 py-8 text-center text-slate-400"
                >
                  Nenhuma movimentação encontrada para o filtro informado.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <Modal isOpen={showSimulacaoModal} onClose={handleCloseModal} title="Simular retirada">
        <div className="space-y-5">
          <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
            A simulação usa o saldo livre atual e não baixa materiais, não reserva estoque e não
            cria uma OR.
          </div>

          <div className="space-y-3">
            {simulacaoItens.map((item) => {
              const materialSelecionado = materiais.find(
                (material) => String(material.id) === String(item.materialId),
              );
              return (
                <div
                  key={item.id}
                  className="grid gap-2 rounded-md border border-slate-200 p-3 sm:grid-cols-[1fr_8rem_2.5rem]"
                >
                  <div>
                    <label className="mb-1 block text-xs font-bold uppercase text-slate-500">
                      Material
                    </label>
                    <select
                      value={item.materialId}
                      onChange={(event) => atualizarLinhaSimulacao(
                        item.id,
                        "materialId",
                        event.target.value,
                      )}
                      className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm"
                    >
                      <option value="">Selecione</option>
                      {materiais.map((material) => {
                        const selecionadoEmOutraLinha = simulacaoItens.some(
                          (linha) => linha.id !== item.id
                            && String(linha.materialId) === String(material.id),
                        );
                        return (
                          <option
                            key={material.id}
                            value={material.id}
                            disabled={selecionadoEmOutraLinha}
                          >
                            {material.nome} · saldo {formatarNumero(getLivre(material))} {unidadeMaterial(material)}
                          </option>
                        );
                      })}
                    </select>
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-bold uppercase text-slate-500">
                      Quantidade
                    </label>
                    <input
                      type="number"
                      min={controlaMetragem(materialSelecionado) ? "0.001" : "1"}
                      step={controlaMetragem(materialSelecionado) ? "0.001" : "1"}
                      value={item.quantidade}
                      onChange={(event) => atualizarLinhaSimulacao(
                        item.id,
                        "quantidade",
                        event.target.value,
                      )}
                      className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm"
                    />
                  </div>
                  <button
                    type="button"
                    onClick={() => removerLinhaSimulacao(item.id)}
                    className="mt-auto flex h-10 items-center justify-center rounded-md border border-slate-200 text-slate-500 hover:border-rose-200 hover:bg-rose-50 hover:text-rose-700"
                    title="Remover item da simulação"
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              );
            })}
          </div>

          <button
            type="button"
            onClick={() => setSimulacaoItens((itens) => [...itens, novaLinhaSimulacao()])}
            disabled={simulacaoItens.length >= materiais.length}
            className="inline-flex items-center gap-2 rounded-md border border-blue-200 px-3 py-2 text-sm font-bold text-blue-700 hover:bg-blue-50 disabled:opacity-40"
          >
            <Plus size={16} /> Adicionar material
          </button>

          {resultadoSimulacao.itens.length > 0 && (
            <section className="overflow-hidden rounded-md border border-slate-200">
              <div className={`border-b px-4 py-3 text-sm font-bold ${
                resultadoSimulacao.possuiFalta
                  ? "border-rose-200 bg-rose-50 text-rose-800"
                  : "border-emerald-200 bg-emerald-50 text-emerald-800"
              }`}
              >
                {resultadoSimulacao.possuiFalta
                  ? `Retirada inviável: faltam ${formatarNumero(resultadoSimulacao.quantidadeFaltante)} unidades de controle.`
                  : "Retirada viável com o saldo livre atual."}
              </div>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[620px] text-left text-sm">
                  <thead className="bg-slate-50 text-xs uppercase text-slate-500">
                    <tr>
                      <th className="px-3 py-2">Material</th>
                      <th className="px-3 py-2 text-right">Saldo atual</th>
                      <th className="px-3 py-2 text-right">Solicitado</th>
                      <th className="px-3 py-2 text-right">Saldo projetado</th>
                      <th className="px-3 py-2 text-right">Faltante</th>
                      <th className="px-3 py-2 text-right">Valor</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {resultadoSimulacao.itens.map((item) => (
                      <tr key={item.id}>
                        <td className="px-3 py-2 font-semibold text-slate-800">
                          {item.material.nome}
                        </td>
                        <td className="px-3 py-2 text-right">{formatarNumero(item.saldoAtual)}</td>
                        <td className="px-3 py-2 text-right">{formatarNumero(item.quantidade)}</td>
                        <td className={`px-3 py-2 text-right font-bold ${
                          item.saldoProjetado < 0 ? "text-rose-700" : "text-slate-800"
                        }`}
                        >
                          {formatarNumero(item.saldoProjetado)}
                        </td>
                        <td className="px-3 py-2 text-right font-bold text-rose-700">
                          {item.quantidadeFaltante > 0
                            ? formatarNumero(item.quantidadeFaltante)
                            : "—"}
                        </td>
                        <td className="px-3 py-2 text-right font-semibold">
                          {formatarMoeda(item.valorSolicitado)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="flex flex-wrap justify-end gap-5 border-t border-slate-200 bg-slate-50 px-4 py-3 text-sm">
                <span>Solicitado: <strong>{formatarNumero(resultadoSimulacao.quantidadeSolicitada)}</strong></span>
                <span>Valor estimado: <strong>{formatarMoeda(resultadoSimulacao.valorSolicitado)}</strong></span>
              </div>
            </section>
          )}

          <div className="flex justify-end">
            <button
              type="button"
              onClick={handleCloseModal}
              className="rounded-md bg-slate-800 px-5 py-2 font-bold text-white hover:bg-slate-700"
            >
              Fechar
            </button>
          </div>
        </div>
      </Modal>

      <Modal isOpen={showMinimoLocalModal} onClose={handleCloseModal} title="Estoque Mínimo por Depósito">
        <form onSubmit={salvarMinimoLocal} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-semibold text-slate-700">Material e depósito</label>
            <select
              required
              value={minimoLocalForm.saldoId}
              onChange={(event) => selecionarSaldoMinimo(event.target.value)}
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
            >
              <option value="">Selecione</option>
              {saldosLocais.map((saldo) => (
                <option key={saldo.id} value={saldo.id}>
                  {saldo.material?.nome} - {saldo.localEstoque?.nome}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm font-semibold text-slate-700">Mínimo neste depósito</label>
            <input
              type="number"
              min="0"
              step="0.001"
              value={minimoLocalForm.estoqueMinimo}
              onChange={(event) => setMinimoLocalForm((prev) => ({ ...prev, estoqueMinimo: event.target.value }))}
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
              placeholder="Vazio para usar o mínimo global do material"
            />
            <p className="mt-1 text-xs text-slate-500">
              Deixe vazio para herdar o mínimo cadastrado no material.
            </p>
          </div>
          <div className="flex justify-end gap-3 pt-3">
            <button type="button" onClick={handleCloseModal} className="rounded-lg border px-5 py-2">Cancelar</button>
            <button type="submit" className="rounded-lg bg-slate-800 px-5 py-2 font-bold text-white">Salvar mínimo</button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={showLocalEstoqueModal} onClose={handleCloseModal} title="Cadastrar Depósito">
        <form onSubmit={cadastrarLocalEstoque} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-semibold text-slate-700">Nome</label>
            <input
              required
              value={localForm.nome}
              onChange={(e) => setLocalForm((prev) => ({ ...prev, nome: e.target.value }))}
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
              placeholder="Ex: Almoxarifado Central"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-semibold text-slate-700">Endereço</label>
            <input
              value={localForm.endereco}
              onChange={(e) => setLocalForm((prev) => ({ ...prev, endereco: e.target.value }))}
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
              placeholder="Endereço ou referência interna"
            />
          </div>
          <div className="flex justify-end gap-3 pt-3">
            <button type="button" onClick={handleCloseModal} className="rounded-lg border px-5 py-2">Cancelar</button>
            <button type="submit" className="rounded-lg bg-slate-800 px-5 py-2 font-bold text-white">Cadastrar</button>
          </div>
        </form>
      </Modal>

      <Modal
        isOpen={showTransferenciaUnidadeModal}
        onClose={handleCloseModal}
        title="Transferir Bobina ou Rolo"
      >
        <form onSubmit={transferirUnidadeRastreavel} className="space-y-4">
          <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm">
            <strong className="block text-slate-800">{unidadeOperacao?.codigo}</strong>
            <span className="text-slate-500">
              {unidadeOperacao?.material?.nome} · {formatarNumero(unidadeOperacao?.metragemAtual)} m · Origem: {unidadeOperacao?.localEstoque?.nome}
            </span>
          </div>
          <select
            required
            value={transferenciaUnidadeForm.destinoId}
            onChange={(e) => setTransferenciaUnidadeForm((prev) => ({ ...prev, destinoId: e.target.value }))}
            className="w-full rounded-lg border border-slate-300 px-4 py-2"
          >
            <option value="">Depósito de destino</option>
            {locaisEstoque
              .filter((local) => String(local.id) !== String(unidadeOperacao?.localEstoque?.id))
              .map((local) => <option key={local.id} value={local.id}>{local.nome}</option>)}
          </select>
          <textarea
            required
            rows="3"
            value={transferenciaUnidadeForm.motivo}
            onChange={(e) => setTransferenciaUnidadeForm((prev) => ({ ...prev, motivo: e.target.value }))}
            className="w-full resize-none rounded-lg border border-slate-300 px-4 py-2"
            placeholder="Motivo da transferência"
          />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <input
              required
              value={transferenciaUnidadeForm.lancadoPor}
              onChange={(e) => setTransferenciaUnidadeForm((prev) => ({ ...prev, lancadoPor: e.target.value }))}
              className="rounded-lg border border-slate-300 px-4 py-2"
              placeholder="Lançado por"
            />
            <input
              required
              value={transferenciaUnidadeForm.autorizadoPor}
              onChange={(e) => setTransferenciaUnidadeForm((prev) => ({ ...prev, autorizadoPor: e.target.value }))}
              className="rounded-lg border border-slate-300 px-4 py-2"
              placeholder="Autorizado por"
            />
          </div>
          <div className="flex justify-end gap-3 pt-3">
            <button type="button" onClick={handleCloseModal} className="rounded-lg border px-5 py-2">Cancelar</button>
            <button type="submit" className="rounded-lg bg-cyan-700 px-5 py-2 font-bold text-white">Transferir unidade</button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={showAjusteModal} onClose={handleCloseModal} title="Ajustar Saldo">
        <form onSubmit={registrarAjuste} className="space-y-4">
          <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm">
            <strong className="block text-slate-800">{materialOperacao?.nome}</strong>
            <span className="text-slate-500">
              Saldo atual: {materialOperacao ? formatarNumero(getLivre(materialOperacao)) : 0} {materialOperacao ? unidadeMaterial(materialOperacao) : ""}
            </span>
          </div>
          <div className="grid grid-cols-2 overflow-hidden rounded-lg border border-slate-200">
            {[
              ["AJUSTE_POSITIVO", "Ajuste positivo"],
              ["AJUSTE_NEGATIVO", "Ajuste negativo"],
            ].map(([valor, label]) => (
              <button
                key={valor}
                type="button"
                onClick={() => setAjusteForm((prev) => ({ ...prev, tipo: valor }))}
                className={`px-3 py-2 text-sm font-bold ${ajusteForm.tipo === valor ? "bg-slate-800 text-white" : "bg-white text-slate-600"}`}
              >
                {label}
              </button>
            ))}
          </div>
          <div>
            <label className="mb-1 block text-sm font-semibold text-slate-700">Depósito</label>
            <select
              required
              value={ajusteForm.localEstoqueId}
              onChange={(e) => setAjusteForm((prev) => ({ ...prev, localEstoqueId: e.target.value }))}
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
            >
              <option value="">Selecione o depósito</option>
              {locaisEstoque.map((local) => (
                <option key={local.id} value={local.id}>{local.nome}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm font-semibold text-slate-700">Valor do ajuste</label>
            <input
              type="number"
              required
              min={controlaMetragem(materialOperacao) ? "0.001" : "1"}
              step={controlaMetragem(materialOperacao) ? "0.001" : "1"}
              value={ajusteForm.valor}
              onChange={(e) => setAjusteForm((prev) => ({ ...prev, valor: e.target.value }))}
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-semibold text-slate-700">Motivo</label>
            <textarea
              required
              rows="3"
              value={ajusteForm.motivo}
              onChange={(e) => setAjusteForm((prev) => ({ ...prev, motivo: e.target.value }))}
              className="w-full resize-none rounded-lg border border-slate-300 px-4 py-2"
            />
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <input
              required
              value={ajusteForm.lancadoPor}
              onChange={(e) => setAjusteForm((prev) => ({ ...prev, lancadoPor: e.target.value }))}
              className="rounded-lg border border-slate-300 px-4 py-2"
              placeholder="Lançado por"
            />
            <input
              required
              value={ajusteForm.autorizadoPor}
              onChange={(e) => setAjusteForm((prev) => ({ ...prev, autorizadoPor: e.target.value }))}
              className="rounded-lg border border-slate-300 px-4 py-2"
              placeholder="Autorizado por"
            />
          </div>
          <div className="flex justify-end gap-3 pt-3">
            <button type="button" onClick={handleCloseModal} className="rounded-lg border px-5 py-2">Cancelar</button>
            <button type="submit" className="rounded-lg bg-slate-800 px-5 py-2 font-bold text-white">Registrar ajuste</button>
          </div>
        </form>
      </Modal>

      <Modal isOpen={showTransferenciaModal} onClose={handleCloseModal} title="Transferir Localização">
        <form onSubmit={registrarTransferencia} className="space-y-4">
          <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm">
            <strong className="block text-slate-800">{materialOperacao?.nome}</strong>
            <span className="text-slate-500">Transferência parcial entre depósitos</span>
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <select
              required
              value={transferenciaForm.origemId}
              onChange={(e) => setTransferenciaForm((prev) => ({ ...prev, origemId: e.target.value }))}
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
            >
              <option value="">Origem</option>
              {saldosDoMaterial(materialOperacao?.id)
                .filter((saldo) => saldoLocalValor(saldo) > 0)
                .map((saldo) => (
                  <option key={saldo.localEstoque.id} value={saldo.localEstoque.id}>
                    {saldo.localEstoque.nome} ({formatarNumero(saldoLocalValor(saldo))} {unidadeMaterial(materialOperacao)})
                  </option>
                ))}
            </select>
            <select
              required
              value={transferenciaForm.destinoId}
              onChange={(e) => setTransferenciaForm((prev) => ({ ...prev, destinoId: e.target.value }))}
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
            >
              <option value="">Destino</option>
              {locaisEstoque
                .filter((local) => String(local.id) !== String(transferenciaForm.origemId))
                .map((local) => <option key={local.id} value={local.id}>{local.nome}</option>)}
            </select>
          </div>
          <input
            type="number"
            required
            min={controlaMetragem(materialOperacao) ? "0.001" : "1"}
            step={controlaMetragem(materialOperacao) ? "0.001" : "1"}
            value={transferenciaForm.valor}
            onChange={(e) => setTransferenciaForm((prev) => ({ ...prev, valor: e.target.value }))}
            className="w-full rounded-lg border border-slate-300 px-4 py-2"
            placeholder={`Valor a transferir (${materialOperacao ? unidadeMaterial(materialOperacao) : ""})`}
          />
          <textarea
            required
            rows="3"
            value={transferenciaForm.motivo}
            onChange={(e) => setTransferenciaForm((prev) => ({ ...prev, motivo: e.target.value }))}
            className="w-full resize-none rounded-lg border border-slate-300 px-4 py-2"
            placeholder="Motivo da transferência"
          />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <input
              required
              value={transferenciaForm.lancadoPor}
              onChange={(e) => setTransferenciaForm((prev) => ({ ...prev, lancadoPor: e.target.value }))}
              className="rounded-lg border border-slate-300 px-4 py-2"
              placeholder="Lançado por"
            />
            <input
              required
              value={transferenciaForm.autorizadoPor}
              onChange={(e) => setTransferenciaForm((prev) => ({ ...prev, autorizadoPor: e.target.value }))}
              className="rounded-lg border border-slate-300 px-4 py-2"
              placeholder="Autorizado por"
            />
          </div>
          <div className="flex justify-end gap-3 pt-3">
            <button type="button" onClick={handleCloseModal} className="rounded-lg border px-5 py-2">Cancelar</button>
            <button type="submit" className="rounded-lg bg-cyan-700 px-5 py-2 font-bold text-white">Transferir</button>
          </div>
        </form>
      </Modal>

      <Modal
        isOpen={showHistoricoImportacoesModal}
        onClose={handleCloseModal}
        title="Histórico do estoque"
      >
        <div className="space-y-4">
          <div className="flex w-fit rounded-lg border border-slate-200 bg-slate-50 p-1">
            <button
              type="button"
              onClick={() => {
                setAbaHistoricoImportacoes("notas-fiscais");
                setImportacaoDetalhe(null);
              }}
              className={`rounded-md px-4 py-2 text-xs font-bold ${
                abaHistoricoImportacoes === "notas-fiscais"
                  ? "bg-white text-blue-700 shadow-sm"
                  : "text-slate-500 hover:text-slate-800"
              }`}
            >
              Notas fiscais
            </button>
            <button
              type="button"
              onClick={() => {
                setAbaHistoricoImportacoes("planilhas");
                setNotaFiscalDetalhe(null);
              }}
              className={`rounded-md px-4 py-2 text-xs font-bold ${
                abaHistoricoImportacoes === "planilhas"
                  ? "bg-white text-blue-700 shadow-sm"
                  : "text-slate-500 hover:text-slate-800"
              }`}
            >
              Planilhas
            </button>
            <button
              type="button"
              onClick={() => {
                setAbaHistoricoImportacoes("retiradas");
                setImportacaoDetalhe(null);
                setNotaFiscalDetalhe(null);
              }}
              className={`rounded-md px-4 py-2 text-xs font-bold ${
                abaHistoricoImportacoes === "retiradas"
                  ? "bg-white text-blue-700 shadow-sm"
                  : "text-slate-500 hover:text-slate-800"
              }`}
            >
              Retiradas/ORs
            </button>
          </div>

          {abaHistoricoImportacoes === "retiradas" && (
            <section className="space-y-4">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                  <span className="block text-[10px] font-bold uppercase text-slate-500">Registros</span>
                  <strong className="text-lg text-slate-900">{retiradasImportadas.length}</strong>
                </div>
                <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
                  <span className="block text-[10px] font-bold uppercase text-slate-500">ORs históricas</span>
                  <strong className="text-lg text-slate-900">{ordensHistoricasImportadas}</strong>
                </div>
                <div className="rounded-lg border border-rose-200 bg-rose-50 p-3">
                  <span className="block text-[10px] font-bold uppercase text-rose-600">Itens com falta</span>
                  <strong className="text-lg text-rose-800">
                    {retiradasImportadas.filter((item) => Number(item.quantidadeFaltante || 0) > 0).length}
                  </strong>
                </div>
              </div>

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-[1fr_190px]">
                <label className="relative">
                  <Search
                    size={16}
                    className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400"
                  />
                  <input
                    value={retiradaHistoricaBusca}
                    onChange={(event) => setRetiradaHistoricaBusca(event.target.value)}
                    className="w-full rounded-lg border border-slate-300 py-2 pl-9 pr-3 text-sm"
                    placeholder="Buscar por OR, OS, obra ou material"
                  />
                </label>
                <select
                  value={retiradaHistoricaSituacao}
                  onChange={(event) => setRetiradaHistoricaSituacao(event.target.value)}
                  className="rounded-lg border border-slate-300 px-3 py-2 text-sm"
                >
                  <option value="">Todas as situações</option>
                  <option value="COM_FALTA">Somente com falta</option>
                  <option value="SEM_FALTA">Somente sem falta</option>
                </select>
              </div>

              <div className="max-h-[28rem] overflow-auto rounded-lg border border-slate-200">
                <table className="w-full min-w-[980px] text-left text-xs">
                  <thead className="sticky top-0 bg-slate-100 uppercase text-slate-500">
                    <tr>
                      <th className="px-3 py-2">OR / Obra</th>
                      <th className="px-3 py-2">Material</th>
                      <th className="px-3 py-2 text-right">Saldo inicial</th>
                      <th className="px-3 py-2 text-right">Retirado</th>
                      <th className="px-3 py-2 text-right">Saldo final</th>
                      <th className="px-3 py-2 text-right">Em falta</th>
                      <th className="px-3 py-2">Data</th>
                      <th className="px-3 py-2 text-center">Ações</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {retiradasHistoricasFiltradas.map((item) => (
                      <tr key={item.retiradaImportadaId} className="hover:bg-slate-50">
                        <td className="px-3 py-2">
                          <strong className="block text-slate-800">{item.numeroOr || item.aba}</strong>
                          <span className="block text-slate-500">{item.numeroOs || "OS histórica"}</span>
                          <span className="text-[11px] text-slate-400">{item.comarca}</span>
                        </td>
                        <td className="px-3 py-2 font-semibold text-slate-800">{item.material}</td>
                        <td className="px-3 py-2 text-right">{formatarNumero(item.saldoInicial)}</td>
                        <td className="px-3 py-2 text-right font-bold">{formatarNumero(item.quantidadeRetirada)}</td>
                        <td className="px-3 py-2 text-right">{formatarNumero(item.saldoFinal)}</td>
                        <td className={`px-3 py-2 text-right font-bold ${
                          Number(item.quantidadeFaltante || 0) > 0 ? "text-rose-700" : "text-slate-400"
                        }`}>
                          {Number(item.quantidadeFaltante || 0) > 0
                            ? formatarNumero(item.quantidadeFaltante)
                            : "—"}
                        </td>
                        <td className="px-3 py-2 text-slate-600">
                          {item.dataRetirada
                            ? new Date(`${item.dataRetirada}T00:00:00`).toLocaleDateString("pt-BR")
                            : "—"}
                        </td>
                        <td className="px-3 py-2 text-center">
                          {podeGerenciarEstoque ? (
                            <button
                              type="button"
                              onClick={() => abrirEdicaoRetiradaHistorica(item)}
                              className="rounded-md border border-slate-200 bg-white p-2 text-slate-600 hover:border-blue-300 hover:text-blue-700"
                              title="Corrigir retirada histórica"
                              aria-label={`Corrigir retirada histórica de ${item.material}`}
                            >
                              <Edit2 size={14} />
                            </button>
                          ) : "—"}
                        </td>
                      </tr>
                    ))}
                    {retiradasHistoricasFiltradas.length === 0 && (
                      <tr>
                        <td colSpan="8" className="px-3 py-10 text-center text-slate-400">
                          Nenhuma retirada encontrada para os filtros informados.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </section>
          )}

          {abaHistoricoImportacoes === "notas-fiscais" && (
            <>
              <div className="max-h-72 overflow-auto rounded-lg border border-slate-200">
                <table className="w-full min-w-[880px] text-left text-xs">
                  <thead className="sticky top-0 bg-slate-100 uppercase text-slate-500">
                    <tr>
                      <th className="px-3 py-2">NF / Emitente</th>
                      <th className="px-3 py-2">Emissão</th>
                      <th className="px-3 py-2">Destino</th>
                      <th className="px-3 py-2 text-right">Itens</th>
                      <th className="px-3 py-2 text-right">Valor</th>
                      <th className="px-3 py-2">Importação</th>
                      <th className="px-3 py-2 text-center">Ações</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {importacoesNotaFiscal.map((importacao) => (
                      <tr key={importacao.id}>
                        <td className="px-3 py-2">
                          <strong className="block text-slate-800">
                            NF {importacao.numero || "não identificada"}
                          </strong>
                          <span className="text-slate-500">{importacao.emitenteNome || "Emitente não identificado"}</span>
                        </td>
                        <td className="px-3 py-2 text-slate-600">
                          {importacao.dataEmissao
                            ? new Date(importacao.dataEmissao).toLocaleDateString("pt-BR")
                            : "—"}
                        </td>
                        <td className="px-3 py-2 text-slate-600">{importacao.localEstoque}</td>
                        <td className="px-3 py-2 text-right text-slate-600">{importacao.itensProcessados}</td>
                        <td className="px-3 py-2 text-right font-bold text-slate-800">
                          {formatarMoeda(importacao.valorTotal)}
                        </td>
                        <td className="px-3 py-2 text-slate-600">
                          <span className="block">{importacao.importadoPor}</span>
                          <span className="text-[11px] text-slate-400">
                            {new Date(importacao.dataImportacao).toLocaleString("pt-BR")}
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          <div className="flex justify-center gap-1">
                            <button
                              type="button"
                              onClick={() => abrirDetalheNotaFiscal(importacao.id)}
                              className="rounded border border-slate-200 p-2 text-blue-700 hover:bg-blue-50"
                              title="Ver itens da nota fiscal"
                            >
                              <Eye size={15} />
                            </button>
                            <button
                              type="button"
                              onClick={() => baixarArquivoNotaFiscal(importacao)}
                              className="rounded border border-slate-200 p-2 text-emerald-700 hover:bg-emerald-50"
                              title="Baixar arquivo original"
                            >
                              <Download size={15} />
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                    {importacoesNotaFiscal.length === 0 && (
                      <tr>
                        <td colSpan="7" className="px-3 py-8 text-center text-slate-400">
                          Nenhuma nota fiscal importada.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>

              {notaFiscalDetalhe?.carregando && <LoadingSpinner />}
              {notaFiscalDetalhe && !notaFiscalDetalhe.carregando && (
                <section className="space-y-3 border-t border-slate-200 pt-4">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <h3 className="font-bold text-slate-900">
                        NF {notaFiscalDetalhe.numero || "não identificada"} · {notaFiscalDetalhe.emitenteNome}
                      </h3>
                      <p className="text-xs text-slate-500">
                        {notaFiscalDetalhe.nomeArquivo} · Série {notaFiscalDetalhe.serie || "—"}
                        {" · "}{notaFiscalDetalhe.localEstoque}
                      </p>
                      <p className="mt-1 text-xs text-slate-500">
                        {notaFiscalDetalhe.materiaisCriados} criados · {notaFiscalDetalhe.materiaisExistentes} vinculados
                        {" · "}{formatarMoeda(notaFiscalDetalhe.valorTotal)}
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => setNotaFiscalDetalhe(null)}
                      className="text-xs font-bold text-slate-500 hover:text-slate-800"
                    >
                      Fechar detalhes
                    </button>
                  </div>

                  <div className="max-h-72 overflow-auto rounded border border-slate-200">
                    <table className="w-full min-w-[920px] text-left text-xs">
                      <thead className="sticky top-0 bg-slate-50 uppercase text-slate-500">
                        <tr>
                          <th className="px-3 py-2">Código</th>
                          <th className="px-3 py-2">Material / Descrição fiscal</th>
                          <th className="px-3 py-2">NCM / CFOP</th>
                          <th className="px-3 py-2 text-right">Quantidade</th>
                          <th className="px-3 py-2 text-right">Valor unit.</th>
                          <th className="px-3 py-2 text-right">Total</th>
                          <th className="px-3 py-2">Resultado</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100">
                        {(notaFiscalDetalhe.itens || []).map((item) => (
                          <tr key={item.id}>
                            <td className="px-3 py-2 font-mono text-slate-600">{item.codigoProduto || "—"}</td>
                            <td className="px-3 py-2">
                              <strong className="block text-slate-800">{item.material}</strong>
                              <span className="block text-slate-500">{item.descricao}</span>
                              <span className="text-[11px] text-slate-400">Part number: {item.partNumber || "—"}</span>
                            </td>
                            <td className="px-3 py-2 text-slate-600">
                              {item.ncm || "—"} / {item.cfop || "—"}
                            </td>
                            <td className="px-3 py-2 text-right">
                              {formatarNumero(item.quantidade)} {item.unidadeFiscal || ""}
                            </td>
                            <td className="px-3 py-2 text-right">{formatarMoeda(item.valorUnitario)}</td>
                            <td className="px-3 py-2 text-right font-bold">{formatarMoeda(item.valorTotal)}</td>
                            <td className="px-3 py-2">{item.acao?.replaceAll("_", " ")}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>
              )}
            </>
          )}

          {abaHistoricoImportacoes === "planilhas" && (
            <>
          {reconciliacoesRetiradas.length > 0 && (
            <section className="mb-4 space-y-2 rounded-lg border border-orange-200 bg-orange-50 p-3">
              <div>
                <h3 className="text-sm font-bold text-orange-950">Correções auditadas de OR</h3>
                <p className="text-xs text-orange-800">
                  Histórico imutável das alterações confirmadas em retiradas importadas.
                </p>
              </div>
              <div className="max-h-40 overflow-auto rounded border border-orange-200 bg-white">
                <table className="w-full min-w-[720px] text-left text-xs">
                  <thead className="sticky top-0 bg-orange-100 uppercase text-orange-900">
                    <tr>
                      <th className="px-3 py-2">Origem / Material</th>
                      <th className="px-3 py-2 text-right">Anterior</th>
                      <th className="px-3 py-2 text-right">Novo</th>
                      <th className="px-3 py-2">Responsável</th>
                      <th className="px-3 py-2">Data</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-orange-100">
                    {reconciliacoesRetiradas.map((evento) => (
                      <tr key={evento.id}>
                        <td className="px-3 py-2">
                          <span className="block text-[10px] font-bold uppercase text-orange-700">
                            {evento.aba}
                          </span>
                          <strong>{evento.material}</strong>
                          <span className="mt-0.5 block text-[11px] text-orange-800">
                            {evento.origem === "EDICAO_MANUAL" ? "Edição manual" : "Planilha"}
                            {evento.motivo ? ` · ${evento.motivo}` : ""}
                          </span>
                        </td>
                        <td className="px-3 py-2 text-right">{formatarNumero(evento.quantidadeAnterior)}</td>
                        <td className="px-3 py-2 text-right font-bold">{formatarNumero(evento.quantidadeNova)}</td>
                        <td className="px-3 py-2">{evento.reconciliadoPor}</td>
                        <td className="px-3 py-2">{new Date(evento.reconciliadoEm).toLocaleString("pt-BR")}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
          <div className="max-h-64 overflow-auto rounded-lg border border-slate-200">
            <table className="w-full min-w-[620px] text-left text-xs">
              <thead className="sticky top-0 bg-slate-100 uppercase text-slate-500">
                <tr>
                  <th className="px-3 py-2">Arquivo</th>
                  <th className="px-3 py-2">Responsável</th>
                  <th className="px-3 py-2">Data</th>
                  <th className="px-3 py-2">Resultado</th>
                  <th className="px-3 py-2 text-center">Detalhes</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {importacoesPlanilha.map((importacao) => (
                  <tr key={importacao.importacaoId}>
                    <td className="px-3 py-2">
                      <strong className="block text-slate-800">{importacao.nomeArquivo}</strong>
                      <span className="text-slate-500">{importacao.deposito}</span>
                      {importacao.tipoImportacao === "ATUALIZACAO_CUSTOS" && (
                        <span className="mt-1 block w-fit rounded bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase text-amber-800">
                          Atualização de custos
                        </span>
                      )}
                      {importacao.tipoImportacao === "SINCRONIZACAO_SALDOS" && (
                        <span className="mt-1 block w-fit rounded bg-cyan-100 px-2 py-0.5 text-[10px] font-bold uppercase text-cyan-800">
                          Sincronização de saldos
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-slate-600">
                      <span className="block">{importacao.importadoPor}</span>
                      {importacao.complementadoPor && (
                        <span className="text-[11px] text-slate-400">
                          Complementado por {importacao.complementadoPor}
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-slate-600">
                      {new Date(importacao.dataImportacao).toLocaleString("pt-BR")}
                    </td>
                    <td className="px-3 py-2 text-slate-600">
                      <span className="block">{importacao.itensProcessados} materiais</span>
                      {!['ATUALIZACAO_CUSTOS', 'SINCRONIZACAO_SALDOS'].includes(importacao.tipoImportacao) && (
                        <span className="block">{importacao.retiradasImportadas} retiradas</span>
                      )}
                      {importacao.faltasIdentificadas > 0 && (
                        <strong className="block text-rose-700">
                          {importacao.faltasIdentificadas} faltas
                        </strong>
                      )}
                    </td>
                    <td className="px-3 py-2 text-center">
                      <button
                        type="button"
                        onClick={() => abrirDetalheImportacao(importacao.importacaoId)}
                        className="rounded border border-slate-200 p-2 text-blue-700 hover:bg-blue-50"
                        title="Ver itens afetados"
                      >
                        <Eye size={15} />
                      </button>
                    </td>
                  </tr>
                ))}
                {importacoesPlanilha.length === 0 && (
                  <tr>
                    <td colSpan="5" className="px-3 py-8 text-center text-slate-400">
                      Nenhuma planilha importada.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {importacaoDetalhe?.carregando && <LoadingSpinner />}
          {importacaoDetalhe && !importacaoDetalhe.carregando && (
            <section className="space-y-3 border-t border-slate-200 pt-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <h3 className="font-bold text-slate-900">{importacaoDetalhe.nomeArquivo}</h3>
                  <p className="text-xs text-slate-500">
                    {importacaoDetalhe.tipoImportacao === "ATUALIZACAO_CUSTOS"
                      ? `${formatarMoeda(importacaoDetalhe.valorTotalImportado)} em estoque após a atualização · saldos preservados`
                      : importacaoDetalhe.tipoImportacao === "SINCRONIZACAO_SALDOS"
                        ? `${formatarMoeda(importacaoDetalhe.valorTotalImportado)} em estoque após a sincronização · custos preservados`
                      : `${formatarMoeda(importacaoDetalhe.valorTotalImportado)} importados · ${importacaoDetalhe.abasRetiradaProcessadas} abas de obra`}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setImportacaoDetalhe(null)}
                  className="text-xs font-bold text-slate-500 hover:text-slate-800"
                >
                  Fechar detalhes
                </button>
              </div>

              <div className="max-h-72 overflow-auto rounded border border-slate-200">
                <table className="w-full min-w-[700px] text-left text-xs">
                  <thead className="sticky top-0 bg-slate-50 uppercase text-slate-500">
                    {importacaoDetalhe.tipoImportacao === "ATUALIZACAO_CUSTOS" ? (
                    <tr>
                      <th className="px-3 py-2">Material</th>
                      <th className="px-3 py-2 text-right">Custo anterior</th>
                      <th className="px-3 py-2 text-right">Custo novo</th>
                      <th className="px-3 py-2 text-right">Linha</th>
                      <th className="px-3 py-2">Resultado</th>
                    </tr>
                    ) : (
                    <tr>
                      <th className="px-3 py-2">Origem / Material</th>
                      <th className="px-3 py-2 text-right">Anterior</th>
                      <th className="px-3 py-2 text-right">
                        {importacaoDetalhe.tipoImportacao === "SINCRONIZACAO_SALDOS"
                          ? "Sincronizado"
                          : "Importado"}
                      </th>
                      <th className="px-3 py-2 text-right">Retirado</th>
                      <th className="px-3 py-2 text-right">Faltante</th>
                      <th className="px-3 py-2">Resultado</th>
                      <th className="px-3 py-2 text-center">Ações</th>
                    </tr>
                    )}
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {importacaoDetalhe.tipoImportacao === "ATUALIZACAO_CUSTOS" ? (
                      (importacaoDetalhe.itens || []).map((item) => (
                        <tr key={`custo-${item.materialId}`}>
                          <td className="px-3 py-2 font-semibold text-slate-800">{item.material}</td>
                          <td className="px-3 py-2 text-right">{formatarMoeda(item.custoAnterior)}</td>
                          <td className="px-3 py-2 text-right font-bold">{formatarMoeda(item.custoUnitario)}</td>
                          <td className="px-3 py-2 text-right">{item.linhaOrigem || "—"}</td>
                          <td className="px-3 py-2">{item.acao.replaceAll("_", " ")}</td>
                        </tr>
                      ))
                    ) : (<>
                    {(importacaoDetalhe.itens || []).map((item) => (
                      <tr key={`estoque-${item.materialId}`}>
                        <td className="px-3 py-2">
                          <span className="block text-[10px] font-bold uppercase text-blue-600">
                            Estoque atual
                          </span>
                          <strong>{item.material}</strong>
                        </td>
                        <td className="px-3 py-2 text-right">{item.saldoAnterior}</td>
                        <td className="px-3 py-2 text-right">{item.saldoImportado}</td>
                        <td className="px-3 py-2 text-right">—</td>
                        <td className="px-3 py-2 text-right">—</td>
                        <td className="px-3 py-2">{item.acao.replaceAll("_", " ")}</td>
                        <td className="px-3 py-2 text-center">—</td>
                      </tr>
                    ))}
                    {(importacaoDetalhe.retiradas || []).map((item, index) => (
                      <tr key={`retirada-${item.aba}-${item.materialId}-${index}`}>
                        <td className="px-3 py-2">
                          <span className="block text-[10px] font-bold uppercase text-amber-700">
                            {item.aba} · {item.comarca}
                          </span>
                          <strong>{item.material}</strong>
                        </td>
                        <td className="px-3 py-2 text-right">{formatarNumero(item.saldoInicial)}</td>
                        <td className="px-3 py-2 text-right">{formatarNumero(item.saldoFinal)}</td>
                        <td className="px-3 py-2 text-right">{formatarNumero(item.quantidadeRetirada)}</td>
                        <td className="px-3 py-2 text-right font-bold text-rose-700">
                          {item.quantidadeFaltante > 0
                            ? formatarNumero(item.quantidadeFaltante)
                            : "—"}
                        </td>
                        <td className="px-3 py-2">
                          {item.quantidadeFaltante > 0 ? "EM FALTA" : "REGISTRADO"}
                        </td>
                        <td className="px-3 py-2 text-center">
                          {podeGerenciarEstoque && item.retiradaImportadaId ? (
                            <button
                              type="button"
                              onClick={() => abrirEdicaoRetiradaHistorica(item)}
                              className="rounded-md border border-slate-200 bg-white p-2 text-slate-600 hover:border-blue-300 hover:text-blue-700"
                              title="Corrigir retirada histórica"
                              aria-label={`Corrigir retirada histórica de ${item.material}`}
                            >
                              <Edit2 size={14} />
                            </button>
                          ) : "—"}
                        </td>
                      </tr>
                    ))}
                    </>)}
                  </tbody>
                </table>
              </div>
            </section>
          )}
            </>
          )}
        </div>
      </Modal>

      <Modal
        isOpen={Boolean(retiradaHistoricaEdicao)}
        onClose={() => {
          if (!retiradaHistoricaSalvando) setRetiradaHistoricaEdicao(null);
        }}
        title="Corrigir retirada histórica"
      >
        {retiradaHistoricaEdicao && (
          <form className="space-y-5" onSubmit={salvarEdicaoRetiradaHistorica}>
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
              <span className="block text-xs font-bold uppercase text-amber-700">
                {retiradaHistoricaEdicao.aba} · {retiradaHistoricaEdicao.comarca}
              </span>
              <strong className="mt-1 block text-slate-900">
                {retiradaHistoricaEdicao.material}
              </strong>
              <span className="mt-1 block text-xs text-slate-500">
                {retiradaHistoricaEdicao.numeroOr || "OR histórica"}
                {retiradaHistoricaEdicao.numeroOs
                  ? ` · ${retiradaHistoricaEdicao.numeroOs}`
                  : ""}
              </span>
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <label className="space-y-1 text-sm font-semibold text-slate-700">
                Quantidade retirada
                <input
                  type="number"
                  required
                  min="0"
                  step="0.001"
                  value={retiradaHistoricaEdicao.quantidadeRetirada}
                  onChange={(event) => setRetiradaHistoricaEdicao((atual) => ({
                    ...atual,
                    quantidadeRetirada: event.target.value,
                  }))}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 font-normal"
                />
              </label>
              <label className="space-y-1 text-sm font-semibold text-slate-700">
                Data da retirada
                <input
                  type="date"
                  value={retiradaHistoricaEdicao.dataRetirada}
                  onChange={(event) => setRetiradaHistoricaEdicao((atual) => ({
                    ...atual,
                    dataRetirada: event.target.value,
                  }))}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 font-normal"
                />
              </label>
            </div>

            <div className="grid grid-cols-3 gap-3 rounded-lg border border-blue-100 bg-blue-50 p-3 text-xs">
              <div>
                <span className="block uppercase text-blue-600">Saldo inicial</span>
                <strong className="text-sm text-blue-950">
                  {formatarNumero(retiradaHistoricaEdicao.saldoInicial)}
                </strong>
              </div>
              <div>
                <span className="block uppercase text-blue-600">Saldo final</span>
                <strong className="text-sm text-blue-950">
                  {formatarNumero(
                    Number(retiradaHistoricaEdicao.saldoInicial || 0)
                    - Number(retiradaHistoricaEdicao.quantidadeRetirada || 0),
                  )}
                </strong>
              </div>
              <div>
                <span className="block uppercase text-blue-600">Em falta</span>
                <strong className="text-sm text-rose-700">
                  {formatarNumero(Math.max(
                    0,
                    Number(retiradaHistoricaEdicao.quantidadeRetirada || 0)
                    - Number(retiradaHistoricaEdicao.saldoInicial || 0),
                  ))}
                </strong>
              </div>
            </div>

            <label className="space-y-1 text-sm font-semibold text-slate-700">
              Motivo da correção
              <textarea
                required
                minLength="5"
                maxLength="500"
                rows="3"
                value={retiradaHistoricaEdicao.motivo}
                onChange={(event) => setRetiradaHistoricaEdicao((atual) => ({
                  ...atual,
                  motivo: event.target.value,
                }))}
                className="w-full resize-none rounded-lg border border-slate-300 px-3 py-2 font-normal"
                placeholder="Ex.: quantidade conferida na OR física da obra."
              />
            </label>

            <Alert
              type="warning"
              message="Esta correção atualiza o histórico, a OR e o consolidado da obra. O saldo atual do estoque não será movimentado, e a alteração ficará registrada na auditoria."
            />

            <div className="flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setRetiradaHistoricaEdicao(null)}
                disabled={retiradaHistoricaSalvando}
                className="rounded-lg border border-slate-300 px-4 py-2 font-semibold text-slate-700"
              >
                Cancelar
              </button>
              <button
                type="submit"
                disabled={retiradaHistoricaSalvando}
                className="rounded-lg bg-blue-700 px-4 py-2 font-bold text-white disabled:opacity-60"
              >
                {retiradaHistoricaSalvando ? "Salvando..." : "Salvar correção"}
              </button>
            </div>
          </form>
        )}
      </Modal>

      <Modal
        isOpen={Boolean(importacaoPreview)}
        onClose={() => {
          if (!importacaoProcessando) {
            setImportacaoPreview(null);
            setImportacaoLocalId("");
            setImportacaoContratoId("");
            setImportacaoResponsavelId("");
          }
        }}
        title={importacaoPreview?.modo === "OR_AVULSA"
          ? "Revisar ordem de retirada"
          : importacaoPreview?.modo === "CUSTOS"
            ? "Revisar atualização de custos"
            : importacaoPreview?.modo === "SALDOS"
              ? "Revisar sincronização de saldos"
            : "Revisar importação do estoque"}
      >
        {importacaoPreview && (
          <div className="space-y-5">
            <div className="rounded-lg border border-blue-200 bg-blue-50 p-4">
              <strong className="block text-sm text-blue-900">
                {importacaoPreview.nomeArquivo}
              </strong>
              <span className="text-xs text-blue-700">
                {importacaoPreview.modo === "OR_AVULSA"
                  ? `Saída calculada sobre o estoque atual · ${importacaoPreview.itens.length} materiais`
                  : importacaoPreview.modo === "CUSTOS"
                    ? `Aba: ${importacaoPreview.abaOrigem} · somente custos · ${importacaoPreview.itens.length} linhas lidas`
                    : importacaoPreview.modo === "SALDOS"
                      ? `Aba: ${importacaoPreview.abaOrigem} · somente saldos · ${importacaoPreview.itens.length} materiais conferidos`
                    : `Catálogo: ${importacaoPreview.abaCatalogo} · saldos conferidos com ${importacaoPreview.abaOrigem} · ${importacaoPreview.itens.length} materiais`}
              </span>
            </div>

            <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
              <div className="rounded border border-slate-200 p-3">
                <span className="block text-xs text-slate-500">
                  {importacaoPreview.modo === "CUSTOS"
                    ? "Não encontrados"
                    : importacaoPreview.modo === "SALDOS" ? "Aumentos" : "Novos"}
                </span>
                <strong className="text-lg text-slate-900">
                  {importacaoPreview.itens.filter((item) => item.acao === (
                    importacaoPreview.modo === "CUSTOS"
                      ? "IGNORAR"
                      : importacaoPreview.modo === "SALDOS" ? "AUMENTAR" : "CRIAR"
                  )).length}
                </strong>
              </div>
              <div className="rounded border border-slate-200 p-3">
                <span className="block text-xs text-slate-500">
                  {importacaoPreview.modo === "OR_AVULSA"
                    ? "Com retirada"
                    : importacaoPreview.modo === "SALDOS" ? "Reduções" : "Atualizados"}
                </span>
                <strong className="text-lg text-slate-900">
                  {importacaoPreview.itens.filter((item) => item.acao === (
                    importacaoPreview.modo === "OR_AVULSA"
                      ? "RETIRAR"
                      : importacaoPreview.modo === "CUSTOS"
                        ? "ATUALIZAR_CUSTO"
                        : importacaoPreview.modo === "SALDOS"
                          ? "REDUZIR"
                        : "ATUALIZAR"
                  )).length}
                </strong>
              </div>
              <div className="rounded border border-slate-200 p-3">
                <span className="block text-xs text-slate-500">Bloqueados</span>
                <strong className="text-lg text-red-700">
                  {importacaoPreview.itens.filter((item) => item.erros.length > 0).length}
                </strong>
              </div>
              <div className="rounded border border-slate-200 p-3">
                <span className="block text-xs text-slate-500">
                  {importacaoPreview.modo === "SALDOS" ? "Total após sincronizar" : "Depois da importação"}
                </span>
                <strong className="text-base text-slate-900">
                  {formatarMoeda(importacaoPreview.valorTotal)}
                </strong>
              </div>
              <div className="rounded border border-slate-200 p-3">
                <span className="block text-xs text-slate-500">
                  {importacaoPreview.modo === "SALDOS" ? "Total atual" : "Antes da importação"}
                </span>
                <strong className="text-base text-slate-900">
                  {formatarMoeda(importacaoPreview.valorTotalAnterior)}
                </strong>
              </div>
            </div>

            {importacaoPreview.modo !== "CUSTOS" && <label className="block">
              <span className="mb-1 block text-sm font-semibold text-slate-700">
                Depósito de referência
              </span>
              <select
                value={importacaoLocalId}
                onChange={(event) => setImportacaoLocalId(event.target.value)}
                required
                className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm"
              >
                <option value="">Selecione o depósito</option>
                {locaisEstoque
                  .filter((local) => local.ativo !== false)
                  .map((local) => (
                    <option key={local.id} value={local.id}>
                      {local.nome}
                    </option>
                  ))}
              </select>
              <span className="mt-1 block text-xs text-slate-500">
                {importacaoPreview.modo === "OR_AVULSA"
                  ? "A retirada será baixada deste depósito até o limite disponível; faltas ficarão registradas na OR."
                  : importacaoPreview.modo === "SALDOS"
                    ? "Aumentos serão creditados aqui. Reduções usarão os depósitos com saldo e ficarão registradas como ajustes."
                  : "Saldos maiores serão creditados aqui. Reduções serão distribuídas pelos depósitos que possuem saldo, sempre com registro de ajuste."}
              </span>
            </label>}

            {!['CUSTOS', 'SALDOS'].includes(importacaoPreview.modo) && <div className="grid gap-3 md:grid-cols-2">
              <label className="block">
                <span className="mb-1 block text-sm font-semibold text-slate-700">
                  Contrato das operações históricas
                </span>
                <select
                  value={importacaoContratoId}
                  onChange={(event) => setImportacaoContratoId(event.target.value)}
                  required
                  className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm"
                >
                  <option value="">Selecione o contrato</option>
                  {contratos.filter((contrato) => !contrato.arquivado).map((contrato) => (
                    <option key={contrato.id} value={contrato.id}>
                      {contrato.contrato} · {contrato.cliente}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block">
                <span className="mb-1 block text-sm font-semibold text-slate-700">
                  Responsável pela importação operacional
                </span>
                <select
                  value={importacaoResponsavelId}
                  onChange={(event) => setImportacaoResponsavelId(event.target.value)}
                  required
                  className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm"
                >
                  <option value="">Selecione o responsável</option>
                  {funcionarios.filter((funcionario) => funcionario.ativo !== false).map((funcionario) => (
                    <option key={funcionario.id} value={funcionario.id}>
                      {funcionario.nome} · {funcionario.funcao}
                    </option>
                  ))}
                </select>
              </label>
            </div>}

            {importacaoPreview.entradas.length > 0 && (
              <section className="space-y-3 rounded-lg border border-emerald-200 bg-emerald-50 p-4">
                <div>
                  <h3 className="text-sm font-bold text-emerald-950">
                    Entradas históricas do cadastro
                  </h3>
                  <p className="mt-1 text-xs text-emerald-800">
                    O estoque inicial e as compras serão preservados para auditoria, sem somar
                    novamente ao saldo consolidado.
                  </p>
                </div>
                <div className="grid gap-2 sm:grid-cols-3">
                  <div className="rounded border border-emerald-200 bg-white p-3">
                    <span className="block text-xs text-slate-500">Eventos</span>
                    <strong className="text-lg text-slate-900">{importacaoPreview.entradas.length}</strong>
                  </div>
                  <div className="rounded border border-emerald-200 bg-white p-3">
                    <span className="block text-xs text-slate-500">Compras</span>
                    <strong className="text-lg text-slate-900">
                      {importacaoPreview.entradas.filter((entrada) => entrada.tipo === "ADICAO").length}
                    </strong>
                  </div>
                  <div className="rounded border border-emerald-200 bg-white p-3">
                    <span className="block text-xs text-slate-500">Valor histórico</span>
                    <strong className="text-base text-slate-900">
                      {formatarMoeda(importacaoPreview.entradas.reduce(
                        (total, entrada) => total + entrada.quantidade * entrada.custoUnitario,
                        0,
                      ))}
                    </strong>
                  </div>
                </div>
              </section>
            )}

            {importacaoPreview.abasRetiradas.length > 0 && (
              <section className="space-y-3 rounded-lg border border-amber-200 bg-amber-50 p-4">
                <div>
                  <h3 className="text-sm font-bold text-amber-950">
                    Retiradas históricas por obra
                  </h3>
                  <p className="mt-1 text-xs text-amber-800">
                    {importacaoPreview.modo === "OR_AVULSA"
                      ? "Cada cidade gerará uma obra e uma OS concluída; cada aba gerará sua própria OR e reduzirá o estoque atual. Quantidades indisponíveis serão registradas como falta."
                      : "Cada cidade gerará uma obra e uma OS concluída; cada aba gerará sua própria OR. As retiradas serão preservadas como histórico e não reduzirão novamente o saldo consolidado da aba ESTOQUE ATUAL."}
                  </p>
                </div>
                <div className="space-y-2">
                  {importacaoPreview.abasRetiradas.map((aba) => (
                    <div
                      key={aba.nome}
                      className="grid gap-2 rounded border border-amber-200 bg-white p-3 md:grid-cols-[1fr_auto]"
                    >
                      <div>
                        <strong className="block text-sm text-slate-800">{aba.nome}</strong>
                        <span className="text-xs text-slate-500">
                          {aba.itens.length} itens · {formatarNumero(aba.totalRetirado)} retirados
                          {" · "}{aba.faltas} faltas
                        </span>
                      </div>
                      <span className="self-center rounded bg-amber-100 px-2 py-1 text-xs font-bold text-amber-900">
                        {aba.cidade}
                      </span>
                    </div>
                  ))}
                </div>
              </section>
            )}
            {reconciliacaoPreview && (
              <section className={`space-y-3 rounded-lg border p-4 ${
                reconciliacaoPreview.divergencias > 0
                  ? "border-orange-300 bg-orange-50"
                  : "border-emerald-200 bg-emerald-50"
              }`}>
                <div>
                  <h3 className="text-sm font-bold text-slate-900">
                    Comparação com o histórico das ORs
                  </h3>
                  <p className="mt-1 text-xs text-slate-600">
                    {reconciliacaoPreview.divergencias > 0
                      ? `${reconciliacaoPreview.divergencias} alteração(ões) aguardando confirmação. O estoque atual não será movimentado.`
                      : "Nenhuma diferença foi encontrada nas retiradas históricas."}
                  </p>
                </div>
                {reconciliacaoPreview.itens?.length > 0 && (
                  <div className="max-h-52 overflow-auto rounded border border-orange-200 bg-white">
                    <table className="w-full min-w-[760px] text-left text-xs">
                      <thead className="sticky top-0 bg-orange-100 uppercase text-orange-900">
                        <tr>
                          <th className="px-3 py-2">Origem / Material</th>
                          <th className="px-3 py-2 text-right">Retirada anterior</th>
                          <th className="px-3 py-2 text-right">Nova retirada</th>
                          <th className="px-3 py-2 text-right">Saldo anterior</th>
                          <th className="px-3 py-2 text-right">Novo saldo</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-orange-100">
                        {reconciliacaoPreview.itens.map((item) => (
                          <tr key={item.retiradaImportadaId}>
                            <td className="px-3 py-2">
                              <span className="block text-[10px] font-bold uppercase text-orange-700">
                                {item.aba}
                              </span>
                              <strong>{item.material}</strong>
                            </td>
                            <td className="px-3 py-2 text-right">{formatarNumero(item.quantidadeAnterior)}</td>
                            <td className="px-3 py-2 text-right font-bold">{formatarNumero(item.quantidadeNova)}</td>
                            <td className="px-3 py-2 text-right">{formatarNumero(item.saldoFinalAnterior)}</td>
                            <td className="px-3 py-2 text-right font-bold">{formatarNumero(item.saldoFinalNovo)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            )}
            {importacaoPreview.simulacao.length > 0 && (
              <section className="space-y-3 rounded-lg border border-slate-200 bg-slate-50 p-4">
                <div>
                  <h3 className="text-sm font-bold text-slate-900">Simulação da planilha</h3>
                  <p className="mt-1 text-xs text-slate-600">
                    Recalculada pelo sistema a partir do saldo consolidado, sem depender das fórmulas
                    incompatíveis da aba SIMULACAO.
                  </p>
                </div>
                <div className="max-h-40 overflow-auto rounded border border-slate-200 bg-white">
                  <table className="w-full min-w-[520px] text-left text-xs">
                    <thead className="bg-slate-100 uppercase text-slate-500">
                      <tr>
                        <th className="px-3 py-2">Material</th>
                        <th className="px-3 py-2 text-right">Atual</th>
                        <th className="px-3 py-2 text-right">Pedido</th>
                        <th className="px-3 py-2 text-right">Resultado</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {importacaoPreview.simulacao.map((item) => (
                        <tr key={normalizarTextoPlanilha(item.nomeMaterial)}>
                          <td className="px-3 py-2 font-semibold text-slate-800">{item.nomeMaterial}</td>
                          <td className="px-3 py-2 text-right">{formatarNumero(item.estoqueAtual)}</td>
                          <td className="px-3 py-2 text-right">{formatarNumero(item.quantidadeSimulada)}</td>
                          <td className={`px-3 py-2 text-right font-bold ${
                            item.quantidadeFaltante > 0 ? "text-red-700" : "text-emerald-700"
                          }`}>
                            {item.quantidadeFaltante > 0
                              ? `Faltam ${formatarNumero(item.quantidadeFaltante)}`
                              : `Saldo ${formatarNumero(item.saldoFinal)}`}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            )}
            {importacaoPreview.avisos.length > 0 && (
              <div className="space-y-2">
                <Alert
                  type="error"
                  message={`${importacaoPreview.avisos.length} pendência(s) encontrada(s); a importação completa permanece bloqueada.`}
                />
                {(() => {
                  const validacao = resumirAvisosImportacao(
                    importacaoPreview.avisos,
                    importacaoPreview.avisosRetiradas,
                  );
                  return (
                    <div className="grid gap-2 sm:grid-cols-2">
                      <div className="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-700">
                        <strong className="block">Cadastro, saldos e retornos</strong>
                        {validacao.gerais} pendência(s). Bloqueiam a importação completa.
                      </div>
                      <div className={`rounded border px-3 py-2 text-xs ${
                        validacao.bloqueiaReconciliacao
                          ? "border-red-200 bg-red-50 text-red-700"
                          : "border-emerald-200 bg-emerald-50 text-emerald-700"
                      }`}>
                        <strong className="block">Histórico das ORs</strong>
                        {validacao.bloqueiaReconciliacao
                          ? `${validacao.retiradas} pendência(s) impedem a comparação.`
                          : "Sem pendências próprias; a comparação está disponível."}
                      </div>
                    </div>
                  );
                })()}
                <ul className="max-h-32 overflow-auto rounded border border-red-200 bg-red-50 px-4 py-2 text-xs text-red-700">
                  {importacaoPreview.avisos.map((aviso, indice) => (
                    <li key={`${indice}-${aviso}`} className="py-0.5">
                      {aviso}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <div className="max-h-72 overflow-auto rounded-lg border border-slate-200">
              {importacaoPreview.modo === "CUSTOS" ? (
              <table className="w-full min-w-[760px] text-left text-xs">
                <thead className="sticky top-0 bg-slate-100 uppercase text-slate-500">
                  <tr>
                    <th className="px-3 py-2">Material na planilha</th>
                    <th className="px-3 py-2">Material no estoque</th>
                    <th className="px-3 py-2 text-right">Custo atual</th>
                    <th className="px-3 py-2 text-right">Valor informado</th>
                    <th className="px-3 py-2 text-right">Novo custo</th>
                    <th className="px-3 py-2">Ação</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {importacaoPreview.itens.map((item) => (
                    <tr key={`${normalizarTextoPlanilha(item.nome)}-${item.linhaOrigem}`}>
                      <td className="px-3 py-2">
                        <strong className="block text-slate-800">{item.nome}</strong>
                        <span className="text-[11px] text-slate-400">Linha {item.linhaOrigem}</span>
                        {item.erros.map((erro) => (
                          <span key={erro} className="block text-[11px] text-red-600">{erro}</span>
                        ))}
                      </td>
                      <td className="px-3 py-2 text-slate-700">{item.materialNome || "Não encontrado"}</td>
                      <td className="px-3 py-2 text-right">{item.materialId ? formatarMoeda(item.custoAnterior) : "—"}</td>
                      <td className="px-3 py-2 text-right">
                        {formatarMoeda(item.custoInformado)}
                        {item.conversaoBobina && (
                          <span className="block text-[11px] text-slate-400">por bobina de 305 m</span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-right font-bold">
                        {item.materialId ? formatarMoeda(item.custoUnitario) : "—"}
                        {item.conversaoBobina && item.materialId && (
                          <span className="block text-[11px] text-blue-600">custo por metro</span>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <span className={`rounded px-2 py-1 font-bold ${
                          item.erros.length
                            ? "bg-red-100 text-red-700"
                            : item.acao === "IGNORAR"
                              ? "bg-slate-100 text-slate-600"
                              : item.acao === "SEM_ALTERACAO"
                                ? "bg-emerald-100 text-emerald-700"
                                : "bg-amber-100 text-amber-700"
                        }`}>
                          {item.erros.length ? "BLOQUEADO" : item.acao.replaceAll("_", " ")}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              ) : importacaoPreview.modo === "SALDOS" ? (
              <table className="w-full min-w-[900px] text-left text-xs">
                <thead className="sticky top-0 bg-slate-100 uppercase text-slate-500">
                  <tr>
                    <th className="px-3 py-2">Material</th>
                    <th className="px-3 py-2 text-right">Atual</th>
                    <th className="px-3 py-2 text-right">Planilha</th>
                    <th className="px-3 py-2 text-right">Diferença</th>
                    <th className="px-3 py-2 text-right">Valor atual</th>
                    <th className="px-3 py-2 text-right">Valor após</th>
                    <th className="px-3 py-2">Ação</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {importacaoPreview.itens.map((item) => (
                    <tr key={`${normalizarTextoPlanilha(item.nome)}-${item.linhaOrigem}`}>
                      <td className="px-3 py-2">
                        <strong className="block text-slate-800">{item.nome}</strong>
                        <span className="text-[11px] text-slate-400">Linha {item.linhaOrigem}</span>
                        {item.quantidadeFaltante > 0 && (
                          <span className="block text-[11px] font-semibold text-red-700">
                            Planilha informa falta de {formatarNumero(item.quantidadeFaltante)}; saldo será zero
                          </span>
                        )}
                        {item.erros.map((erro) => (
                          <span key={erro} className="block text-[11px] text-red-600">{erro}</span>
                        ))}
                      </td>
                      <td className="px-3 py-2 text-right">{formatarNumero(item.saldoAtual)}</td>
                      <td className="px-3 py-2 text-right font-bold">{formatarNumero(item.saldo)}</td>
                      <td className={`px-3 py-2 text-right font-bold ${
                        item.diferenca > 0
                          ? "text-emerald-700"
                          : item.diferenca < 0 ? "text-red-700" : "text-slate-500"
                      }`}>
                        {item.diferenca > 0 ? "+" : ""}{formatarNumero(item.diferenca)}
                      </td>
                      <td className="px-3 py-2 text-right">{formatarMoeda(item.valorAnterior)}</td>
                      <td className="px-3 py-2 text-right font-bold">{formatarMoeda(item.valorTotal)}</td>
                      <td className="px-3 py-2">
                        <span className={`rounded px-2 py-1 font-bold ${
                          item.erros.length
                            ? "bg-red-100 text-red-700"
                            : item.acao === "AUMENTAR"
                              ? "bg-emerald-100 text-emerald-700"
                              : item.acao === "REDUZIR"
                                ? "bg-amber-100 text-amber-800"
                                : "bg-slate-100 text-slate-600"
                        }`}>
                          {item.erros.length ? "BLOQUEADO" : item.acao.replaceAll("_", " ")}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              ) : (
              <table className="w-full min-w-[580px] text-left text-xs">
                <thead className="sticky top-0 bg-slate-100 uppercase text-slate-500">
                  <tr>
                    <th className="px-3 py-2">Material</th>
                    <th className="px-3 py-2 text-right">Atual</th>
                    <th className="px-3 py-2 text-right">
                      {importacaoPreview.modo === "OR_AVULSA" ? "Solicitado" : "Planilha"}
                    </th>
                    <th className="px-3 py-2 text-right">Custo</th>
                    <th className="px-3 py-2">Ação</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {importacaoPreview.itens.map((item) => (
                    <tr key={normalizarTextoPlanilha(item.nome)}>
                      <td className="px-3 py-2">
                        <strong className="block text-slate-800">{item.nome}</strong>
                        {item.erros.map((erro) => (
                          <span key={erro} className="block text-[11px] text-red-600">
                            {erro}
                          </span>
                        ))}
                      </td>
                      <td className="px-3 py-2 text-right">{formatarNumero(item.saldoAtual)}</td>
                      <td className="px-3 py-2 text-right font-bold">
                        {formatarNumero(importacaoPreview.modo === "OR_AVULSA"
                          ? item.quantidadeSolicitada
                          : item.saldo)}
                        {importacaoPreview.modo === "OR_AVULSA" && item.quantidadeFaltante > 0 && (
                          <span className="block text-[11px] text-red-700">
                            Falta {formatarNumero(item.quantidadeFaltante)}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-right">{formatarMoeda(item.custoUnitario)}</td>
                      <td className="px-3 py-2">
                        <span
                          className={`rounded px-2 py-1 font-bold ${
                            item.erros.length
                              ? "bg-red-100 text-red-700"
                              : item.quantidadeFaltante > 0
                                ? "bg-red-100 text-red-700"
                              : item.acao === "CRIAR"
                                ? "bg-blue-100 text-blue-700"
                                : "bg-amber-100 text-amber-700"
                          }`}
                        >
                          {item.erros.length
                            ? "BLOQUEADO"
                            : item.quantidadeFaltante > 0
                              ? "EM FALTA"
                              : item.acao}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              )}
            </div>

            <div className="rounded bg-slate-50 p-3 text-xs text-slate-600">
              {importacaoPreview.modo === "OR_AVULSA"
                ? "Esta é uma ordem de retirada avulsa. O saldo atual do sistema é a fonte de verdade; a confirmação criará a OS/OR e aplicará somente a saída possível. "
                : importacaoPreview.modo === "CUSTOS"
                  ? "Esta operação atualiza somente o custo médio dos materiais encontrados. Quantidades, reservas, faltas e movimentações não serão alteradas. Linhas não encontradas serão ignoradas. "
                  : importacaoPreview.modo === "SALDOS"
                    ? "Esta operação sincroniza somente os saldos da aba ESTOQUE ATUAL. Custos, reservas, cadastro, projetos, OS, OR, retiradas e retornos não serão criados nem alterados. Cada diferença ficará registrada como ajuste de inventário. "
                  : `A aba ESTOQUE ATUAL é o snapshot final e será a única fonte de saldo. Foram encontrados ${importacaoPreview.entradas.length} entradas, ${importacaoPreview.retornos.length} retornos históricos e ${importacaoPreview.simulacao.filter((item) => item.quantidadeFaltante > 0).length} faltas na simulação. `}
              O hash impede reaplicar o mesmo arquivo.
            </div>

            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div>
                {!['OR_AVULSA', 'CUSTOS', 'SALDOS'].includes(importacaoPreview.modo) && importacaoPreview.abasRetiradas.length > 0 && (
                  <button
                    type="button"
                    onClick={() => reconciliarHistoricoRetiradas(
                      Boolean(reconciliacaoPreview?.divergencias),
                    )}
                    disabled={
                      reconciliacaoProcessando
                      || resumirAvisosImportacao(
                        importacaoPreview.avisos,
                        importacaoPreview.avisosRetiradas,
                      ).bloqueiaReconciliacao
                    }
                    className={`rounded-lg px-4 py-2 text-sm font-bold disabled:cursor-not-allowed disabled:bg-slate-300 ${
                      reconciliacaoPreview?.divergencias
                        ? "bg-orange-600 text-white hover:bg-orange-700"
                        : "border border-orange-300 text-orange-800 hover:bg-orange-50"
                    }`}
                  >
                    {reconciliacaoProcessando
                      ? "Comparando..."
                      : reconciliacaoPreview?.divergencias
                        ? `Aplicar ${reconciliacaoPreview.divergencias} correção(ões)`
                        : "Comparar histórico das ORs"}
                  </button>
                )}
              </div>
              <div className="flex justify-end gap-3">
              <button
                type="button"
                onClick={() => {
                  setImportacaoPreview(null);
                  setImportacaoLocalId("");
                  setImportacaoContratoId("");
                  setImportacaoResponsavelId("");
                }}
                disabled={importacaoProcessando}
                className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-700"
              >
                Cancelar
              </button>
              <button
                type="button"
                onClick={confirmarImportacaoPlanilha}
                disabled={
                  importacaoProcessando
                  || (!["CUSTOS", "SALDOS"].includes(importacaoPreview.modo) && (
                    !importacaoLocalId || !importacaoContratoId || !importacaoResponsavelId
                  ))
                  || (importacaoPreview.modo === "SALDOS" && !importacaoLocalId)
                  || (importacaoPreview.modo === "CUSTOS"
                    && !importacaoPreview.itens.some((item) => item.materialId && item.erros.length === 0))
                  || importacaoPreview.avisos.length > 0
                  || importacaoPreview.itens.some((item) => item.erros.length > 0)
                }
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-bold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                {importacaoProcessando
                  ? "Processando..."
                  : importacaoPreview.modo === "SALDOS"
                    ? "Confirmar sincronização"
                    : "Confirmar importação"}
              </button>
              </div>
            </div>
          </div>
        )}
      </Modal>

      <Modal
        isOpen={Boolean(notaFiscalPreview)}
        onClose={() => {
          if (!notaFiscalProcessando) {
            setNotaFiscalPreview(null);
            setNotaFiscalLocalId("");
          }
        }}
        title="Revisar entrada por nota fiscal"
      >
        {notaFiscalPreview && (
          <div className="space-y-5">
            <div className="flex flex-col gap-2 rounded border border-emerald-200 bg-emerald-50 p-4 sm:flex-row sm:items-center sm:justify-between">
              <div><strong className="block text-sm text-emerald-950">{notaFiscalPreview.nomeArquivo}</strong><span className="text-xs text-emerald-800">{notaFiscalPreview.tipoArquivo} · o estoque ainda não foi alterado</span></div>
              <span className="text-xs font-bold uppercase text-emerald-800">Revisão obrigatória</span>
            </div>

            {(notaFiscalPreview.avisos || []).length > 0 && (
              <div className="rounded border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
                {(notaFiscalPreview.avisos || []).map((aviso) => <p key={aviso}>{aviso}</p>)}
              </div>
            )}

            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <label className="text-xs font-semibold text-slate-600">Número da NF<input value={notaFiscalPreview.numero || ""} onChange={(event) => setNotaFiscalPreview((atual) => ({ ...atual, numero: event.target.value }))} className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm" /></label>
              <label className="text-xs font-semibold text-slate-600">Série<input value={notaFiscalPreview.serie || ""} onChange={(event) => setNotaFiscalPreview((atual) => ({ ...atual, serie: event.target.value }))} className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm" /></label>
              <label className="text-xs font-semibold text-slate-600">Emissão<input type="datetime-local" value={notaFiscalPreview.dataEmissao?.slice(0, 16) || ""} onChange={(event) => setNotaFiscalPreview((atual) => ({ ...atual, dataEmissao: event.target.value || null }))} className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm" /></label>
              <label className="text-xs font-semibold text-slate-600">Depósito *<select required value={notaFiscalLocalId} onChange={(event) => setNotaFiscalLocalId(event.target.value)} className="mt-1 w-full rounded border border-slate-300 bg-white px-3 py-2 text-sm"><option value="">Selecione</option>{locaisEstoque.filter((local) => local.ativo !== false).map((local) => <option key={local.id} value={local.id}>{local.nome}</option>)}</select></label>
              <label className="text-xs font-semibold text-slate-600 sm:col-span-2">Emitente<input value={notaFiscalPreview.emitenteNome || ""} onChange={(event) => setNotaFiscalPreview((atual) => ({ ...atual, emitenteNome: event.target.value }))} className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm" /></label>
              <label className="text-xs font-semibold text-slate-600">CNPJ<input value={notaFiscalPreview.emitenteCnpj || ""} onChange={(event) => setNotaFiscalPreview((atual) => ({ ...atual, emitenteCnpj: event.target.value }))} className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm" /></label>
              <label className="text-xs font-semibold text-slate-600">Chave de acesso<input value={notaFiscalPreview.chaveAcesso || ""} onChange={(event) => setNotaFiscalPreview((atual) => ({ ...atual, chaveAcesso: event.target.value }))} className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm" /></label>
            </div>

            <div className="flex items-center justify-between gap-3">
              <div><h3 className="text-sm font-bold text-slate-900">Itens da entrada</h3><p className="text-xs text-slate-500">Vincule a um item existente ou deixe “Criar novo material”.</p></div>
              <button type="button" onClick={adicionarItemNotaFiscal} className="flex items-center gap-1 rounded border border-blue-200 px-3 py-2 text-xs font-bold text-blue-700 hover:bg-blue-50"><Plus size={15} /> Item</button>
            </div>

            <div className="max-h-[48vh] overflow-auto rounded border border-slate-200">
              <table className="w-full min-w-[1320px] text-left text-xs">
                <thead className="sticky top-0 z-10 bg-slate-100 uppercase text-slate-500"><tr><th className="px-2 py-2">Usar</th><th className="px-2 py-2">Material no sistema</th><th className="px-2 py-2">Nome / descrição</th><th className="px-2 py-2">Código do produto</th><th className="px-2 py-2">Part Number</th><th className="px-2 py-2">Qtd.</th><th className="px-2 py-2">Valor unit.</th><th className="px-2 py-2">Controle</th><th className="px-2 py-2">Categoria</th><th className="w-10 px-2 py-2"><span className="sr-only">Remover</span></th></tr></thead>
                <tbody className="divide-y divide-slate-100">
                  {notaFiscalPreview.itens.map((item, indice) => (
                    <tr key={`${item.codigoProduto || "item"}-${indice}`} className={!item.importar ? "bg-slate-50 opacity-60" : "bg-white"}>
                      <td className="px-2 py-2 text-center"><input type="checkbox" checked={item.importar} onChange={(event) => atualizarItemNotaFiscal(indice, "importar", event.target.checked)} /></td>
                      <td className="px-2 py-2"><select value={item.materialId} onChange={(event) => atualizarItemNotaFiscal(indice, "materialId", event.target.value)} className="w-56 rounded border border-slate-300 bg-white px-2 py-2"><option value="">Criar novo material</option>{materiais.map((material) => <option key={material.id} value={material.id}>{material.nome} · {material.partNumber}</option>)}</select></td>
                      <td className="px-2 py-2"><input required={item.importar && !item.materialId} disabled={Boolean(item.materialId)} value={item.nome || ""} onChange={(event) => atualizarItemNotaFiscal(indice, "nome", event.target.value)} placeholder="Nome do material" className="mb-1 w-64 rounded border border-slate-300 px-2 py-1.5 disabled:bg-slate-100" /><input required={item.importar} value={item.descricao || ""} onChange={(event) => atualizarItemNotaFiscal(indice, "descricao", event.target.value)} placeholder="Descrição fiscal" className="w-64 rounded border border-slate-300 px-2 py-1.5" /></td>
                      <td className="px-2 py-2"><input value={item.codigoProduto || ""} onChange={(event) => atualizarItemNotaFiscal(indice, "codigoProduto", event.target.value)} placeholder="Código da NF" className="w-36 rounded border border-slate-300 px-2 py-2" /></td>
                      <td className="px-2 py-2"><input disabled={Boolean(item.materialId)} value={item.partNumber || ""} onChange={(event) => atualizarItemNotaFiscal(indice, "partNumber", event.target.value)} placeholder="Gerado se vazio" className="w-36 rounded border border-slate-300 px-2 py-2 disabled:bg-slate-100" /></td>
                      <td className="px-2 py-2"><input type="number" min="0.001" step="0.001" required={item.importar} value={item.quantidade} onChange={(event) => atualizarItemNotaFiscal(indice, "quantidade", event.target.value)} className="w-24 rounded border border-slate-300 px-2 py-2 text-right" /></td>
                      <td className="px-2 py-2"><input type="number" min="0" step="0.0001" required={item.importar} value={item.valorUnitario} onChange={(event) => atualizarItemNotaFiscal(indice, "valorUnitario", event.target.value)} className="w-28 rounded border border-slate-300 px-2 py-2 text-right" /></td>
                      <td className="px-2 py-2"><select disabled={Boolean(item.materialId)} value={item.tipoControle} onChange={(event) => atualizarItemNotaFiscal(indice, "tipoControle", event.target.value)} className="w-36 rounded border border-slate-300 bg-white px-2 py-2 disabled:bg-slate-100"><option value="UNIDADE">Unidade</option><option value="METRAGEM">Metragem</option></select></td>
                      <td className="px-2 py-2"><select disabled={Boolean(item.materialId)} value={item.categoria} onChange={(event) => atualizarItemNotaFiscal(indice, "categoria", event.target.value)} className="w-40 rounded border border-slate-300 bg-white px-2 py-2 disabled:bg-slate-100">{CATEGORIAS_MATERIAL.map((categoria) => <option key={categoria.value} value={categoria.value}>{categoria.label}</option>)}</select></td>
                      <td className="px-2 py-2"><button type="button" onClick={() => setNotaFiscalPreview((atual) => ({ ...atual, itens: atual.itens.filter((_, posicao) => posicao !== indice) }))} className="rounded p-2 text-slate-400 hover:bg-red-50 hover:text-red-700" title="Remover linha"><Trash2 size={16} /></button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="flex flex-col gap-3 border-t border-slate-200 pt-4 sm:flex-row sm:items-center sm:justify-between">
              <div className="text-sm text-slate-600"><strong className="text-slate-900">{notaFiscalPreview.itens.filter((item) => item.importar).length}</strong> itens selecionados · <strong className="text-slate-900">{formatarMoeda(notaFiscalPreview.itens.filter((item) => item.importar).reduce((total, item) => total + Number(item.quantidade || 0) * Number(item.valorUnitario || 0), 0))}</strong></div>
              <div className="flex justify-end gap-2"><button type="button" onClick={() => { setNotaFiscalPreview(null); setNotaFiscalLocalId(""); }} disabled={notaFiscalProcessando} className="rounded border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-700">Cancelar</button><button type="button" onClick={confirmarImportacaoNotaFiscal} disabled={notaFiscalProcessando || !notaFiscalLocalId || notaFiscalPreview.itens.every((item) => !item.importar)} className="rounded bg-emerald-700 px-4 py-2 text-sm font-bold text-white hover:bg-emerald-800 disabled:bg-slate-300">{notaFiscalProcessando ? "Importando..." : "Confirmar entrada"}</button></div>
            </div>
          </div>
        )}
      </Modal>

      <Modal
        isOpen={Boolean(fotoExpandida)}
        onClose={() => setFotoExpandida(null)}
        title={fotoExpandida?.nome || "Foto do Produto"}
      >
        <div className="space-y-3">
          {fotoExpandida?.fotoProdutoUrl && (
            <img
              src={fotoExpandida.fotoProdutoUrl}
              alt={`Foto ampliada de ${fotoExpandida.nome}`}
              className="max-h-[70vh] w-full rounded-lg border border-slate-200 bg-slate-950 object-contain"
            />
          )}
          {fotoExpandida?.descricao && (
            <p className="rounded-lg bg-slate-50 p-3 text-sm text-slate-600">
              {fotoExpandida.descricao}
            </p>
          )}
        </div>
      </Modal>

      {/*   MODAL NOVO: Cadastro de Material do Catálogo */}
      <Modal
        isOpen={showNovoMaterialModal}
        onClose={handleCloseModal}
        title={materialEmEdicao ? "Editar Material do Estoque" : "Cadastrar Novo Material no Catálogo"}
      >
        <form onSubmit={handleSubmitNovoMaterial} className="space-y-4">
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1">
              Nome do Material
            </label>
            <input
              type="text"
              name="nome"
              value={novoMaterialData.nome}
              onChange={handleNovoMaterialChange}
              required
              className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:ring-2 focus:ring-slate-500 focus:outline-none"
              placeholder="Ex: Roteador Wireless Mikrotik"
            />
          </div>
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1">
              Categoria
            </label>
            <select
              name="categoria"
              value={novoMaterialData.categoria}
              onChange={handleNovoMaterialChange}
              className="w-full rounded-lg border border-slate-300 px-4 py-2 focus:outline-none focus:ring-2 focus:ring-slate-500"
            >
              {CATEGORIAS_MATERIAL.map((categoria) => (
                <option key={categoria.value} value={categoria.value}>
                  {categoria.label}
                </option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1">
                Tipo de controle
              </label>
              <select
                name="tipoControle"
                value={novoMaterialData.tipoControle}
                onChange={handleNovoMaterialChange}
                className="w-full rounded-lg border border-slate-300 px-4 py-2 focus:outline-none focus:ring-2 focus:ring-slate-500"
              >
                {TIPOS_CONTROLE.map((tipo) => (
                  <option key={tipo.value} value={tipo.value}>
                    {tipo.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1">
                Unidade de medida
              </label>
              <select
                name="unidadeMedida"
                value={novoMaterialData.unidadeMedida}
                onChange={handleNovoMaterialChange}
                disabled={novoMaterialData.tipoControle !== "UNIDADE"}
                className="w-full rounded-lg border border-slate-300 px-4 py-2 disabled:bg-slate-100 focus:outline-none focus:ring-2 focus:ring-slate-500"
              >
                {UNIDADES_MEDIDA.map((unidade) => (
                  <option key={unidade.value} value={unidade.value}>
                    {unidade.label}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1">
                Tamanho / dimensão
              </label>
              <input
                type="text"
                name="dimensao"
                value={novoMaterialData.dimensao}
                onChange={handleNovoMaterialChange}
                className="w-full rounded-lg border border-slate-300 px-4 py-2 focus:outline-none focus:ring-2 focus:ring-slate-500"
                placeholder="Ex: 20 mm ou 50 x 50 mm"
              />
            </div>
            {novoMaterialData.tipoControle === "PECA_COM_COMPRIMENTO" && (
              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1">
                  Comprimento por peça (m)
                </label>
                <input
                  type="number"
                  name="comprimentoPorPeca"
                  min="0.001"
                  step="0.001"
                  required
                  value={novoMaterialData.comprimentoPorPeca}
                  onChange={handleNovoMaterialChange}
                  className="w-full rounded-lg border border-slate-300 px-4 py-2 focus:outline-none focus:ring-2 focus:ring-slate-500"
                  placeholder="3"
                />
              </div>
            )}
          </div>
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1">
              Descrição
            </label>
            <textarea
              name="descricao"
              rows="3"
              value={novoMaterialData.descricao}
              onChange={handleNovoMaterialChange}
              className="w-full resize-none rounded-lg border border-slate-300 px-4 py-2 focus:outline-none focus:ring-2 focus:ring-slate-500"
              placeholder="Descreva aplicação, especificações, observações de uso ou retorno."
            />
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {!materialEmEdicao && !["BOBINA", "ROLO"].includes(novoMaterialData.tipoControle) && (
              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1">
                  {novoMaterialData.tipoControle === "METRAGEM"
                    ? "Metragem inicial"
                    : "Quantidade inicial"}
                </label>
                <input
                  type="number"
                  name={
                    novoMaterialData.tipoControle === "METRAGEM"
                      ? "metragemDisponivel"
                      : "quantidadeDisponivel"
                  }
                  min="0"
                  step={novoMaterialData.tipoControle === "METRAGEM" ? "0.001" : "1"}
                  value={
                    novoMaterialData.tipoControle === "METRAGEM"
                      ? novoMaterialData.metragemDisponivel
                      : novoMaterialData.quantidadeDisponivel
                  }
                  onChange={handleNovoMaterialChange}
                  className="w-full rounded-lg border border-slate-300 px-4 py-2 focus:outline-none focus:ring-2 focus:ring-slate-500"
                  placeholder="0"
                />
              </div>
            )}
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1">
                Estoque mínimo ({UNIDADES_MEDIDA.find((item) => item.value === novoMaterialData.unidadeMedida)?.label})
              </label>
              <input
                type="number"
                name="estoqueMinimo"
                min="0"
                step={novoMaterialData.tipoControle === "METRAGEM" ? "0.001" : "1"}
                value={novoMaterialData.estoqueMinimo}
                onChange={handleNovoMaterialChange}
                className="w-full rounded-lg border border-slate-300 px-4 py-2 focus:outline-none focus:ring-2 focus:ring-slate-500"
                placeholder="0"
              />
            </div>
          </div>
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1">
              Custo médio unitário (R$)
            </label>
            <input
              type="number"
              name="custoMedio"
              min="0"
              step="0.0001"
              required
              value={novoMaterialData.custoMedio}
              onChange={handleNovoMaterialChange}
              className="w-full rounded-lg border border-slate-300 px-4 py-2 focus:outline-none focus:ring-2 focus:ring-slate-500"
              placeholder="0,0000"
            />
            <p className="mt-1 text-xs text-slate-500">
              {["FRACIONADO", "METRAGEM", "BOBINA", "ROLO"].includes(novoMaterialData.tipoControle)
                ? "Informe o custo médio por metro."
                : "Informe o custo médio por unidade cadastrada."}
            </p>
          </div>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-[1fr_96px] md:items-end">
            <div className="space-y-3">
              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1">
                  Foto do Produto
                </label>
                <input
                  type="url"
                  name="fotoProdutoUrl"
                  value={
                    novoMaterialData.fotoProdutoUrl?.startsWith("data:")
                      ? ""
                      : novoMaterialData.fotoProdutoUrl
                  }
                  onChange={handleNovoMaterialChange}
                  className="w-full rounded-lg border border-slate-300 px-4 py-2 focus:outline-none focus:ring-2 focus:ring-slate-500"
                  placeholder="https://exemplo.com/foto-produto.png"
                />
              </div>
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                onChange={handleNovoMaterialFotoChange}
                className="w-full text-sm text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-slate-100 file:px-3 file:py-2 file:text-sm file:font-bold file:text-slate-700 hover:file:bg-slate-200"
              />
            </div>
            <div className="flex h-24 w-24 items-center justify-center rounded-lg border border-dashed border-slate-300 bg-slate-50">
              {novoMaterialData.fotoProdutoUrl ? (
                <img
                  src={novoMaterialData.fotoProdutoUrl}
                  alt="Preview do produto"
                  className="h-full w-full rounded-lg object-cover"
                />
              ) : (
                <Package size={22} className="text-slate-400" />
              )}
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1">
                Part Number
              </label>
              <input
                type="text"
                name="partNumber"
                value={novoMaterialData.partNumber}
                onChange={handleNovoMaterialChange}
                required
                className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:ring-2 focus:ring-slate-500 focus:outline-none"
                placeholder="Ex: PN-MK-951"
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1">
                Fabricante
              </label>
              <input
                type="text"
                name="fabricante"
                value={novoMaterialData.fabricante}
                onChange={handleNovoMaterialChange}
                className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:ring-2 focus:ring-slate-500 focus:outline-none"
                placeholder="Ex: Mikrotik"
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1">
                Fornecedor
              </label>
              <input
                type="text"
                name="fornecedor"
                value={novoMaterialData.fornecedor}
                onChange={handleNovoMaterialChange}
                className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:ring-2 focus:ring-slate-500 focus:outline-none"
                placeholder="Ex: WDC Networks"
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1">
                Localização no Estoque
              </label>
              <input
                type="text"
                name="localizacao"
                value={novoMaterialData.localizacao}
                onChange={handleNovoMaterialChange}
                className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:ring-2 focus:ring-slate-500 focus:outline-none"
                placeholder="Ex: Armário C - Gaveta 3"
              />
            </div>
          </div>
          <div className="flex gap-3 justify-end pt-4">
            <button
              type="button"
              onClick={handleCloseModal}
              className="px-6 py-2 border rounded-lg text-slate-700 hover:bg-slate-50"
            >
              Cancelar
            </button>
            <button
              type="submit"
              className="px-6 py-2 bg-slate-700 hover:bg-slate-800 text-white font-semibold rounded-lg"
            >
              {materialEmEdicao ? "Salvar Alterações" : "Cadastrar Produto"}
            </button>
          </div>
        </form>
      </Modal>

      {/* Modal Entrada */}
      <Modal
        isOpen={showEntradaModal}
        onClose={handleCloseModal}
        title="Entrada de Material"
      >
        <form onSubmit={handleSubmitEntrada} className="space-y-4">
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1">
              Selecionar Material
            </label>
            <select
              name="materialId"
              value={formData.materialId}
              onChange={handleInputChange}
              required
              className="w-full px-4 py-2 border border-slate-300 rounded-lg"
            >
              <option value="">-- Selecione um material --</option>
              {materiais.filter((material) => !rastreavel(material)).map((m) => (
                <option key={m.id} value={m.id}>
                  {m.nome} ({m.partNumber})
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1">
              {materialEntradaSelecionado?.tipoControle === "METRAGEM"
                ? "Metragem"
                : "Quantidade"}
            </label>
            <input
              type="number"
              name="quantidade"
              value={formData.quantidade}
              onChange={handleInputChange}
              min={materialEntradaSelecionado?.tipoControle === "METRAGEM" ? "0.001" : "1"}
              step={materialEntradaSelecionado?.tipoControle === "METRAGEM" ? "0.001" : "1"}
              required
              className="w-full px-4 py-2 border border-slate-300 rounded-lg"
            />
          </div>
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1">
              Custo unitário desta entrada (R$)
            </label>
            <input
              type="number"
              name="custoUnitarioEntrada"
              value={formData.custoUnitarioEntrada}
              onChange={handleInputChange}
              min="0"
              step="0.0001"
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
              placeholder={
                materialEntradaSelecionado
                  ? `Atual: ${formatarMoeda(materialEntradaSelecionado.custoMedio)}`
                  : "Opcional"
              }
            />
            <p className="mt-1 text-xs text-slate-500">
              Em branco mantém o custo atual. Ao informar, o sistema recalcula a média ponderada.
            </p>
          </div>
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1">
              Funcionário Responsável
            </label>
            <select
              name="funcionarioId"
              value={formData.funcionarioId}
              onChange={handleInputChange}
              required
              className="w-full px-4 py-2 border border-slate-300 rounded-lg"
            >
              <option value="">-- Selecione um funcionário --</option>
              {funcionarios.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.nome}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1">Depósito de entrada</label>
            <select
              name="localEstoqueId"
              value={formData.localEstoqueId}
              onChange={handleInputChange}
              required
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
            >
              <option value="">Selecione o depósito</option>
              {locaisEstoque.map((local) => (
                <option key={local.id} value={local.id}>{local.nome}</option>
              ))}
            </select>
          </div>
          <div className="flex gap-3 justify-end pt-4">
            <button
              type="button"
              onClick={handleCloseModal}
              className="px-6 py-2 border rounded-lg"
            >
              Cancelar
            </button>
            <button
              type="submit"
              className="px-6 py-2 bg-green-600 text-white font-semibold rounded-lg"
            >
              Registrar Entrada
            </button>
          </div>
        </form>
      </Modal>

      <Modal
        isOpen={showUnidadeRastreavelModal}
        onClose={handleCloseModal}
        title="Cadastrar Bobina ou Rolo"
      >
        <form onSubmit={cadastrarUnidadeRastreavel} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-semibold text-slate-700">Material</label>
            <select
              required
              value={unidadeForm.materialId}
              onChange={(event) =>
                setUnidadeForm((prev) => ({ ...prev, materialId: event.target.value }))
              }
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
            >
              <option value="">Selecione o material</option>
              {materiaisRastreaveis.map((material) => (
                <option key={material.id} value={material.id}>
                  {material.nome} ({material.tipoControle})
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm font-semibold text-slate-700">Depósito</label>
            <select
              required
              value={unidadeForm.localEstoqueId}
              onChange={(event) =>
                setUnidadeForm((prev) => ({ ...prev, localEstoqueId: event.target.value }))
              }
              className="w-full rounded-lg border border-slate-300 px-4 py-2"
            >
              <option value="">Selecione o depósito</option>
              {locaisEstoque.map((local) => (
                <option key={local.id} value={local.id}>{local.nome}</option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-sm font-semibold text-slate-700">Código único</label>
              <input
                required
                value={unidadeForm.codigo}
                onChange={(event) =>
                  setUnidadeForm((prev) => ({ ...prev, codigo: event.target.value }))
                }
                className="w-full rounded-lg border border-slate-300 px-4 py-2"
                placeholder="Ex: BOB-CAT6-001"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-semibold text-slate-700">Metragem inicial</label>
              <input
                type="number"
                required
                min="0.001"
                step="0.001"
                value={unidadeForm.metragemInicial}
                onChange={(event) =>
                  setUnidadeForm((prev) => ({ ...prev, metragemInicial: event.target.value }))
                }
                className="w-full rounded-lg border border-slate-300 px-4 py-2"
                placeholder="305"
              />
            </div>
          </div>
          <div>
            <label className="mb-1 block text-sm font-semibold text-slate-700">Observação</label>
            <textarea
              rows="3"
              value={unidadeForm.observacao}
              onChange={(event) =>
                setUnidadeForm((prev) => ({ ...prev, observacao: event.target.value }))
              }
              className="w-full resize-none rounded-lg border border-slate-300 px-4 py-2"
            />
          </div>
          <div className="flex justify-end gap-3 pt-3">
            <button type="button" onClick={handleCloseModal} className="rounded-lg border px-5 py-2">
              Cancelar
            </button>
            <button type="submit" className="rounded-lg bg-cyan-700 px-5 py-2 font-bold text-white">
              Cadastrar
            </button>
          </div>
        </form>
      </Modal>

      {/* Modal OR */}
      <Modal
        isOpen={showSaidaModal}
        onClose={handleCloseModal}
        title={`${acaoOr === "retirada" ? "Executar Retirada" : "Registrar Devolução"} - ${ordemRetiradaAtual?.numeroOr || ""}`}
      >
        <form onSubmit={handleSubmitOr} className="space-y-4">
          {ordemRetiradaAtual && (
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs text-slate-600">
              <p className="font-black text-slate-800">{ordemRetiradaAtual.numeroOr}</p>
              <p>
                {ordemRetiradaAtual.ordemServico?.numeroOs} -{" "}
                {ordemRetiradaAtual.comarca?.nomeComarca}
              </p>
              <div className="mt-2 flex flex-wrap gap-1">
                {(ordemRetiradaAtual.itens || []).map((item) => (
                  <span key={item.id} className="rounded bg-white px-2 py-1">
                    {item.nomeMaterial}: {item.quantidadeSolicitada} {unidadeMaterial(item.material)}
                  </span>
                ))}
              </div>
            </div>
          )}

          {acaoOr === "retirada" ? (
            <>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div>
                  <label className="block text-sm font-semibold text-slate-700 mb-1">
                    Quem conferiu os itens *
                  </label>
                  <input
                    value={orForm.conferidoPor}
                    onChange={(e) =>
                      setOrForm((prev) => ({ ...prev, conferidoPor: e.target.value }))
                    }
                    required
                    className="w-full px-4 py-2 border border-slate-300 rounded-lg"
                  />
                </div>
                <div>
                  <label className="block text-sm font-semibold text-slate-700 mb-1">
                    Quem levou os itens *
                  </label>
                  <input
                    value={orForm.levadoPor}
                    onChange={(e) =>
                      setOrForm((prev) => ({ ...prev, levadoPor: e.target.value }))
                    }
                    required
                    className="w-full px-4 py-2 border border-slate-300 rounded-lg"
                  />
                </div>
              </div>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <SignatureBox
                  label="Assinatura desenhada do conferente *"
                  value={orForm.assinaturaConferente}
                  onChange={(assinaturaConferente) =>
                    setOrForm((prev) => ({ ...prev, assinaturaConferente }))
                  }
                />
                <SignatureBox
                  label="Assinatura desenhada de quem levou *"
                  value={orForm.assinaturaRetirante}
                  onChange={(assinaturaRetirante) =>
                    setOrForm((prev) => ({ ...prev, assinaturaRetirante }))
                  }
                />
              </div>
              {(ordemRetiradaAtual?.itens || [])
                .filter((item) => rastreavel(item.material))
                .map((item) => (
                  <div key={item.id} className="rounded-lg border border-cyan-200 bg-cyan-50/50 p-4">
                    <div className="mb-3 flex items-center justify-between gap-3">
                      <div>
                        <p className="text-sm font-bold text-slate-800">{item.nomeMaterial}</p>
                        <p className="text-xs text-slate-500">Total solicitado: {item.quantidadeSolicitada} m</p>
                      </div>
                      <button
                        type="button"
                        onClick={() => adicionarAlocacao(item.id)}
                        className="rounded border border-cyan-200 bg-white px-2 py-1 text-xs font-bold text-cyan-700"
                      >
                        + Bobina/Rolo
                      </button>
                    </div>
                    <div className="space-y-2">
                      {(orForm.alocacoes[item.id] || []).map((alocacao, index) => (
                        <div key={index} className="space-y-2 rounded border border-cyan-200 bg-white p-3">
                          <div className="grid grid-cols-[1fr_110px_32px] gap-2">
                            <select
                              required
                              value={alocacao.unidadeRastreavelId}
                              onChange={(event) =>
                                atualizarAlocacao(
                                  item.id,
                                  index,
                                  "unidadeRastreavelId",
                                  event.target.value,
                                )
                              }
                              className="rounded border border-slate-300 bg-white px-3 py-2 text-sm"
                            >
                              <option value="">Selecione</option>
                              {unidadesDisponiveis(item.material?.id).map((unidade) => (
                                <option key={unidade.id} value={unidade.id}>
                                  {unidade.codigo} ({formatarNumero(unidade.metragemAtual)} m)
                                </option>
                              ))}
                            </select>
                            <input
                              type="number"
                              required
                              min="0.001"
                              step="0.001"
                              value={alocacao.metragem}
                              onChange={(event) =>
                                atualizarAlocacao(item.id, index, "metragem", event.target.value)
                              }
                              className="rounded border border-slate-300 px-2 py-2 text-sm"
                              aria-label="Metragem retirada"
                            />
                            <button
                              type="button"
                              onClick={() => removerAlocacao(item.id, index)}
                              disabled={(orForm.alocacoes[item.id] || []).length === 1}
                              className="rounded text-slate-400 hover:bg-slate-50 hover:text-rose-600 disabled:opacity-30"
                              title="Remover alocação"
                            >
                              <Minus size={15} />
                            </button>
                          </div>
                          <label className="flex cursor-pointer items-center gap-3 rounded border border-dashed border-cyan-300 bg-cyan-50 px-3 py-2 text-xs font-semibold text-cyan-800">
                            <Camera size={18} />
                            <span className="flex-1">
                              {alocacao.evidenciaFotoNome || "Foto da metragem restante *"}
                            </span>
                            <input
                              type="file"
                              accept="image/jpeg,image/png"
                              capture="environment"
                              required={!alocacao.evidenciaFotoBase64}
                              onChange={(event) =>
                                selecionarEvidenciaRetirada(
                                  item.id,
                                  index,
                                  event.target.files?.[0],
                                )
                              }
                              className="sr-only"
                            />
                            {alocacao.evidenciaFotoBase64 && (
                              <img
                                src={alocacao.evidenciaFotoBase64}
                                alt="Preview da metragem na retirada"
                                className="h-12 w-16 rounded border border-cyan-200 object-cover"
                              />
                            )}
                          </label>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
            </>
          ) : (
            <>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div>
                  <label className="block text-sm font-semibold text-slate-700 mb-1">
                    Quem devolveu *
                  </label>
                  <input
                    value={orForm.devolvidoPor}
                    onChange={(e) =>
                      setOrForm((prev) => ({ ...prev, devolvidoPor: e.target.value }))
                    }
                    required
                    className="w-full px-4 py-2 border border-slate-300 rounded-lg"
                  />
                </div>
                <div>
                  <label className="block text-sm font-semibold text-slate-700 mb-1">
                    Quem conferiu/recebeu *
                  </label>
                  <input
                    value={orForm.recebidoPor}
                    onChange={(e) =>
                      setOrForm((prev) => ({ ...prev, recebidoPor: e.target.value }))
                    }
                    required
                    className="w-full px-4 py-2 border border-slate-300 rounded-lg"
                  />
                </div>
              </div>
              <div className="rounded-lg border border-slate-200">
                <div className="grid grid-cols-[1fr_120px] gap-3 bg-slate-50 px-3 py-2 text-xs font-black uppercase text-slate-500">
                  <span>Item devolvido</span>
                  <span>Qtd.</span>
                </div>
                {(ordemRetiradaAtual?.itens || []).flatMap((item) =>
                  rastreavel(item.material)
                    ? (item.alocacoes || []).map((alocacao) => (
                        <div
                          key={`alocacao-${alocacao.id}`}
                          className="space-y-2 border-t border-slate-100 px-3 py-3 text-sm"
                        >
                          <div className="grid grid-cols-[1fr_120px] items-center gap-3">
                            <span>
                              {item.nomeMaterial} · {alocacao.unidadeRastreavel?.codigo}
                              <small className="ml-2 text-slate-400">
                                retirado {alocacao.metragemRetirada} m
                              </small>
                            </span>
                            <input
                              type="number"
                              min="0"
                              max={Number(alocacao.metragemRetirada || 0) - Number(alocacao.metragemDevolvida || 0)}
                              step="0.001"
                              value={orForm.devolucoesAlocacao[alocacao.id] ?? 0}
                              onChange={(event) =>
                                setOrForm((prev) => ({
                                  ...prev,
                                  devolucoesAlocacao: {
                                    ...prev.devolucoesAlocacao,
                                    [alocacao.id]: event.target.value,
                                  },
                                }))
                              }
                              className="w-full rounded border border-slate-300 px-2 py-1"
                              aria-label="Metragem devolvida"
                            />
                          </div>
                          <label className="flex cursor-pointer items-center gap-3 rounded border border-dashed border-emerald-300 bg-emerald-50 px-3 py-2 text-xs font-semibold text-emerald-800">
                            <Camera size={18} />
                            <span className="flex-1">
                              {orForm.evidenciasDevolucao[alocacao.id]?.nome
                                || "Foto da metragem restante no retorno *"}
                            </span>
                            <input
                              type="file"
                              accept="image/jpeg,image/png"
                              capture="environment"
                              required={!orForm.evidenciasDevolucao[alocacao.id]?.base64}
                              onChange={(event) =>
                                selecionarEvidenciaDevolucao(
                                  alocacao.id,
                                  event.target.files?.[0],
                                )
                              }
                              className="sr-only"
                            />
                            {orForm.evidenciasDevolucao[alocacao.id]?.base64 && (
                              <img
                                src={orForm.evidenciasDevolucao[alocacao.id].base64}
                                alt="Preview da metragem no retorno"
                                className="h-12 w-16 rounded border border-emerald-200 object-cover"
                              />
                            )}
                          </label>
                        </div>
                      ))
                    : [
                        <div
                          key={`item-${item.id}`}
                          className="grid grid-cols-[1fr_120px] items-center gap-3 border-t border-slate-100 px-3 py-2 text-sm"
                        >
                          <span>
                            {item.nomeMaterial}
                            {item.categoria === "FERRAMENTA" && (
                              <strong className="ml-2 rounded bg-amber-100 px-2 py-0.5 text-[10px] uppercase text-amber-800">
                                retorno obrigatório
                              </strong>
                            )}
                          </span>
                          <input
                            type="number"
                            min="0"
                            max={item.quantidadeRetirada || item.quantidadeSolicitada || 0}
                            step={controlaMetragem(item.material) ? "0.001" : "1"}
                            value={orForm.devolucoes[item.id] ?? 0}
                            onChange={(event) =>
                              setOrForm((prev) => ({
                                ...prev,
                                devolucoes: {
                                  ...prev.devolucoes,
                                  [item.id]: event.target.value,
                                },
                              }))
                            }
                            className="w-full rounded border border-slate-300 px-2 py-1"
                          />
                        </div>,
                      ],
                )}
              </div>
              <SignatureBox
                label="Assinatura desenhada de recebimento *"
                value={orForm.assinaturaRecebimento}
                onChange={(assinaturaRecebimento) =>
                  setOrForm((prev) => ({ ...prev, assinaturaRecebimento }))
                }
              />
            </>
          )}

          <div className="flex gap-3 justify-end pt-4">
            <button
              type="button"
              onClick={handleCloseModal}
              className="px-6 py-2 border rounded-lg"
            >
              Cancelar
            </button>
            <button
              type="submit"
              className="px-6 py-2 bg-blue-600 text-white font-semibold rounded-lg"
            >
              {acaoOr === "retirada" ? "Confirmar Retirada" : "Confirmar Devolução"}
            </button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
