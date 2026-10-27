import test from "node:test";
import assert from "node:assert/strict";
import ExcelJS from "exceljs";
import {
  celulaPossuiFormulaSemResultado,
  custoPlanilhaParaEstoque,
  extrairAtualizacaoCustos,
  extrairSincronizacaoSaldos,
  extrairOrdensRetiradaAvulsas,
  interpretarSaldoPlanilha,
  quantidadePlanilhaParaEstoque,
  localizarCabecalhoEstoque,
  resolverSaldoBaseCadastro,
  resumirAvisosImportacao,
  saldoCadastroIncluiRetornos,
  valorDaCelula,
} from "./planilhaEstoque.js";

const planilhaSaldosSintetica = () => {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet("ESTOQUE ATUAL");
  sheet.addRow(["Produto", "Estoque atual", "Valor unitário"]);
  return { workbook, sheet };
};

const materialSintetico = (id, nome) => ({
  id, nome, ativo: true, tipoControle: "UNIDADE",
  quantidadeDisponivel: 3, quantidadeReservada: 0, custoMedio: 2,
});

test("XLSX sintético preserva 78 produtos e ignora 22 extensões de fórmulas vazias", async () => {
  const { workbook, sheet } = planilhaSaldosSintetica();
  const materiais = [];
  for (let id = 1; id <= 78; id += 1) {
    const nome = `Produto sintético ${id}`;
    materiais.push(materialSintetico(id, nome));
    sheet.addRow([
      { formula: `"${nome}"`, result: nome },
      { formula: `${id - 1}`, result: id - 1 },
      2,
    ]);
  }
  for (let i = 0; i < 22; i += 1) {
    sheet.addRow([
      { formula: 'IF(1=1,"","produto")', result: "" },
      { formula: 'IF(1=1,"",1)', result: "" },
      { formula: 'IF(1=1,"",2)', result: "" },
    ]);
  }
  const reaberto = new ExcelJS.Workbook();
  await reaberto.xlsx.load(await workbook.xlsx.writeBuffer());
  const resultado = extrairSincronizacaoSaldos(reaberto, materiais);
  assert.equal(resultado.itens.length, 78);
  assert.deepEqual(resultado.avisos, []);
  assert.deepEqual(resultado.itens.map(i => i.nome), materiais.map(m => m.nome));
  assert.deepEqual(resultado.itens.map(i => i.saldoInformado), materiais.map(m => m.id - 1));
  assert.ok(resultado.itens.every(i => i.erros.length === 0));
});

for (const [rotulo, valor, mensagem] of [
  ["vazio", null, /Saldo não informado/],
  ["texto vazio", "  ", /Saldo não informado/],
  ["fórmula vazia", { formula: '""', result: "" }, /Saldo não informado/],
  ["fórmula sem cache", { formula: "B9" }, /fórmula sem resultado disponível/],
  ["fórmula compartilhada sem cache", { sharedFormula: "B2" }, /fórmula sem resultado disponível/],
  ["erro de fórmula", { formula: "1/0", result: { error: "#DIV/0!" } }, /Saldo inválido/],
  ["erro de célula", { error: "#REF!" }, /Saldo inválido/],
  ["booleano", false, /Saldo inválido/],
  ["data", new Date("2026-01-01T00:00:00Z"), /Saldo inválido/],
  ["texto não numérico", "indisponível", /Saldo inválido/],
]) {
  test(`sincronização bloqueia saldo obrigatório: ${rotulo}`, () => {
    const { workbook, sheet } = planilhaSaldosSintetica();
    sheet.addRow(["Produto sintético", 5, 2]);
    sheet.addRow(["Outro produto", valor, 2]);
    const resultado = extrairSincronizacaoSaldos(workbook, [
      materialSintetico(1, "Produto sintético"), materialSintetico(2, "Outro produto"),
    ]);
    assert.equal(resultado.itens.length, 2);
    assert.equal(resultado.itens[1].acao, "BLOQUEADO");
    assert.ok(resultado.itens[1].erros.some(e => mensagem.test(e)));
    assert.ok(resultado.avisos.some(a => a.includes("Linha 3: Outro produto")));
  });
}

