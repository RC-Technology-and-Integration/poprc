import React, { useState, useEffect } from "react";
import {
  Package,
  FileCheck,
  CheckCircle2,
  AlertTriangle,
  Loader2,
  RefreshCw,
  Briefcase,
  Edit2,
  X,
  Download,
} from "lucide-react";
import api, { getApiErrorMessage } from "../services/api";
import FilaPendenciasOperacionais from "../components/FilaPendenciasOperacionais";

export default function AuditoriaMateriaisEAsBuilt() {
  const [comarcas, setComarcas] = useState([]);
  const [selectedComarcaId, setSelectedComarcaId] = useState("");
  const [dados, setDados] = useState(null);
  const [rastreabilidade, setRastreabilidade] = useState(null);
  const [ordensRetirada, setOrdensRetirada] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [justificativaHomologacao, setJustificativaHomologacao] = useState("");

  const [editModalOpen, setEditModalOpen] = useState(false);
  const [selectedMaterial, setSelectedMaterial] = useState(null);
  const [novaQuantidade, setNovaQuantidade] = useState("");

  const carregarComarcas = async () => {
    try {
      const res = await api.get("/comarcas");
      setComarcas(res.data || []);
      if (res.data && res.data.length > 0) {
        setSelectedComarcaId(res.data[0].id);
      }
    } catch (err) {
      console.error("Erro ao listar comarcas", err);
      setError("Não foi possível buscar a lista de comarcas ativas.");
    } finally {
      setLoading(false);
    }
  };

  const carregarAuditoria = async (id) => {
    if (!id) return;
    try {
      setLoading(true);
      const [auditoriaResponse, rastreabilidadeResponse, ordensRetiradaResponse] = await Promise.all([
        api.get(`/comarcas/${id}/auditoria`),
        api.get(`/comarcas/${id}/rastreabilidade-estoque`),
        api.get(`/ordens-retirada/comarca/${id}`),
      ]);
      setDados(auditoriaResponse.data);
      setRastreabilidade(rastreabilidadeResponse.data);
      setOrdensRetirada(ordensRetiradaResponse.data || []);
      setError(null);
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao carregar os dados de engenharia do servidor."));
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    carregarComarcas();
  }, []);

  useEffect(() => {
    if (selectedComarcaId) {
      setJustificativaHomologacao("");
      carregarAuditoria(selectedComarcaId);
    }
  }, [selectedComarcaId]);

  const homologarAsBuilt = async () => {
    try {
      const response = await api.patch(
        `/comarcas/${selectedComarcaId}/as-built/homologar`,
        { justificativa: justificativaHomologacao },
      );
      setDados(response.data);
      setJustificativaHomologacao("");
      const rastreabilidadeResponse = await api.get(
        `/comarcas/${selectedComarcaId}/rastreabilidade-estoque`,
      );
      setRastreabilidade(rastreabilidadeResponse.data);
      setError(null);
      setSuccess(
        response.data?.asBuiltStatus === "HOMOLOGADO_COM_DIVERGENCIA"
          ? "As-Built homologado com divergência registrada para esta OS."
          : "Documentação As-Built homologada para esta OS.",
      );
    } catch (err) {
      setError(
        getApiErrorMessage(err, "Erro ao homologar As-Built. Verifique as divergências da auditoria."),
      );
    }
  };

  const reabrirAsBuilt = async () => {
    const confirmar = window.confirm(
      "Reabrir este As-Built vai estornar a baixa no estoque e recriar a reserva dos materiais previstos. Deseja continuar?",
    );
    if (!confirmar) return;

    try {
      const response = await api.patch(
        `/comarcas/${selectedComarcaId}/as-built/reabrir`,
      );
      setDados(response.data);
      const rastreabilidadeResponse = await api.get(
        `/comarcas/${selectedComarcaId}/rastreabilidade-estoque`,
      );
      setRastreabilidade(rastreabilidadeResponse.data);
      setError(null);
      setSuccess("As-Built reaberto para ajuste e baixa estornada no estoque.");
    } catch (err) {
      setError(
        getApiErrorMessage(err, "Erro ao reabrir As-Built. Verifique o status atual da OS."),
      );
    }
  };

  const abrirModalEdicao = (material) => {
    setSelectedMaterial(material);
    setNovaQuantidade(material.utilizado);
    setEditModalOpen(true);
  };

  const salvarAjusteEstoque = async (e) => {
    e.preventDefault();
    try {
      await api.put(
        `/comarcas/materiais/${selectedMaterial.id}/auditoria`,
        null,
        { params: { quantidadeAuditada: parseFloat(novaQuantidade) || 0 } },
      );
      setSuccess("Divergência de material atualizada com sucesso!");
      setEditModalOpen(false);
      carregarAuditoria(selectedComarcaId);
    } catch (err) {
      setError(getApiErrorMessage(err, "Erro ao atualizar dados de inventário."));
    }
  };

  const materiais = dados?.materiais || [];
  const asBuiltStatus = dados?.asBuiltStatus || "PENDENTE";
  const conciliado = Boolean(dados?.conciliado);
  const movimentacaoFisicaPorMaterial = ordensRetirada.reduce((acumulado, ordem) => {
    (ordem.itens || []).forEach((item) => {
      const materialId = item.material?.id || item.materialItem?.material?.id;
      if (!materialId) return;
      const chave = String(materialId);
      const atual = acumulado[chave] || { retirado: 0, devolvido: 0, consumoLiquido: 0 };
      atual.retirado += Number(item.quantidadeRetirada || 0);
      atual.devolvido += Number(item.quantidadeDevolvida || 0);
      atual.consumoLiquido = atual.retirado - atual.devolvido;
      acumulado[chave] = atual;
    });
    return acumulado;
  }, {});
  const obterMovimentacaoFisica = (material) =>
    movimentacaoFisicaPorMaterial[String(material?.materialId)] || {
      retirado: 0,
      devolvido: 0,
      consumoLiquido: 0,
    };
  const materialPossuiPendenciaFisica = (material) => {
    const fisico = obterMovimentacaoFisica(material);
    if (!material.estoqueBaixado) return true;
    if (material.categoria === "FERRAMENTA") {
      return fisico.retirado <= 0 || Math.abs(fisico.consumoLiquido) > 0.0001;
    }
    return Math.abs(fisico.consumoLiquido - Number(material.utilizado || 0)) > 0.0001;
  };
  const materiaisComPendenciaFisica = materiais.filter(materialPossuiPendenciaFisica);
  const podeHomologar =
    materiais.length > 0 &&
    materiaisComPendenciaFisica.length === 0 &&
    !["HOMOLOGADO", "HOMOLOGADO_COM_DIVERGENCIA"].includes(asBuiltStatus);
  const statusAsBuiltClass =
    asBuiltStatus === "HOMOLOGADO"
      ? "text-emerald-400"
      : asBuiltStatus === "HOMOLOGADO_COM_DIVERGENCIA"
        ? "text-blue-400"
      : asBuiltStatus === "DIVERGENTE"
        ? "text-rose-400"
        : "text-amber-400";
  const comarcaSelecionada = comarcas.find(
    (comarca) => String(comarca.id) === String(selectedComarcaId),
  );
  const numeroOsSelecionada =
    dados?.numeroOs || comarcaSelecionada?.ordemServico?.numeroOs || "OS não vinculada";
  const quantidadeAuditadaModal = parseFloat(novaQuantidade) || 0;
  const incrementoAuditoria = ["FRACIONADO", "METRAGEM", "BOBINA", "ROLO"].includes(
    selectedMaterial?.tipoControle,
  )
    ? 0.001
    : 1;
  const saldoModal = selectedMaterial
    ? selectedMaterial.previsto - quantidadeAuditadaModal
    : 0;
  const movimentacaoFisicaModal = obterMovimentacaoFisica(selectedMaterial);
  const rastreabilidadeItens = rastreabilidade?.itens || [];
  const rastreabilidadeTotais = rastreabilidade?.totais || {};
  const asBuiltHomologado = ["HOMOLOGADO", "HOMOLOGADO_COM_DIVERGENCIA"].includes(
    asBuiltStatus,
  );
  const statusLabels = {
    PENDENTE: "Pendente",
    DIVERGENTE: "Divergente",
    HOMOLOGADO: "Homologado",
    HOMOLOGADO_COM_DIVERGENCIA: "Homologado com divergência",
    REABERTO_PARA_AJUSTE: "Reaberto para ajuste",
  };
  const statusLabel = statusLabels[asBuiltStatus] || asBuiltStatus;
  const formatarDataHora = (value) => {
    if (!value) return "-";
    return new Date(value).toLocaleString("pt-BR", {
      day: "2-digit",
      month: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  };
  const exportarRastreabilidadeXlsx = async () => {
    const ExcelJS = (await import("exceljs")).default;
    const workbook = new ExcelJS.Workbook();
    workbook.creator = "RC Operations Hub";
    const worksheet = workbook.addWorksheet("Rastreabilidade", {
      views: [{ state: "frozen", ySplit: 1 }],
    });
    const linhas = [
      [
        "OS",
        "Comarca",
        "Material",
        "Part Number",
        "Previsto",
        "Reservado",
        "Auditado",
        "Baixado",
        "Item adicional",
        "Material faltante",
        "Solicitação",
        "Retirada",
        "Uso",
        "Saldo Previsto x Baixado",
        "Saldo Auditado x Baixado",
      ],
      ...rastreabilidadeItens.map((item) => [
        numeroOsSelecionada,
        rastreabilidade?.nomeComarca || comarcaSelecionada?.nomeComarca || "",
        item.nome,
        item.partNumber || "",
        item.previsto,
        item.reservado,
        item.auditado,
        item.baixado,
        item.itemAdicional ? "Sim" : "Não",
        item.materialFaltante ? item.descricaoFaltante || "Sim" : "Não",
        item.dataHoraSolicitacao || "",
        item.dataHoraRetirada || "",
        item.dataHoraUso || "",
        item.saldoPrevistoBaixado,
        item.saldoAuditadoBaixado,
      ]),
    ];
    linhas.forEach((linha) => worksheet.addRow(linha));
    worksheet.autoFilter = { from: "A1", to: "O1" };
    worksheet.getRow(1).height = 30;
    worksheet.getRow(1).eachCell((cell) => {
      cell.font = { bold: true, color: { argb: "FFFFFFFF" } };
      cell.fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FF1E293B" } };
      cell.alignment = { vertical: "middle", horizontal: "center", wrapText: true };
    });
    worksheet.columns.forEach((column) => {
      let maior = 10;
      column.eachCell({ includeEmpty: true }, (cell) => {
        maior = Math.max(maior, String(cell.value ?? "").length + 2);
        cell.alignment = { vertical: "top", wrapText: true };
      });
      column.width = Math.min(45, maior);
    });
    worksheet.eachRow((row, index) => {
      if (index === 1) return;
      let linhasNecessarias = 1;
      row.eachCell({ includeEmpty: true }, (cell, columnNumber) => {
        linhasNecessarias = Math.max(
          linhasNecessarias,
          Math.ceil(String(cell.value ?? "").length / (worksheet.getColumn(columnNumber).width || 12)),
        );
      });
      row.height = Math.min(90, Math.max(20, linhasNecessarias * 15));
    });
    const buffer = await workbook.xlsx.writeBuffer();
    const url = URL.createObjectURL(new Blob([buffer], {
      type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `rastreabilidade-${numeroOsSelecionada}.xlsx`;
    link.click();
    URL.revokeObjectURL(url);
  };

  if (loading && comarcas.length === 0)
    return (
      <div className="min-h-screen bg-slate-950 flex justify-center items-center">
        <Loader2 className="w-10 h-10 animate-spin text-indigo-500" />
      </div>
    );

  return (
    <div className="min-h-full bg-slate-950 p-4 font-sans text-slate-100 md:p-6">
      <div className="mx-auto max-w-[1500px] space-y-6">
        <header className="flex flex-col items-start justify-between gap-4 border-b border-slate-800 pb-5 lg:flex-row lg:items-end">
          <div>
            <p className="text-xs font-bold uppercase text-cyan-400">Materiais e auditoria</p>
            <h1 className="mt-1 text-2xl font-black text-white sm:text-3xl">
              Auditoria de Retirada/Devolução
            </h1>
            <p className="mt-1 text-sm text-slate-400">
              Conciliação por OR, OS, estoque e documentação As-Built
            </p>
          </div>

          <div className="flex w-full min-w-0 items-end gap-2 lg:w-auto">
            <label className="min-w-0 flex-1 lg:w-[430px]">
              <span className="mb-1.5 flex items-center gap-1.5 text-[10px] font-black uppercase text-slate-400"><Briefcase size={13} /> OS / obra em análise</span>
              <select
                value={selectedComarcaId}
                onChange={(e) => setSelectedComarcaId(e.target.value)}
                className="h-10 w-full min-w-0 cursor-pointer rounded border border-slate-700 bg-slate-900 px-3 text-sm font-bold text-white outline-none focus:border-blue-500"
              >
                {comarcas.length === 0 && <option value="">Nenhuma obra disponível</option>}
                {comarcas.map((comarca) => (
                  <option
                    key={comarca.id}
                    value={comarca.id}
                    className="bg-slate-900 text-white"
                  >
                    {comarca.ordemServico?.numeroOs || "OS não vinculada"} -{" "}
                    {comarca.nomeComarca || "Comarca operacional"}
                  </option>
                ))}
              </select>
            </label>
            <button
              type="button"
              onClick={() => carregarAuditoria(selectedComarcaId)}
              disabled={!selectedComarcaId || loading}
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded border border-slate-700 bg-slate-900 hover:bg-slate-800 disabled:opacity-40"
              title="Atualizar auditoria"
            >
              <RefreshCw className={`h-4 w-4 text-slate-300 ${loading ? "animate-spin" : ""}`} />
            </button>
          </div>
        </header>

        <FilaPendenciasOperacionais area="AUDITORIA" titulo="Auditorias aguardando homologação" limite={4} dark recolhivel inicialmenteAberta={false} />

        {error && (
          <div className="bg-rose-950/40 border border-rose-500/30 text-rose-300 p-4 rounded-xl text-sm">
            {error}
          </div>
        )}
        {success && (
          <div className="bg-emerald-950/40 border border-emerald-500/30 text-emerald-300 p-4 rounded-xl text-sm">
            {success}
          </div>
        )}

        {materiaisComPendenciaFisica.length > 0 && !asBuiltHomologado && (
          <div className="bg-amber-950/40 border border-amber-500/30 text-amber-200 p-4 rounded-xl text-sm">
            <p className="font-black">Conciliação física pendente</p>
            <p className="mt-1 text-amber-100/80">
              O auditado deve corresponder ao consumo líquido da OR (retirado menos devolvido).
              Ferramentas precisam ter retirada registrada e devolução integral.
            </p>
          </div>
        )}

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          {/* Tabela de Discrepância de Inventário com Conciliação Dinâmica */}
          <div className="overflow-hidden rounded-lg border border-slate-800 bg-slate-900 shadow-xl lg:col-span-2">
            <div className="p-6 border-b border-slate-800 flex items-center gap-3">
              <Package className="w-5 h-5 text-indigo-400" />
              <h2 className="font-bold text-white">
                Discrepância de Inventário
              </h2>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm whitespace-nowrap">
                <thead className="bg-slate-950/50 text-slate-400 border-b border-slate-800 uppercase text-xs font-bold tracking-wider">
                  <tr>
                    <th className="px-6 py-4">Item</th>
                    <th className="px-6 py-4 text-center">Previsto (A)</th>
                    <th className="px-6 py-4 text-center">Auditado (B)</th>
                    <th className="px-6 py-4 text-center">Conciliação</th>
                    <th className="px-6 py-4">O que está faltando</th>
                    <th className="px-6 py-4 text-center">Ajustar</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800">
                  {materiais.length === 0 ? (
                    <tr>
                      <td
                        colSpan="6"
                        className="px-6 py-8 text-center text-slate-500"
                      >
                        Nenhum material previsto para esta OS/comarca no banco.
                      </td>
                    </tr>
                  ) : (
                    materiais.map((mat, i) => {
                      const saldoDivergencia = mat.previsto - mat.utilizado;
                      const fisico = obterMovimentacaoFisica(mat);
                      const pendenciaFisica = materialPossuiPendenciaFisica(mat);

                      return (
                        <tr
                          key={mat.id || i}
                          className="hover:bg-slate-800/50 transition-colors"
                        >
                          <td className="px-6 py-4 font-medium text-slate-300">
                            <div>
                              <div className="flex items-center gap-2">
                                <span>{mat.nome}</span>
                              {mat.itemAdicional && (
                                <span className="rounded bg-emerald-500/10 px-2 py-0.5 text-[10px] font-bold uppercase text-emerald-400 border border-emerald-500/20">
                                  Item adicional
                                </span>
                              )}
                              {mat.estoqueBaixado && (
                                <span className="rounded bg-emerald-500/10 px-2 py-0.5 text-[10px] font-bold uppercase text-emerald-400 border border-emerald-500/20">
                                  Baixado
                                </span>
                              )}
                              {mat.estoqueReservado && !mat.estoqueBaixado && (
                                <span className="rounded bg-blue-500/10 px-2 py-0.5 text-[10px] font-bold uppercase text-blue-400 border border-blue-500/20">
                                  Reservado
                                </span>
                              )}
                              </div>
                              <span className="mt-1 block text-[10px] font-medium text-slate-500">
                                OR: retirado {fisico.retirado} · devolvido {fisico.devolvido} · consumo {fisico.consumoLiquido}
                              </span>
                            </div>
                          </td>
                          <td className="px-6 py-4 text-center text-slate-400 font-mono">
                            {mat.previsto}
                          </td>
                          <td className="px-6 py-4 text-center font-mono font-bold text-slate-200">
                            {mat.utilizado}
                          </td>

                          {/* Destacador visual automatizado do Saldo contábil */}
                          <td className="px-6 py-4 text-center">
                            {pendenciaFisica ? (
                              <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded text-xs font-bold bg-rose-500/10 text-rose-400 border border-rose-500/20">
                                <AlertTriangle className="w-3 h-3" /> OR {fisico.consumoLiquido} / auditado {mat.utilizado}
                              </span>
                            ) : saldoDivergencia === 0 ? (
                              <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded text-xs font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                                <CheckCircle2 className="w-3 h-3" /> Conciliado
                              </span>
                            ) : saldoDivergencia > 0 ? (
                              <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded text-xs font-bold bg-blue-500/10 text-blue-400 border border-blue-500/20">
                                Sobra: {saldoDivergencia} un
                              </span>
                            ) : (
                              <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded text-xs font-bold bg-rose-500/10 text-rose-400 border border-rose-500/20">
                                <AlertTriangle className="w-3 h-3" /> Falta:{" "}
                                {Math.abs(saldoDivergencia)} un
                              </span>
                            )}
                          </td>
                          <td className="px-6 py-4 text-xs text-slate-400 max-w-xs">
                            {mat.materialFaltante ? (
                              <div className="space-y-1">
                                <span className="inline-flex rounded bg-amber-500/10 px-2 py-0.5 font-bold uppercase text-amber-300 border border-amber-500/20">
                                  Faltante
                                </span>
                                <p className="whitespace-normal text-slate-500">
                                  {mat.descricaoFaltante || "Material pendente"}
                                </p>
                              </div>
                            ) : (
                              <span className="text-slate-600">Sem falta</span>
                            )}
                          </td>
                          <td className="px-6 py-4 text-center">
                            <button
                              onClick={() => abrirModalEdicao(mat)}
                              disabled={asBuiltHomologado}
                              className="p-1.5 bg-slate-950 hover:bg-slate-800 disabled:bg-slate-900 disabled:text-slate-700 disabled:cursor-not-allowed border border-slate-800 rounded-lg text-slate-400 hover:text-indigo-400 transition-colors"
                              title={
                                asBuiltHomologado
                                  ? "Reabra o As-Built para ajustar"
                                  : "Ajustar quantidade auditada"
                              }
                            >
                              <Edit2 size={14} />
                            </button>
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>
          </div>

          {/* Card do As-Built */}
          <div className="flex flex-col justify-between space-y-6 rounded-lg border border-slate-800 bg-slate-900 p-6 shadow-xl">
            <div className="space-y-4">
              <div className="flex items-center gap-3 pb-2 border-b border-slate-800">
                <FileCheck className="w-5 h-5 text-indigo-400" />
                <h2 className="font-bold text-white">Status do As-Built</h2>
              </div>
              <p className="text-sm text-slate-400 leading-relaxed">
                A conciliação usa a OS {numeroOsSelecionada} para comparar o
                previsto na comarca com o que foi auditado em campo.
              </p>

              <div className="bg-slate-950 p-4 rounded-xl border border-slate-800 text-center">
                <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider block mb-1">
                  Situação do Fechamento
                </span>
                <p className={`text-xl font-black tracking-wide ${statusAsBuiltClass}`}>
                  {statusLabel}
                </p>
              </div>
              {!asBuiltHomologado && !conciliado && materiais.length > 0 && (
                <label className="block text-sm text-slate-300">
                  Justificativa da divergência <span className="text-rose-400">*</span>
                  <textarea
                    value={justificativaHomologacao}
                    onChange={(event) => setJustificativaHomologacao(event.target.value)}
                    rows={4}
                    className="mt-2 w-full rounded-lg border border-slate-700 bg-slate-950 p-3 text-sm text-white"
                    placeholder="Explique a divergência registrada na auditoria"
                    required
                  />
                </label>
              )}
              {dados?.homologacaoLegadaSemJustificativa && (
                <p className="text-xs text-amber-300">
                  Homologação anterior à exigência de justificativa; não há texto registrado.
                </p>
              )}
              {dados?.homologacoes?.length > 0 && (
                <div className="space-y-2 text-sm text-slate-300">
                  <h3 className="font-semibold">Histórico de homologações</h3>
                  {dados.homologacoes.map((homologacao) => (
                    <div key={homologacao.id} className="rounded-lg border border-slate-800 bg-slate-950 p-3">
                      <p className="text-xs text-slate-400">
                        {statusLabels[homologacao.status] || homologacao.status} · {formatarDataHora(homologacao.registradoEm)}
                        {homologacao.responsavel && ` · ${homologacao.responsavel}`}
                      </p>
                      {homologacao.justificativa && (
                        <p className="mt-2 whitespace-pre-wrap break-words">{homologacao.justificativa}</p>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>

            {!asBuiltHomologado ? (
              <button
                onClick={homologarAsBuilt}
                disabled={!podeHomologar || (!conciliado && !justificativaHomologacao.trim())}
                className="w-full bg-indigo-600 hover:bg-indigo-500 disabled:bg-slate-800 disabled:text-slate-500 disabled:cursor-not-allowed text-white font-bold py-3 px-4 rounded-xl text-sm transition shadow-lg shadow-indigo-600/10"
              >
                {materiais.length === 0
                  ? "Sem Materiais Previstos"
                  : materiaisComPendenciaFisica.length > 0
                    ? "Conciliação física pendente"
                  : conciliado
                    ? "Homologar As-Built"
                    : "Homologar com Divergência"}
              </button>
            ) : (
              <div className="space-y-2">
                <div
                  className={`text-center p-3 rounded-xl text-xs font-bold flex items-center justify-center gap-2 ${
                    asBuiltStatus === "HOMOLOGADO"
                      ? "bg-emerald-500/10 border border-emerald-500/20 text-emerald-400"
                      : "bg-indigo-500/10 border border-indigo-500/20 text-indigo-300"
                  }`}
                >
                  <CheckCircle2 className="w-4 h-4" /> {statusLabel}
                </div>
                <button
                  onClick={reabrirAsBuilt}
                  className="w-full bg-slate-950 hover:bg-slate-800 border border-slate-800 text-slate-300 font-bold py-2.5 px-4 rounded-xl text-xs transition"
                >
                  Reabrir para ajuste
                </button>
              </div>
            )}
          </div>
        </div>

        <div className="overflow-hidden rounded-lg border border-slate-800 bg-slate-900 shadow-xl">
          <div className="p-6 border-b border-slate-800 flex items-center justify-between gap-4">
            <div>
              <h2 className="font-bold text-white">Ciclo da Ordem de Retirada</h2>
              <p className="text-xs text-slate-500">
                Quem retirou, quando retirou, o que levou e o que devolveu.
              </p>
            </div>
            <span className="rounded-full bg-slate-950 px-3 py-1 text-xs font-bold text-slate-400 border border-slate-800">
              {ordensRetirada.length} ORs
            </span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm whitespace-nowrap">
              <thead className="bg-slate-950/50 text-slate-400 border-b border-slate-800 uppercase text-xs font-bold tracking-wider">
                <tr>
                  <th className="px-6 py-4">OR</th>
                  <th className="px-6 py-4">Retirada</th>
                  <th className="px-6 py-4">Devolução</th>
                  <th className="px-6 py-4">Itens</th>
                  <th className="px-6 py-4 text-center">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800">
                {ordensRetirada.length === 0 ? (
                  <tr>
                    <td colSpan="5" className="px-6 py-8 text-center text-slate-500">
                      Nenhuma OR vinculada a esta OS/comarca.
                    </td>
                  </tr>
                ) : (
                  ordensRetirada.map((or) => (
                    <tr key={or.id} className="hover:bg-slate-800/50 transition-colors">
                      <td className="px-6 py-4 font-black text-slate-100">
                        {or.numeroOr}
                        <span className="block text-[10px] font-medium text-slate-500">
                          Gerada: {formatarDataHora(or.dataGeracao)}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-xs text-slate-400">
                        <p>Levou: {or.levadoPor || "-"}</p>
                        <p>Conferiu: {or.conferidoPor || "-"}</p>
                        <p>Quando: {formatarDataHora(or.dataRetirada)}</p>
                      </td>
                      <td className="px-6 py-4 text-xs text-slate-400">
                        <p>Devolveu: {or.devolvidoPor || "-"}</p>
                        <p>Recebeu: {or.recebidoPor || "-"}</p>
                        <p>Quando: {formatarDataHora(or.dataDevolucao)}</p>
                      </td>
                      <td className="px-6 py-4 text-xs text-slate-400 max-w-md">
                        {(or.itens || []).map((item) => (
                          <span
                            key={item.id}
                            className="mr-1 mb-1 inline-flex rounded bg-slate-950 px-2 py-0.5 font-semibold border border-slate-800"
                          >
                            {item.nomeMaterial}: ret. {item.quantidadeRetirada || 0} / dev.{" "}
                            {item.quantidadeDevolvida || 0}
                          </span>
                        ))}
                      </td>
                      <td className="px-6 py-4 text-center">
                        <span className="rounded bg-indigo-500/10 px-2.5 py-0.5 text-xs font-bold uppercase text-indigo-300 border border-indigo-500/20">
                          {or.status}
                        </span>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>

        <div className="overflow-hidden rounded-lg border border-slate-800 bg-slate-900 shadow-xl">
          <div className="p-6 border-b border-slate-800 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
            <div className="flex items-center gap-3">
              <Package className="w-5 h-5 text-indigo-400" />
              <div>
                <h2 className="font-bold text-white">Rastreabilidade por OS</h2>
                <p className="text-xs text-slate-500">
                  Previsto, reservado, auditado e baixado no estoque para{" "}
                  {numeroOsSelecionada}.
                </p>
              </div>
            </div>
            <button
              onClick={exportarRastreabilidadeXlsx}
              disabled={rastreabilidadeItens.length === 0}
              className="inline-flex items-center justify-center gap-2 rounded-lg border border-slate-800 bg-slate-950 px-3 py-2 text-xs font-bold text-slate-300 hover:bg-slate-800 disabled:cursor-not-allowed disabled:text-slate-600 md:order-last"
            >
              <Download size={14} /> Exportar Excel
            </button>
            <div className="grid grid-cols-2 sm:grid-cols-5 gap-2 text-center">
              {[
                ["Previsto", rastreabilidadeTotais.previsto],
                ["Reservado", rastreabilidadeTotais.reservado],
                ["Auditado", rastreabilidadeTotais.auditado],
                ["Baixado", rastreabilidadeTotais.baixado],
                ["Saldo", rastreabilidadeTotais.saldoPrevistoBaixado],
              ].map(([label, value]) => (
                <div
                  key={label}
                  className="rounded-lg border border-slate-800 bg-slate-950 px-3 py-2"
                >
                  <span className="block text-[10px] font-bold uppercase tracking-wider text-slate-500">
                    {label}
                  </span>
                  <strong className="text-sm text-slate-100">{value ?? 0}</strong>
                </div>
              ))}
            </div>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm whitespace-nowrap">
              <thead className="bg-slate-950/50 text-slate-400 border-b border-slate-800 uppercase text-xs font-bold tracking-wider">
                <tr>
                  <th className="px-6 py-4">Material</th>
                  <th className="px-6 py-4 text-center">Previsto</th>
                  <th className="px-6 py-4 text-center">Reservado</th>
                  <th className="px-6 py-4 text-center">Auditado</th>
                  <th className="px-6 py-4 text-center">Baixado</th>
                  <th className="px-6 py-4 text-center">Saldo Prev.-Baixado</th>
                  <th className="px-6 py-4">Timeline</th>
                  <th className="px-6 py-4">Últimos movimentos</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800">
                {rastreabilidadeItens.length === 0 ? (
                  <tr>
                    <td
                      colSpan="8"
                      className="px-6 py-8 text-center text-slate-500"
                    >
                      Nenhum material previsto para rastrear nesta OS.
                    </td>
                  </tr>
                ) : (
                  rastreabilidadeItens.map((item) => {
                    const saldoBaixado = item.saldoPrevistoBaixado || 0;
                    const movimentos = item.movimentacoes || [];

                    return (
                      <tr
                        key={item.materialItemId}
                        className="hover:bg-slate-800/50 transition-colors"
                      >
                        <td className="px-6 py-4 font-medium text-slate-300">
                          {item.nome}
                          {item.partNumber && (
                            <span className="block text-[10px] font-mono text-slate-500">
                              {item.partNumber}
                            </span>
                          )}
                        </td>
                        <td className="px-6 py-4 text-center font-mono text-slate-300">
                          {item.previsto}
                        </td>
                        <td className="px-6 py-4 text-center font-mono text-blue-300">
                          {item.reservado}
                        </td>
                        <td className="px-6 py-4 text-center font-mono text-slate-200">
                          {item.auditado}
                        </td>
                        <td className="px-6 py-4 text-center font-mono text-rose-300">
                          {item.baixado}
                        </td>
                        <td className="px-6 py-4 text-center">
                          <span
                            className={`inline-flex px-2.5 py-0.5 rounded text-xs font-bold border ${
                              saldoBaixado === 0
                                ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/20"
                                : saldoBaixado > 0
                                  ? "bg-blue-500/10 text-blue-400 border-blue-500/20"
                                  : "bg-rose-500/10 text-rose-400 border-rose-500/20"
                            }`}
                          >
                            {saldoBaixado}
                          </span>
                        </td>
                        <td className="px-6 py-4 text-[10px] text-slate-500">
                          <div>Solic.: {formatarDataHora(item.dataHoraSolicitacao)}</div>
                          <div>Ret.: {formatarDataHora(item.dataHoraRetirada)}</div>
                          <div>Uso: {formatarDataHora(item.dataHoraUso)}</div>
                          <div className="mt-1 flex flex-wrap gap-1">
                            {item.itemAdicional && (
                              <span className="rounded bg-emerald-500/10 px-2 py-0.5 font-bold uppercase text-emerald-400 border border-emerald-500/20">
                                Adicional
                              </span>
                            )}
                            {item.materialFaltante && (
                              <span className="rounded bg-amber-500/10 px-2 py-0.5 font-bold uppercase text-amber-300 border border-amber-500/20">
                                Faltante
                              </span>
                            )}
                          </div>
                        </td>
                        <td className="px-6 py-4 text-xs text-slate-500 max-w-xs">
                          {movimentos.length === 0 ? (
                            "Sem movimentação vinculada"
                          ) : (
                            movimentos.slice(0, 3).map((movimento) => (
                              <span
                                key={movimento.id}
                                className="mr-1 mb-1 inline-flex rounded bg-slate-950 px-2 py-0.5 font-semibold text-slate-400 border border-slate-800"
                              >
                                {movimento.tipo}: {movimento.quantidade}
                              </span>
                            ))
                          )}
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {/* MODAL AJUSTAR USO DE MATERIAL */}
      {editModalOpen && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl w-full max-w-sm overflow-hidden shadow-2xl">
            <div className="p-5 border-b border-slate-800 flex justify-between items-center bg-slate-950">
              <h2 className="text-sm font-bold text-white truncate">
                Lançar Auditado: {selectedMaterial?.nome}
              </h2>
              <button
                onClick={() => setEditModalOpen(false)}
                className="text-slate-400 hover:text-white"
              >
                <X size={20} />
              </button>
            </div>
            <form onSubmit={salvarAjusteEstoque} className="p-6 space-y-4">
              <div>
                <label className="text-[10px] font-bold text-slate-400 uppercase block mb-1">
                  Quantidade Prevista
                </label>
                <input
                  type="text"
                  disabled
                  value={selectedMaterial?.previsto}
                  className="w-full p-2.5 bg-slate-950 border border-slate-800 rounded-lg text-sm text-slate-500 outline-none cursor-not-allowed"
                />
              </div>
              <div>
                <label className="text-[10px] font-bold text-slate-400 uppercase block mb-1">
                  Quantidade Real Utilizada *
                </label>
                <div className="grid grid-cols-[42px_1fr_42px] gap-2">
                  <button
                    type="button"
                    onClick={() =>
                      setNovaQuantidade(
                        String(Math.max(0, quantidadeAuditadaModal - incrementoAuditoria)),
                      )
                    }
                    className="bg-slate-950 border border-slate-800 rounded-lg text-slate-300 hover:text-white"
                  >
                    -
                  </button>
                  <input
                    type="number"
                    required
                    min="0"
                    step={incrementoAuditoria}
                    value={novaQuantidade}
                    onChange={(e) => setNovaQuantidade(e.target.value)}
                    className="w-full p-2.5 bg-slate-950 border border-slate-700 rounded-lg text-sm text-white outline-none focus:border-indigo-500 text-center font-mono"
                  />
                  <button
                    type="button"
                    onClick={() =>
                      setNovaQuantidade(String(quantidadeAuditadaModal + incrementoAuditoria))
                    }
                    className="bg-slate-950 border border-slate-800 rounded-lg text-slate-300 hover:text-white"
                  >
                    +
                  </button>
                </div>
                <div className="mt-2 grid grid-cols-3 gap-2">
                  <button
                    type="button"
                    onClick={() => setNovaQuantidade("0")}
                    className="rounded-lg border border-slate-800 bg-slate-950 py-2 text-xs font-bold text-slate-400 hover:text-white"
                  >
                    Zerado
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      setNovaQuantidade(String(movimentacaoFisicaModal.consumoLiquido))
                    }
                    className="rounded-lg border border-slate-800 bg-slate-950 py-2 text-xs font-bold text-slate-400 hover:text-white"
                  >
                    Consumo OR
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      setNovaQuantidade(String(selectedMaterial?.previsto || 0))
                    }
                    className="rounded-lg border border-slate-800 bg-slate-950 py-2 text-xs font-bold text-slate-400 hover:text-white"
                  >
                    Igual Prev.
                  </button>
                </div>
              </div>
              <div className="rounded-xl border border-indigo-500/20 bg-indigo-500/5 p-3 text-xs text-indigo-200">
                <p className="font-black uppercase text-[10px] text-indigo-300">Movimentação física da OR</p>
                <p className="mt-1">
                  Retirado: {movimentacaoFisicaModal.retirado} · Devolvido: {movimentacaoFisicaModal.devolvido} · Consumo líquido: {movimentacaoFisicaModal.consumoLiquido}
                </p>
              </div>
              <div className="rounded-xl border border-slate-800 bg-slate-950 p-3 text-xs">
                {saldoModal === 0 ? (
                  <span className="font-bold text-emerald-400">Balanço perfeito</span>
                ) : saldoModal > 0 ? (
                  <span className="font-bold text-blue-400">
                    Sobra registrada: {saldoModal} unidade(s)
                  </span>
                ) : (
                  <span className="font-bold text-rose-400">
                    Falta/excesso registrado: {Math.abs(saldoModal)} unidade(s)
                  </span>
                )}
              </div>
              <button
                type="submit"
                className="w-full bg-indigo-600 hover:bg-indigo-500 text-white py-2.5 rounded-lg font-bold text-sm transition-colors shadow-lg"
              >
                Salvar Ajuste Contábil
              </button>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
