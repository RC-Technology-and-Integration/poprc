import { custoPlanilhaParaEstoque, ehCaboEmBobina305 } from "./planilhaEstoque.js";

const numero = (valor) => {
  const convertido = Number(valor || 0);
  return Number.isFinite(convertido) ? convertido : 0;
};

const normalizar = (valor) => String(valor || "")
  .normalize("NFD")
  .replace(/[\u0300-\u036f]/g, "")
  .trim()
  .toLowerCase();

const controlaSaldoFracionado = (material) =>
  ["FRACIONADO", "METRAGEM", "BOBINA", "ROLO"].includes(material?.tipoControle);

const arredondarCentavos = (valor) => {
  const absoluto = Math.abs(numero(valor));
  return Math.sign(valor) * Math.round((absoluto + Number.EPSILON) * 100) / 100;
};

const custoInicial = (material) => {
  const custo = numero(material?.custoMedio);
  if (material?.tipoControle === "FRACIONADO" && ehCaboEmBobina305(material.nome)) {
    return custoPlanilhaParaEstoque(material.nome, custo);
  }
  return custo;
};

export const saldoDisponivelMaterial = (material) => {
  if (!material) return 0;
  const disponivel = controlaSaldoFracionado(material)
    ? numero(material.metragemDisponivel)
    : numero(material.quantidadeDisponivel);
  const reservado = controlaSaldoFracionado(material)
    ? numero(material.metragemReservada)
    : numero(material.quantidadeReservada);
  return Math.max(0, disponivel - reservado);
};

export const calcularSimulacaoRetirada = (materiais, solicitacoes) => {
  const porId = new Map((materiais || []).map((material) => [String(material.id), material]));
  const itens = (solicitacoes || [])
    .map((solicitacao) => {
      const material = porId.get(String(solicitacao.materialId));
      const quantidade = numero(solicitacao.quantidade);
      if (!material || quantidade <= 0) return null;
      const saldoAtual = saldoDisponivelMaterial(material);
      const saldoProjetado = saldoAtual - quantidade;
      const custoUnitario = numero(material.custoMedio);
      return {
        ...solicitacao,
        material,
        quantidade,
        saldoAtual,
        saldoProjetado,
        quantidadeFaltante: Math.max(0, -saldoProjetado),
        valorSolicitado: arredondarCentavos(quantidade * custoUnitario),
      };
    })
    .filter(Boolean);

  return {
    itens,
    possuiFalta: itens.some((item) => item.quantidadeFaltante > 0),
    quantidadeSolicitada: itens.reduce((total, item) => total + item.quantidade, 0),
    quantidadeFaltante: itens.reduce((total, item) => total + item.quantidadeFaltante, 0),
    valorSolicitado: arredondarCentavos(
      itens.reduce((total, item) => total + item.valorSolicitado, 0),
    ),
  };
};

const criarResumo = (comarca) => ({
  comarca,
  itens: new Map(),
  ordens: [],
  totalRetirado: 0,
  totalDevolvido: 0,
  totalFaltante: 0,
  valorLiquido: 0,
});

const criarResumoOrdem = (ordem, comarca) => ({
  id: ordem?.id != null ? String(ordem.id) : `planilha:${ordem?.aba || "sem-origem"}`,
  numeroOr: ordem?.numeroOr || "Retirada importada",
  status: ordem?.status || "HISTORICA_IMPORTADA",
  comarca,
  abasOrigem: new Set(),
  itens: new Map(),
  totalRetirado: 0,
  totalDevolvido: 0,
  totalFaltante: 0,
  valorLiquido: 0,
});

const localizarMaterial = (materiais, id, nome) =>
  (id != null ? materiais.find((material) => String(material.id) === String(id)) : null)
  || materiais.find((material) => normalizar(material.nome) === normalizar(nome));

const adicionarQuantidade = (resumo, material, nome, retirada, devolvida, numeroOr) => {
  const chave = String(material?.id || normalizar(nome));
  const item = resumo.itens.get(chave) || {
    material,
    nome: nome || material?.nome || "Material",
    retirada: 0,
    devolvida: 0,
    faltante: 0,
    custoUnitario: custoInicial(material),
    ordens: new Set(),
  };
  item.retirada += numero(retirada);
  item.devolvida += numero(devolvida);
  if (numeroOr) item.ordens.add(numeroOr);
  resumo.itens.set(chave, item);
};

const enriquecerAuditoria = (resumo, retirada, material) => {
  const chave = String(material?.id || normalizar(retirada.material));
  const item = resumo.itens.get(chave) || {
    material,
    nome: retirada.material || material?.nome || "Material",
    retirada: 0,
    devolvida: 0,
    faltante: 0,
    custoUnitario: numero(retirada.custoUnitario ?? custoInicial(material)),
    ordens: new Set(),
  };
  item.faltante = Math.max(item.faltante, numero(retirada.quantidadeFaltante));
  item.custoUnitario = numero(retirada.custoUnitario ?? item.custoUnitario);
  resumo.itens.set(chave, item);
  if (retirada.aba) resumo.abasOrigem?.add(retirada.aba);
};