test("sincronização não oculta linhas sem nome que contêm dados, inclusive zero ou erro", () => {
  for (const nome of [null, "", { formula: "A9" }, { formula: '""', result: "" },
    { formula: "A9", result: { error: "#REF!" } }, 12, false]) {
    const { workbook, sheet } = planilhaSaldosSintetica();
    sheet.addRow([nome, 0, 2]);
    const resultado = extrairSincronizacaoSaldos(workbook);
    assert.deepEqual(resultado.itens, []);
    assert.match(resultado.avisos[0], /Linha 2: produto ausente ou inválido/);
    assert.ok(!resultado.avisos.some(a => a.includes("[object Object]")));
  }
  const { workbook, sheet } = planilhaSaldosSintetica();
  sheet.addRow([{ error: "#REF!" }, null, null]);
  sheet.addRow([null, { error: "#REF!" }, null]);
  sheet.addRow([null, null, 0]);
  assert.equal(extrairSincronizacaoSaldos(workbook).avisos.length, 3);
});

test("sincronização lê nomes de texto e saldos válidos sem perder zero", () => {
  const { workbook, sheet } = planilhaSaldosSintetica();
  sheet.addRow([{ richText: [{ text: "Produto " }, { text: "A" }] }, 0, 2]);
  sheet.addRow([{ text: "Produto B", hyperlink: "https://example.com" }, "12", 2]);
  sheet.addRow([{ formula: '"Produto C"', result: "Produto C" }, { formula: "0", result: 0 }, 2]);
  const resultado = extrairSincronizacaoSaldos(workbook, ["A", "B", "C"].map(
    (letra, i) => materialSintetico(i + 1, `Produto ${letra}`),
  ));
  assert.deepEqual(resultado.avisos, []);
  assert.deepEqual(resultado.itens.map(i => i.saldoInformado), [0, 12, 0]);
});

test("extensões vazias não removem bloqueios de cadastro, duplicidade, reserva e população", () => {
  const { workbook, sheet } = planilhaSaldosSintetica();
  sheet.addRow(["Produto A", 0, 2]);
  sheet.addRow(["Produto A", 1, 2]);
  sheet.addRow(["Desconhecido", 1, 2]);
  sheet.addRow([{ formula: '""' }, { formula: '""' }, { formula: '""' }]);
  const resultado = extrairSincronizacaoSaldos(workbook, [
    { ...materialSintetico(1, "Produto A"), quantidadeReservada: 1 },
    materialSintetico(2, "Ausente"),
  ]);
  assert.equal(resultado.itens.length, 3);
  for (const mensagem of ["reservado", "repetido", "não cadastrado", "Ausente está ativo"]) {
    assert.ok(resultado.avisos.some(a => a.includes(mensagem)));
  }
});

test("contrato do helper mantém fórmulas, erros, textos e datas para os demais consumidores", () => {
  const data = new Date("2026-01-01T00:00:00Z");
  for (const valor of [null, 0, "", data, { formula: "B2" },
    { error: "#REF!" }, { objetoNaoSuportado: true }]) {
    assert.equal(valorDaCelula({ value: valor }), valor);
  }
  assert.equal(valorDaCelula({ value: { formula: "B2", result: 12 } }), 12);
  assert.equal(valorDaCelula({ value: { formula: "B2", result: 0 } }), 0);
  assert.equal(valorDaCelula({ value: { formula: "B2", result: "" } }), "");
  assert.equal(valorDaCelula({ value: { text: "Nome", hyperlink: "https://example.com" } }), "Nome");
  assert.equal(valorDaCelula({ value: { richText: [{ text: "No" }, { text: "me" }] } }), "Nome");
});

test("interpreta saldo negativo como falta sem criar estoque negativo", () => {
  assert.deepEqual(interpretarSaldoPlanilha("PORCA GAIOLA", -15), {
    valido: true,
    saldoInformado: -15,
    saldo: 0,
    quantidadeFaltante: 15,
  });
  assert.deepEqual(interpretarSaldoPlanilha("CAIXA DE CABO CAT6A", -0.1), {
    valido: true,
    saldoInformado: -30.5,
    saldo: 0,
    quantidadeFaltante: 30.5,
  });
  assert.equal(interpretarSaldoPlanilha("VELCRO", "sem saldo").valido, false);
});

