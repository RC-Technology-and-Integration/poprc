export const normalizarTextoPlanilha = (valor) =>
  String(valor ?? "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-zA-Z0-9]+/g, " ")
    .trim()
    .replace(/\s+/g, " ")
    .toLowerCase();

export const METROS_POR_BOBINA_CABO = 305;

export const arredondarQuantidadeEstoque = (valor) =>
  Number(Number(valor || 0).toFixed(3));

export const resumirAvisosImportacao = (avisos = [], avisosRetiradas = []) => ({
  total: avisos.length,
  retiradas: avisosRetiradas.length,
  gerais: Math.max(0, avisos.length - avisosRetiradas.length),
  bloqueiaImportacao: avisos.length > 0,
  bloqueiaReconciliacao: avisosRetiradas.length > 0,
});

export const resolverSaldoBaseCadastro = (
  estoqueCalculado,
  estoqueInformado,
  incluiRetornos,
) => (incluiRetornos && Number.isFinite(estoqueInformado)
  ? estoqueInformado
  : estoqueCalculado);

export const saldoCadastroIncluiRetornos = (sheet, cabecalho) => {
  if (!sheet || !cabecalho?.aposAdicoes) return false;
  const quantidadeLinhas = Math.max(0, Math.min(10, sheet.rowCount - cabecalho.linha));
  return Array.from(
    { length: quantidadeLinhas },
    (_, indice) => cabecalho.linha + indice + 1,
  ).some((linha) => {
    const valor = sheet.getCell(linha, cabecalho.aposAdicoes).value;
    const formula = valor && typeof valor === "object" ? valor.formula : null;
    return normalizarTextoPlanilha(formula).includes("sobras retornos");
  });
};

export const ehCaboEmBobina305 = (nome) => {
  const normalizado = normalizarTextoPlanilha(nome);
  return (normalizado.includes("caixa") && normalizado.includes("cabo")
      && normalizado.includes("cat6"))
    || (normalizado.includes("bobina") && normalizado.includes("cabo"));
};

export const quantidadePlanilhaParaEstoque = (nome, quantidade) => {
  const valor = Number(quantidade);
  if (!Number.isFinite(valor)) return valor;
  return ehCaboEmBobina305(nome)
    ? arredondarQuantidadeEstoque(valor * METROS_POR_BOBINA_CABO)
    : valor;
};

export const interpretarSaldoPlanilha = (nome, quantidade) => {
  const saldoInformado = quantidadePlanilhaParaEstoque(nome, quantidade);
  if (!Number.isFinite(saldoInformado)) {
    return {
      valido: false,
      saldoInformado,
      saldo: 0,
      quantidadeFaltante: 0,
    };
  }
  return {
    valido: true,
    saldoInformado,
    saldo: arredondarQuantidadeEstoque(Math.max(0, saldoInformado)),
    quantidadeFaltante: arredondarQuantidadeEstoque(Math.max(0, -saldoInformado)),
  };
};

export const custoPlanilhaParaEstoque = (nome, custoUnitario) => {
  const valor = Number(custoUnitario);
  if (!Number.isFinite(valor)) return valor;
  return ehCaboEmBobina305(nome)
    ? Number((valor / METROS_POR_BOBINA_CABO).toFixed(4))
    : valor;
};

const caboLegadoEmBobinas = (material) =>
  ehCaboEmBobina305(material?.nome)
  && material?.tipoControle === "FRACIONADO";

export const valorDaCelula = (celula) => {
  if (celula?.result != null) return celula.result;
  const valor = celula?.value;
  if (valor && typeof valor === "object") {
    if (valor.result != null) return valor.result;
    if (valor.text != null) return valor.text;
    if (Array.isArray(valor.richText)) {
      return valor.richText.map((item) => item.text || "").join("");
    }
  }
  return valor;
};

export const celulaPossuiFormulaSemResultado = (celula) => {
  const valor = celula?.value;
  if (!valor || typeof valor !== "object") return false;
  return Boolean(valor.formula || valor.sharedFormula)
    && valor.result == null
    && celula?.result == null;
};

export const localizarCabecalhoEstoque = (sheet) => {
  const limiteLinhas = Math.min(sheet.rowCount, 20);
  const limiteColunas = Math.min(sheet.columnCount, 15);
  for (let linha = 1; linha <= limiteLinhas; linha += 1) {
    const mapa = {};
    for (let coluna = 1; coluna <= limiteColunas; coluna += 1) {
      const texto = normalizarTextoPlanilha(valorDaCelula(sheet.getCell(linha, coluna)));
      if (texto === "produto" && !mapa.produto) mapa.produto = coluna;
      if ([
        "estoque atual",
        "estoque apos retiradas",
        "quantidade em estoque",
      ].includes(texto) && !mapa.quantidade) mapa.quantidade = coluna;
      if (texto === "valor unitario" && !mapa.custo) mapa.custo = coluna;
      if (texto === "quantidade da retirada" && !mapa.retirada) mapa.retirada = coluna;
      if (texto === "data de retirada" && !mapa.dataRetirada) mapa.dataRetirada = coluna;
      if (texto === "estoque apos retirada" && !mapa.saldoFinal) mapa.saldoFinal = coluna;
    }
    if (mapa.produto && mapa.quantidade && mapa.custo) {
      return { linha, ...mapa };
    }
  }
  return null;
};

const dataPlanilhaParaIso = (valor) => {
  if (valor instanceof Date && !Number.isNaN(valor.getTime())) {
    return valor.toISOString().slice(0, 10);
  }
  if (typeof valor === "number" && Number.isFinite(valor)) {
    const data = new Date(Date.UTC(1899, 11, 30) + valor * 86400000);
    return Number.isNaN(data.getTime()) ? null : data.toISOString().slice(0, 10);
  }
  if (typeof valor === "string") {
    const correspondencia = valor.trim().match(/^(\d{1,2})\/(\d{1,2})\/(\d{2,4})$/);
    if (correspondencia) {
      const [, dia, mes, anoInformado] = correspondencia;
      const ano = anoInformado.length === 2 ? `20${anoInformado}` : anoInformado;
      return `${ano}-${mes.padStart(2, "0")}-${dia.padStart(2, "0")}`;
    }
  }
  return null;
};

const saldoDisponivel = (material) => {
  const fracionado = ["FRACIONADO", "METRAGEM", "BOBINA", "ROLO"]
    .includes(material?.tipoControle);
  const disponivel = fracionado
    ? Number(material?.metragemDisponivel ?? 0)
    : Number(material?.quantidadeDisponivel ?? 0);
  const reservado = fracionado
    ? Number(material?.metragemReservada ?? 0)
    : Number(material?.quantidadeReservada ?? 0);
  const saldo = Math.max(0, disponivel - reservado);
  return caboLegadoEmBobinas(material)
    ? quantidadePlanilhaParaEstoque(material.nome, saldo)
    : saldo;
};

const custoDisponivel = (material) => {
  const custo = Math.max(0, Number(material?.custoMedio ?? 0));
  return caboLegadoEmBobinas(material) ? custo / METROS_POR_BOBINA_CABO : custo;
};

const saldoTotalMaterial = (material) => {
  const fracionado = ["FRACIONADO", "METRAGEM", "BOBINA", "ROLO"]
    .includes(material?.tipoControle);
  const saldo = fracionado
    ? Number(material?.metragemDisponivel ?? 0)
    : Number(material?.quantidadeDisponivel ?? 0);
  return caboLegadoEmBobinas(material)
    ? quantidadePlanilhaParaEstoque(material.nome, saldo)
    : saldo;
};

const saldoReservadoMaterial = (material) => {
  const fracionado = ["FRACIONADO", "METRAGEM", "BOBINA", "ROLO"]
    .includes(material?.tipoControle);
  const reservado = fracionado
    ? Number(material?.metragemReservada ?? 0)
    : Number(material?.quantidadeReservada ?? 0);
  return caboLegadoEmBobinas(material)
    ? quantidadePlanilhaParaEstoque(material.nome, reservado)
    : reservado;
};

// No XLSX, fórmulas que exibem vazio podem chegar sem resultado armazenado.
// Só são uma extensão vazia quando não há dados que exijam identificar o produto.
const celulaSemConteudoSincronizacao = (celula) => {
  if (celulaPossuiFormulaSemResultado(celula)) return true;
  const valor = valorDaCelula(celula);
  return valor == null || (typeof valor === "string" && !valor.trim());
};

export const extrairSincronizacaoSaldos = (workbook, materiais = []) => {
  const origem = workbook.worksheets.find(
    (sheet) => normalizarTextoPlanilha(sheet.name) === "estoque atual",
  );
  const cabecalho = origem ? localizarCabecalhoEstoque(origem) : null;
  if (!origem || !cabecalho) return null;

  const materiaisPorNome = materiais.reduce((mapa, material) => {
    const chave = normalizarTextoPlanilha(material.nome);
    mapa.set(chave, [...(mapa.get(chave) || []), material]);
    return mapa;
  }, new Map());
  const nomesLidos = new Set();
  const itens = [];
  const avisos = [];

  for (let linha = cabecalho.linha + 1; linha <= origem.rowCount; linha += 1) {
    const celulaNome = origem.getCell(linha, cabecalho.produto);
    const celulaSaldo = origem.getCell(linha, cabecalho.quantidade);
    const nomeBruto = valorDaCelula(celulaNome);
    const nome = typeof nomeBruto === "string" ? nomeBruto.trim() : "";
    const chave = normalizarTextoPlanilha(nome);
    if (!chave) {
      if (celulaSemConteudoSincronizacao(celulaNome)
        && [celulaSaldo, origem.getCell(linha, cabecalho.custo)]
          .every(celulaSemConteudoSincronizacao)) continue;
      avisos.push(`Linha ${linha}: produto ausente ou inválido; confira o nome e o resultado da fórmula na planilha.`);
      continue;
    }
    if (chave.startsWith("valor total") || chave === "total geral") continue;

    const saldoBruto = valorDaCelula(celulaSaldo);
    const saldoLegivel = !celulaSemConteudoSincronizacao(celulaSaldo)
      && ["number", "string"].includes(typeof saldoBruto);
    const saldoInterpretado = interpretarSaldoPlanilha(nome, saldoLegivel ? saldoBruto : NaN);
    const { saldoInformado } = saldoInterpretado;
    const correspondencias = materiaisPorNome.get(chave) || [];
    const material = correspondencias.length === 1 ? correspondencias[0] : null;
    const erros = [];
    if (celulaPossuiFormulaSemResultado(celulaSaldo)) {
      erros.push("Saldo com fórmula sem resultado disponível; recalcule e exporte a planilha");
    } else if (celulaSemConteudoSincronizacao(celulaSaldo)) {
      erros.push("Saldo não informado");
    } else if (!saldoInterpretado.valido) {
      erros.push("Saldo inválido; confira erros de fórmula ou conteúdo não suportado");
    }
    if (nomesLidos.has(chave)) erros.push("Material repetido na planilha");
    nomesLidos.add(chave);
    if (correspondencias.length === 0) erros.push("Material não cadastrado no estoque");
    if (correspondencias.length > 1) erros.push("Nome duplicado no estoque");
    if (material && ["BOBINA", "ROLO"].includes(material.tipoControle)) {
      erros.push("Bobina/rolo rastreável exige conferência por unidade física");
    }

    const saldoDesejado = saldoInterpretado.saldo;
    const saldoAtual = material ? saldoTotalMaterial(material) : 0;
    const saldoReservado = material ? saldoReservadoMaterial(material) : 0;
    const controlaFracao = material && ["FRACIONADO", "METRAGEM"]
      .includes(material.tipoControle);
    if (material && !controlaFracao && !Number.isInteger(saldoDesejado)) {
      erros.push("O saldo de um material por unidade precisa ser inteiro");
    }
    if (material && saldoDesejado + 0.0005 < saldoReservado) {
      erros.push(`Saldo inferior ao reservado (${saldoReservado})`);
    }
    const diferenca = arredondarQuantidadeEstoque(saldoDesejado - saldoAtual);
    const custoUnitario = material ? custoDisponivel(material) : 0;
    const acao = erros.length > 0
      ? "BLOQUEADO"
      : diferenca > 0.0005
        ? "AUMENTAR"
        : diferenca < -0.0005 ? "REDUZIR" : "SEM_ALTERACAO";

    if (erros.length > 0) {
      avisos.push(`Linha ${linha}: ${nome} - ${erros.join("; ").toLowerCase()}.`);
    }
    itens.push({
      nome,
      materialId: material?.id,
      materialNome: material?.nome,
      linhaOrigem: linha,
      saldoAtual,
      saldoReservado,
      saldoInformado: Number.isFinite(saldoInformado) ? saldoInformado : 0,
      saldo: saldoDesejado,
      quantidadeFaltante: saldoInterpretado.quantidadeFaltante,
      diferenca,
      custoUnitario,
      valorAnterior: Number((saldoAtual * custoUnitario).toFixed(2)),
      valorTotal: Number((saldoDesejado * custoUnitario).toFixed(2)),
      acao,
      erros,
    });
  }

  materiais
    .filter((material) => material.ativo !== false)
    .filter((material) => !["BOBINA", "ROLO"].includes(material.tipoControle))
    .forEach((material) => {
      if (!nomesLidos.has(normalizarTextoPlanilha(material.nome))) {
        avisos.push(`O material ${material.nome} está ativo, mas não aparece na aba ESTOQUE ATUAL.`);
      }
    });

  return { abaOrigem: origem.name, itens, avisos };
};

const localizarCabecalhoCustos = (sheet) => {
  const nomesProduto = new Set(["produto", "material", "nome", "nome descricao", "descricao"]);
  const nomesCusto = new Set([
    "valor unitario",
    "custo unitario",
    "custo medio",
    "valor",
  ]);
  for (let linha = 1; linha <= Math.min(sheet.rowCount, 20); linha += 1) {
    let produto;
    let custo;
    for (let coluna = 1; coluna <= Math.min(sheet.columnCount, 20); coluna += 1) {
      const texto = normalizarTextoPlanilha(valorDaCelula(sheet.getCell(linha, coluna)));
      if (!produto && nomesProduto.has(texto)) produto = coluna;
      if (!custo && nomesCusto.has(texto)) custo = coluna;
    }
    if (produto && custo && produto !== custo) return { linha, produto, custo };
  }
  return null;
};

export const extrairAtualizacaoCustos = (workbook, materiais = []) => {
  const candidatas = [...workbook.worksheets].sort((a, b) => {
    const aCadastro = normalizarTextoPlanilha(a.name) === "cadastro produtos" ? 0 : 1;
    const bCadastro = normalizarTextoPlanilha(b.name) === "cadastro produtos" ? 0 : 1;
    return aCadastro - bCadastro;
  });
  const origem = candidatas
    .map((sheet) => ({ sheet, cabecalho: localizarCabecalhoCustos(sheet) }))
    .find(({ cabecalho }) => cabecalho);
  if (!origem) return null;

  const materiaisPorNome = materiais.reduce((mapa, material) => {
    const chave = normalizarTextoPlanilha(material.nome);
    mapa.set(chave, [...(mapa.get(chave) || []), material]);
    return mapa;
  }, new Map());
  const nomesLidos = new Set();
  const itens = [];
  const avisos = [];

  for (let linha = origem.cabecalho.linha + 1; linha <= origem.sheet.rowCount; linha += 1) {
    const nome = String(
      valorDaCelula(origem.sheet.getCell(linha, origem.cabecalho.produto)) || "",
    ).trim();
    const chave = normalizarTextoPlanilha(nome);
    if (!chave || chave === "total" || chave.startsWith("valor total")) continue;

    const custoInformado = Number(
      valorDaCelula(origem.sheet.getCell(linha, origem.cabecalho.custo)),
    );
    const correspondencias = materiaisPorNome.get(chave) || [];
    const erros = [];
    if (!Number.isFinite(custoInformado) || custoInformado < 0) {
      erros.push("Valor unitário inválido");
    }
    if (nomesLidos.has(chave)) erros.push("Material repetido na planilha");
    nomesLidos.add(chave);
    if (correspondencias.length > 1) erros.push("Nome duplicado no estoque");

    const material = correspondencias.length === 1 ? correspondencias[0] : null;
    const custoUnitario = Number.isFinite(custoInformado)
      ? custoPlanilhaParaEstoque(nome, custoInformado)
      : 0;
    const custoAnterior = material ? custoDisponivel(material) : 0;
    const ignorado = !material && erros.length === 0;
    const semAlteracao = material
      && erros.length === 0
      && Math.abs(custoAnterior - custoUnitario) < 0.00005;

    if (erros.length > 0) {
      avisos.push(`Linha ${linha}: ${nome} - ${erros.join("; ").toLowerCase()}.`);
    }
    itens.push({
      nome,
      materialId: material?.id,
      materialNome: material?.nome,
      custoAnterior,
      custoInformado: Number.isFinite(custoInformado) ? custoInformado : 0,
      custoUnitario,
      linhaOrigem: linha,
      conversaoBobina: ehCaboEmBobina305(nome),
      acao: ignorado ? "IGNORAR" : semAlteracao ? "SEM_ALTERACAO" : "ATUALIZAR_CUSTO",
      erros,
    });
  }

  return {
    abaOrigem: origem.sheet.name,
    itens,
    avisos,
  };
};

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

export const extrairOrdensRetiradaAvulsas = (workbook, materiais = []) => {
  const materiaisPorNome = materiais.reduce((mapa, material) => {
    const chave = normalizarTextoPlanilha(material.nome);
    mapa.set(chave, [...(mapa.get(chave) || []), material]);
    return mapa;
  }, new Map());
  const saldoProjetado = new Map();
  const itensAfetados = new Map();
  const avisos = [];

  const abasRetiradas = workbook.worksheets
    .filter((sheet) => normalizarTextoPlanilha(sheet.name).startsWith("ordem de retirada"))
    .map((sheet) => ({ sheet, cabecalho: localizarCabecalhoEstoque(sheet) }))
    .filter(({ cabecalho }) => cabecalho?.retirada && cabecalho?.saldoFinal)
    .map(({ sheet, cabecalho }) => {
      const itens = [];
      for (let linha = cabecalho.linha + 1; linha <= sheet.rowCount; linha += 1) {
        const nome = String(valorDaCelula(sheet.getCell(linha, cabecalho.produto)) || "").trim();
        const chave = normalizarTextoPlanilha(nome);
        if (!chave || chave.startsWith("total") || chave.startsWith("valor total")) continue;

        const quantidadeRetirada = quantidadePlanilhaParaEstoque(
          nome,
          valorDaCelula(sheet.getCell(linha, cabecalho.retirada)),
        );
        if (!Number.isFinite(quantidadeRetirada) || quantidadeRetirada <= 0) continue;

        const correspondencias = materiaisPorNome.get(chave) || [];
        if (correspondencias.length !== 1) {
          const erro = correspondencias.length === 0
            ? "Material não cadastrado no estoque"
            : "Nome duplicado no estoque";
          avisos.push(`Aba ${sheet.name}, linha ${linha}: ${nome} - ${erro.toLowerCase()}.`);
          if (!itensAfetados.has(chave)) {
            itensAfetados.set(chave, {
              nome,
              saldo: 0,
              saldoAtual: 0,
              custoUnitario: 0,
              linhaOrigem: linha,
              materialId: undefined,
              tipoControle: "UNIDADE",
              quantidadeSolicitada: quantidadeRetirada,
              saldoDepois: 0,
              quantidadeFaltante: quantidadeRetirada,
              acao: "RETIRAR",
              erros: [erro],
            });
          }
          continue;
        }

        const material = correspondencias[0];
        const erros = [];
        if (["BOBINA", "ROLO"].includes(material.tipoControle)) {
          erros.push("Bobina/rolo exige seleção das unidades físicas");
          avisos.push(
            `Aba ${sheet.name}, linha ${linha}: ${nome} usa controle físico individual.`,
          );
        }
        const saldoAtual = saldoDisponivel(material);
        const saldoInicial = saldoProjetado.has(chave)
          ? saldoProjetado.get(chave)
          : saldoAtual;
        const saldoFinal = arredondarQuantidadeEstoque(
          saldoInicial - quantidadeRetirada,
        );
        saldoProjetado.set(chave, Math.max(0, saldoFinal));
        const custoUnitario = custoDisponivel(material);

        if (!itensAfetados.has(chave)) {
          itensAfetados.set(chave, {
            nome: material.nome,
            saldo: saldoAtual,
            saldoAtual,
            custoUnitario,
            linhaOrigem: linha,
            materialId: material.id,
            tipoControle: material.tipoControle,
            quantidadeSolicitada: 0,
            acao: "RETIRAR",
            erros,
          });
        }
        const itemAfetado = itensAfetados.get(chave);
        itemAfetado.quantidadeSolicitada += quantidadeRetirada;
        itemAfetado.saldoDepois = Math.max(0, saldoFinal);
        itemAfetado.quantidadeFaltante = Math.max(
          0,
          itemAfetado.quantidadeSolicitada - itemAfetado.saldoAtual,
        );
        itens.push({
          nomeMaterial: material.nome,
          linhaOrigem: linha,
          saldoInicial,
          quantidadeRetirada,
          saldoFinal,
          quantidadeFaltante: arredondarQuantidadeEstoque(Math.max(0, -saldoFinal)),
          custoUnitario,
          dataRetirada: cabecalho.dataRetirada
            ? dataPlanilhaParaIso(valorDaCelula(sheet.getCell(linha, cabecalho.dataRetirada)))
            : null,
        });
      }
      return {
        nome: sheet.name,
        cidade: identificarCidade(sheet),
        itens,
        totalRetirado: itens.reduce((total, item) => total + item.quantidadeRetirada, 0),
        faltas: itens.filter((item) => item.quantidadeFaltante > 0).length,
      };
    })
    .filter((aba) => aba.itens.length > 0);

  return {
    abasRetiradas,
    itens: Array.from(itensAfetados.values()),
    avisos,
  };
};