const finalizarResumo = (resumo) => {
  const itens = [...resumo.itens.values()]
    .map((item) => ({
      ...item,
      saldoLiquido: item.retirada - item.devolvida,
      valorAtribuido: item.valorAtribuido ?? arredondarCentavos(
        (item.retirada - item.devolvida) * numero(item.custoUnitario),
      ),
      ordens: [...item.ordens],
    }))
    .sort((a, b) => a.nome.localeCompare(b.nome, "pt-BR"));
  return {
    ...resumo,
    itens,
    abasOrigem: resumo.abasOrigem ? [...resumo.abasOrigem] : undefined,
    totalRetirado: itens.reduce((total, item) => total + item.retirada, 0),
    totalDevolvido: itens.reduce((total, item) => total + item.devolvida, 0),
    totalFaltante: itens.reduce((total, item) => total + item.faltante, 0),
    valorLiquido: arredondarCentavos(itens.reduce(
      (total, item) => total + item.valorAtribuido,
      0,
    )),
  };
};

export const consolidarRetiradasPorObra = ({
  comarcas = [],
  materiais = [],
  ordensRetirada = [],
  retiradasImportadas = [],
}) => {
  const obras = new Map(
    comarcas.map((comarca) => [String(comarca.id), criarResumo(comarca)]),
  );
  const ordens = new Map();

  ordensRetirada.forEach((ordem) => {
    if (!ordem.comarca?.id) return;
    const chaveComarca = String(ordem.comarca.id);
    if (!obras.has(chaveComarca)) obras.set(chaveComarca, criarResumo(ordem.comarca));
    const resumoOrdem = criarResumoOrdem(ordem, ordem.comarca);
    (ordem.itens || []).forEach((item) => {
      const retirada = numero(item.quantidadeRetirada);
      const devolvida = numero(item.quantidadeDevolvida);
      if (retirada === 0 && devolvida === 0) return;
      const material = localizarMaterial(materiais, item.material?.id, item.nomeMaterial);
      adicionarQuantidade(
        resumoOrdem,
        material,
        item.nomeMaterial,
        retirada,
        devolvida,
        ordem.numeroOr,
      );
    });
    ordens.set(String(ordem.id), resumoOrdem);
    obras.get(chaveComarca).ordens.push(resumoOrdem);
  });

  retiradasImportadas.forEach((retirada) => {
    if (!retirada.comarcaId) return;
    const chaveComarca = String(retirada.comarcaId);
    const comarca = comarcas.find((item) => String(item.id) === chaveComarca) || {
      id: retirada.comarcaId,
      nomeComarca: retirada.comarca,
      ordemServico: { numeroOs: retirada.numeroOs },
    };
    if (!obras.has(chaveComarca)) obras.set(chaveComarca, criarResumo(comarca));
    const material = localizarMaterial(materiais, retirada.materialId, retirada.material);
    let resumoOrdem = retirada.ordemRetiradaId != null
      ? ordens.get(String(retirada.ordemRetiradaId))
      : null;

    if (!resumoOrdem) {
      const chaveSintetica = `${chaveComarca}:${retirada.numeroOr || retirada.aba}`;
      resumoOrdem = ordens.get(chaveSintetica);
      if (!resumoOrdem) {
        resumoOrdem = criarResumoOrdem({
          id: chaveSintetica,
          numeroOr: retirada.numeroOr,
          aba: retirada.aba,
        }, comarca);
        ordens.set(chaveSintetica, resumoOrdem);
        obras.get(chaveComarca).ordens.push(resumoOrdem);
      }
      adicionarQuantidade(
        resumoOrdem,
        material,
        retirada.material,
        retirada.quantidadeRetirada,
        0,
        retirada.numeroOr || `Planilha ${retirada.aba}`,
      );
    }
    enriquecerAuditoria(resumoOrdem, retirada, material);
  });

  return [...obras.values()]
    .map((obra) => {
      const ordensFinalizadas = obra.ordens
        .map(finalizarResumo)
        .sort((a, b) => a.numeroOr.localeCompare(b.numeroOr, "pt-BR"));
      const resumoObra = criarResumo(obra.comarca);
      ordensFinalizadas.forEach((ordem) => {
        ordem.itens.forEach((item) => adicionarQuantidade(
          resumoObra,
          item.material,
          item.nome,
          item.retirada,
          item.devolvida,
          ordem.numeroOr,
        ));
        ordem.itens.forEach((item) => {
          const chave = String(item.material?.id || normalizar(item.nome));
          const consolidado = resumoObra.itens.get(chave);
          consolidado.faltante += item.faltante;
          consolidado.custoUnitario = item.custoUnitario;
          consolidado.valorAtribuido = arredondarCentavos(
            numero(consolidado.valorAtribuido) + item.valorAtribuido,
          );
        });
      });
      return {
        ...finalizarResumo(resumoObra),
        ordens: ordensFinalizadas,
      };
    })
    .sort((a, b) => String(a.comarca?.nomeComarca || "")
      .localeCompare(String(b.comarca?.nomeComarca || ""), "pt-BR"));
};