test("separa os bloqueios da importação completa dos bloqueios das ORs", () => {
  const apenasCadastro = resumirAvisosImportacao(
    ["CADASTRO_PRODUTOS, linha 5: saldo inválido."],
    [],
  );
  const comRetirada = resumirAvisosImportacao(
    ["Linha 5: saldo inválido.", "Aba ORDEM DE RETIRADA, linha 8: item inválido."],
    ["Aba ORDEM DE RETIRADA, linha 8: item inválido."],
  );

  assert.deepEqual(apenasCadastro, {
    total: 1,
    retiradas: 0,
    gerais: 1,
    bloqueiaImportacao: true,
    bloqueiaReconciliacao: false,
  });
  assert.equal(comRetirada.total, 2);
  assert.equal(comRetirada.gerais, 1);
  assert.equal(comRetirada.retiradas, 1);
  assert.equal(comRetirada.bloqueiaImportacao, true);
  assert.equal(comRetirada.bloqueiaReconciliacao, true);
});

test("usa o saldo informado quando a fórmula do cadastro já inclui retornos", () => {
  assert.equal(resolverSaldoBaseCadastro(239, 255, true), 255);
  assert.equal(resolverSaldoBaseCadastro(239, 255, false), 239);
  assert.equal(resolverSaldoBaseCadastro(239, Number.NaN, true), 239);
});

test("detecta quando o saldo-base do cadastro já soma SOBRAS - RETORNOS", () => {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet("CADASTRO_PRODUTOS");
  sheet.getCell("A4").value = "PRODUTO";
  sheet.getCell("C4").value = "ESTOQUE APÓS ADIÇÕES";
  sheet.getCell("C5").value = {
    formula: "B5 + SUM(F5:L5) + 'SOBRAS - RETORNOS'!G5",
    result: 255,
  };

  assert.equal(saldoCadastroIncluiRetornos(sheet, { linha: 4, aposAdicoes: 3 }), true);
});

test("prepara sincronização exclusiva de saldos e preserva os custos do sistema", () => {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet("ESTOQUE ATUAL");
  sheet.getCell("A4").value = "Produto";
  sheet.getCell("B4").value = "Estoque após retiradas";
  sheet.getCell("C4").value = "Valor unitário";
  sheet.getCell("A5").value = "PATCH CORD";
  sheet.getCell("B5").value = 12;
  sheet.getCell("C5").value = 999;
  sheet.getCell("A6").value = "CAIXA DE CABO CAT6A";
  sheet.getCell("B6").value = 0.41;
  sheet.getCell("C6").value = 999;
  sheet.getCell("A7").value = "TERMINAL";
  sheet.getCell("B7").value = -3;
  sheet.getCell("C7").value = 999;

  const resultado = extrairSincronizacaoSaldos(workbook, [
    {
      id: 1,
      nome: "Patch Cord",
      ativo: true,
      tipoControle: "UNIDADE",
      quantidadeDisponivel: 10,
      quantidadeReservada: 2,
      custoMedio: 5,
    },
    {
      id: 2,
      nome: "Caixa de Cabo CAT6A",
      ativo: true,
      tipoControle: "METRAGEM",
      metragemDisponivel: 0,
      metragemReservada: 0,
      custoMedio: 6.8433,
    },
    {
      id: 3,
      nome: "Terminal",
      ativo: true,
      tipoControle: "UNIDADE",
      quantidadeDisponivel: 5,
      quantidadeReservada: 0,
      custoMedio: 8.09,
    },
  ]);

  assert.equal(resultado.avisos.length, 0);
  assert.equal(resultado.itens[0].saldoAtual, 10);
  assert.equal(resultado.itens[0].saldo, 12);
  assert.equal(resultado.itens[0].custoUnitario, 5);
  assert.equal(resultado.itens[0].acao, "AUMENTAR");
  assert.equal(resultado.itens[1].saldo, 125.05);
  assert.equal(resultado.itens[1].custoUnitario, 6.8433);
  assert.equal(resultado.itens[2].saldo, 0);
  assert.equal(resultado.itens[2].quantidadeFaltante, 3);
  assert.equal(resultado.itens[2].acao, "REDUZIR");
});

