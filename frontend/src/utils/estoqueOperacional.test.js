import test from "node:test";
import assert from "node:assert/strict";
import {
  calcularSimulacaoRetirada,
  consolidarRetiradasPorObra,
} from "./estoqueOperacional.js";

const material = {
  id: 1,
  nome: "Cabo CAT6",
  tipoControle: "UNIDADE",
  quantidadeDisponivel: 10,
  quantidadeReservada: 2,
  custoMedio: 5,
};

test("não duplica uma retirada presente na OR e no histórico de importação", () => {
  const comarca = { id: 10, nomeComarca: "Cuité" };
  const resultado = consolidarRetiradasPorObra({
    comarcas: [comarca],
    materiais: [material],
    ordensRetirada: [{
      id: 20,
      numeroOr: "0001 - OS 01 - OR 01",
      comarca,
      itens: [{ material, nomeMaterial: material.nome, quantidadeRetirada: 4, quantidadeDevolvida: 1 }],
    }],
    retiradasImportadas: [{
      ordemRetiradaId: 20,
      numeroOr: "0001 - OS 01 - OR 01",
      comarcaId: 10,
      materialId: 1,
      material: material.nome,
      aba: "ORDEM DE RETIRADA - CUITÉ",
      quantidadeRetirada: 4,
      quantidadeFaltante: 2,
      custoUnitario: 5,
    }],
  });

  assert.equal(resultado[0].ordens.length, 1);
  assert.equal(resultado[0].totalRetirado, 4);
  assert.equal(resultado[0].totalDevolvido, 1);
  assert.equal(resultado[0].totalFaltante, 2);
  assert.deepEqual(resultado[0].ordens[0].abasOrigem, ["ORDEM DE RETIRADA - CUITÉ"]);
});

test("simula saldo, falta e valor sem alterar o material", () => {
  const resultado = calcularSimulacaoRetirada([material], [{ materialId: 1, quantidade: 12 }]);

  assert.equal(resultado.itens[0].saldoAtual, 8);
  assert.equal(resultado.itens[0].saldoProjetado, -4);
  assert.equal(resultado.quantidadeFaltante, 4);
  assert.equal(resultado.valorSolicitado, 60);
  assert.equal(resultado.possuiFalta, true);
  assert.equal(material.quantidadeDisponivel, 10);
});

test("simulacao soma os valores arredondados que apresenta por item", () => {
  const materiais = [
    { id: 1, custoMedio: 0.005, quantidadeDisponivel: 1 },
    { id: 2, custoMedio: 0.005, quantidadeDisponivel: 1 },
  ];
  const resultado = calcularSimulacaoRetirada(materiais, [
    { materialId: 1, quantidade: 1 },
    { materialId: 2, quantidade: 1 },
  ]);

  assert.deepEqual(resultado.itens.map((item) => item.valorSolicitado), [0.01, 0.01]);
  assert.equal(resultado.valorSolicitado, 0.02);
});

test("soma no resumo os centavos apresentados em cada material", () => {
  const comarca = { id: 10, nomeComarca: "Cuite" };
  const materiais = [
    { id: 1, nome: "Material A", custoMedio: 0.005 },
    { id: 2, nome: "Material B", custoMedio: 0.005 },
  ];
  const [obra] = consolidarRetiradasPorObra({
    comarcas: [comarca],
    materiais,
    ordensRetirada: [{
      id: 20,
      numeroOr: "OR 01",
      comarca,
      itens: materiais.map((item) => ({
        material: item,
        nomeMaterial: item.nome,
        quantidadeRetirada: 1,
        quantidadeDevolvida: 0,
      })),
    }],
  });

  assert.deepEqual(obra.itens.map((item) => item.valorAtribuido), [0.01, 0.01]);
  assert.equal(obra.valorLiquido, 0.02);
  assert.equal(obra.ordens[0].valorLiquido, 0.02);
});

test("preserva a soma dos valores das ORs ao consolidar o mesmo material", () => {
  const comarca = { id: 10, nomeComarca: "Cuite" };
  const materialCentavos = { id: 1, nome: "Material A", custoMedio: 0.005 };
  const [obra] = consolidarRetiradasPorObra({
    comarcas: [comarca],
    materiais: [materialCentavos],
    ordensRetirada: [20, 21].map((id) => ({
      id,
      numeroOr: `OR ${id}`,
      comarca,
      itens: [{
        material: materialCentavos,
        nomeMaterial: materialCentavos.nome,
        quantidadeRetirada: 1,
        quantidadeDevolvida: 0,
      }],
    })),
  });

  assert.equal(obra.itens[0].retirada, 2);
  assert.equal(obra.itens[0].valorAtribuido, 0.02);
  assert.equal(obra.valorLiquido, 0.02);
});

test("usa o custo registrado na retirada importada, nao o custo atual", () => {
  const comarca = { id: 10, nomeComarca: "Cuite" };
  const materialAtual = { id: 1, nome: "Material A", custoMedio: 10 };
  const [obra] = consolidarRetiradasPorObra({
    comarcas: [comarca],
    materiais: [materialAtual],
    ordensRetirada: [{
      id: 20,
      numeroOr: "OR 01",
      comarca,
      itens: [{
        material: materialAtual,
        nomeMaterial: materialAtual.nome,
        quantidadeRetirada: 1,
        quantidadeDevolvida: 0,
      }],
    }],
    retiradasImportadas: [{
      ordemRetiradaId: 20,
      comarcaId: 10,
      materialId: 1,
      material: materialAtual.nome,
      quantidadeRetirada: 1,
      custoUnitario: 5,
    }],
  });

  assert.equal(obra.ordens[0].itens[0].custoUnitario, 5);
  assert.equal(obra.ordens[0].itens[0].valorAtribuido, 5);
  assert.equal(obra.itens[0].valorAtribuido, 5);
  assert.equal(obra.valorLiquido, 5);
});

test("mantem o custo por metro no resumo de cabo legado", () => {
  const comarca = { id: 10, nomeComarca: "Cuite" };
  const cabo = {
    id: 1,
    nome: "CAIXA DE CABO CAT6A",
    tipoControle: "FRACIONADO",
    custoMedio: 305,
  };
  const [obra] = consolidarRetiradasPorObra({
    comarcas: [comarca],
    materiais: [cabo],
    ordensRetirada: [{
      id: 20,
      numeroOr: "OR 01",
      comarca,
      itens: [{ material: cabo, nomeMaterial: cabo.nome, quantidadeRetirada: 10 }],
    }],
  });

  assert.equal(obra.itens[0].custoUnitario, 1);
  assert.equal(obra.itens[0].valorAtribuido, 10);
  assert.equal(obra.valorLiquido, 10);
});