test("bloqueia sincronização incompleta ou abaixo do saldo reservado", () => {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet("ESTOQUE ATUAL");
  sheet.addRow(["Produto", "Estoque atual", "Valor unitário"]);
  sheet.addRow(["PATCH CORD", 1, 5]);

  const resultado = extrairSincronizacaoSaldos(workbook, [
    {
      id: 1,
      nome: "Patch Cord",
      ativo: true,
      tipoControle: "UNIDADE",
      quantidadeDisponivel: 10,
      quantidadeReservada: 2,
      custoMedio: 5,
    },
    {
      id: 2,
      nome: "Terminal",
      ativo: true,
      tipoControle: "UNIDADE",
      quantidadeDisponivel: 5,
      quantidadeReservada: 0,
      custoMedio: 8,
    },
  ]);

  assert.ok(resultado.itens[0].erros.some((erro) => erro.includes("reservado")));
  assert.ok(resultado.avisos.some((aviso) => aviso.includes("Terminal")));
});

test("extrai planilha exclusiva de custos sem criar saldo", () => {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet("CADASTRO_PRODUTOS");
  sheet.addRow(["PRODUTO", "VALOR UNITÁRIO"]);
  sheet.addRow(["TAMPA", 24.76]);
  sheet.addRow(["CAIXA DE CABO CAT6A", 2087.2]);
  sheet.addRow(["ITEM NÃO CADASTRADO", 50]);
  sheet.addRow(["VALOR TOTAL", 999]);

  const resultado = extrairAtualizacaoCustos(workbook, [
    { id: 1, nome: "Tampa", tipoControle: "UNIDADE", custoMedio: 20 },
    {
      id: 2,
      nome: "Caixa de Cabo CAT6A",
      tipoControle: "METRAGEM",
      custoMedio: 6,
    },
  ]);

  assert.equal(resultado.abaOrigem, "CADASTRO_PRODUTOS");
  assert.equal(resultado.avisos.length, 0);
  assert.equal(resultado.itens.length, 3);
  assert.equal(resultado.itens[0].materialId, 1);
  assert.equal(resultado.itens[0].custoUnitario, 24.76);
  assert.equal(resultado.itens[1].materialId, 2);
  assert.equal(resultado.itens[1].custoUnitario, 6.8433);
  assert.equal(resultado.itens[1].conversaoBobina, true);
  assert.equal(resultado.itens[2].acao, "IGNORAR");
});

test("localiza a primeira tabela quando existem quadros auxiliares na mesma linha", () => {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet("ESTOQUE ATUAL");
  sheet.getCell("A4").value = "Produto";
  sheet.getCell("B4").value = "Estoque APÓS RETIRADAS";
  sheet.getCell("C4").value = "Valor unitário";
  sheet.getCell("F4").value = "Produto";
  sheet.getCell("G4").value = "Estoque atual";
  sheet.getCell("H4").value = "Valor unitário";

  assert.deepEqual(localizarCabecalhoEstoque(sheet), {
    linha: 4,
    produto: 1,
    quantidade: 2,
    custo: 3,
  });
});

test("identifica fórmula compartilhada sem resultado armazenado", () => {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet("CADASTRO_PRODUTOS");
  const cell = sheet.getCell("C50");
  cell.value = { sharedFormula: "C5" };

  assert.deepEqual(valorDaCelula(cell), cell.value);
  assert.equal(celulaPossuiFormulaSemResultado(cell), true);
});

test("lê resultado zero calculado pelo ExcelJS mesmo sem cache no objeto da célula", () => {
  const celula = { value: { formula: "B1" }, result: 0 };

  assert.equal(valorDaCelula(celula), 0);
  assert.equal(celulaPossuiFormulaSemResultado(celula), false);
});

test("extrai OR avulsa usando o estoque atual e registra quantidade faltante", () => {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet("ORDEM DE RETIRADA - CABEDELO");
  sheet.getCell("A2").value = "CONTROLE DE RETIRADAS E ESTOQUE - CABEDELO";
  sheet.addRow([]);
  sheet.getCell("A7").value = "Produto";
  sheet.getCell("B7").value = "Quantidade em estoque";
  sheet.getCell("C7").value = "Valor unitário";
  sheet.getCell("D7").value = "Quantidade da retirada";
  sheet.getCell("F7").value = "Data de retirada";
  sheet.getCell("G7").value = "Estoque após retirada";
  sheet.getCell("A8").value = "PORCA GAIOLA";
  sheet.getCell("B8").value = 17;
  sheet.getCell("C8").value = 1.5;
  sheet.getCell("D8").value = 32;
  sheet.getCell("F8").value = "28/08/2026";
  sheet.getCell("G8").value = -15;
  sheet.getCell("A9").value = "TOTAL GERAL";
  sheet.getCell("D9").value = 32;

  const resultado = extrairOrdensRetiradaAvulsas(workbook, [{
    id: 10,
    nome: "Porca Gaiola",
    tipoControle: "UNIDADE",
    quantidadeDisponivel: 17,
    quantidadeReservada: 0,
    custoMedio: 2,
  }]);

  assert.equal(resultado.avisos.length, 0);
  assert.equal(resultado.itens.length, 1);
  assert.equal(resultado.itens[0].saldo, 17);
  assert.equal(resultado.itens[0].quantidadeSolicitada, 32);
  assert.equal(resultado.itens[0].quantidadeFaltante, 15);
  assert.equal(resultado.abasRetiradas.length, 1);
  assert.equal(resultado.abasRetiradas[0].cidade, "CABEDELO");
  assert.equal(resultado.abasRetiradas[0].itens.length, 1);
  assert.equal(resultado.abasRetiradas[0].itens[0].quantidadeRetirada, 32);
  assert.equal(resultado.abasRetiradas[0].itens[0].saldoFinal, -15);
  assert.equal(resultado.abasRetiradas[0].itens[0].quantidadeFaltante, 15);
  assert.equal(resultado.abasRetiradas[0].itens[0].custoUnitario, 2);
  assert.equal(resultado.abasRetiradas[0].itens[0].dataRetirada, "2026-08-28");
});

test("converte caixa de cabo em bobina de 305 metros sem alterar o valor financeiro", () => {
  const metragem = quantidadePlanilhaParaEstoque("CAIXA DE CABO CAT6A", 2.94);
  const custoPorMetro = custoPlanilhaParaEstoque("CAIXA DE CABO CAT6A", 2196);

  assert.equal(metragem, 896.7);
  assert.equal(custoPorMetro, 7.2);
  assert.equal(Number((metragem * custoPorMetro).toFixed(2)), 6456.24);
});

test("converte a solicitação da OR de caixas de cabo para metragem e calcula a falta", () => {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet("ORDEM DE RETIRADA - CABEDELO");
  sheet.getCell("A2").value = "CONTROLE DE RETIRADAS E ESTOQUE - CABEDELO";
  sheet.getCell("A7").value = "Produto";
  sheet.getCell("B7").value = "Quantidade em estoque";
  sheet.getCell("C7").value = "Valor unitário";
  sheet.getCell("D7").value = "Quantidade da retirada";
  sheet.getCell("G7").value = "Estoque após retirada";
  sheet.getCell("A8").value = "CAIXA DE CABO CAT6A";
  sheet.getCell("B8").value = 2.94;
  sheet.getCell("C8").value = 2196;
  sheet.getCell("D8").value = 3;
  sheet.getCell("G8").value = -0.06;

  const resultado = extrairOrdensRetiradaAvulsas(workbook, [{
    id: 41,
    nome: "CAIXA DE CABO CAT6A",
    tipoControle: "FRACIONADO",
    unidadeMedida: "UNIDADE",
    metragemDisponivel: 2.94,
    metragemReservada: 0,
    custoMedio: 2196,
  }]);
  const item = resultado.abasRetiradas[0].itens[0];

  assert.equal(item.saldoInicial, 896.7);
  assert.equal(item.quantidadeRetirada, 915);
  assert.ok(Math.abs(item.saldoFinal - -18.3) < 0.0001);
  assert.ok(Math.abs(item.quantidadeFaltante - 18.3) < 0.0001);
  assert.equal(item.custoUnitario, 7.2);
});
