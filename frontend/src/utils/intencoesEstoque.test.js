import test from "node:test";
import assert from "node:assert/strict";
import { listarIntencoes, prefixoIntencoes, recuperarIntencao, salvarIntencao } from "./intencoesEstoque.js";

const entrada = { materialId: 7, funcionarioId: 11, localEstoqueId: 2, metragem: 4 };

function armazenamento() {
  return Object.defineProperties({}, {
    getItem: { value(key) { return this[key] ?? null; } },
    setItem: { value(key, value) { this[key] = value; } },
  });
}

test("duas operações do mesmo funcionário conservam corpos próprios e ficam isoladas por ambiente", () => {
  const storage = armazenamento();
  const pedido = { ...entrada };
  const primeira = salvarIntencao(storage, "teste", 11, "post", "/estoque/entrada", pedido);
  pedido.metragem = 9;
  const segunda = salvarIntencao(storage, "teste", 11, "post", "/estoque/entrada", pedido);
  assert.notEqual(primeira.chave, segunda.chave);
  assert.equal(JSON.parse(recuperarIntencao(storage, "teste", 11, primeira.chave).corpo).metragem, 4);
  assert.equal(listarIntencoes(storage, "teste", 11).itens.length, 2);
  assert.deepEqual(listarIntencoes(storage, "outro ambiente", 11).itens, []);
  assert.deepEqual(listarIntencoes(storage, "teste", 12).itens, []);
  assert.throws(() => recuperarIntencao(storage, "teste", 12, primeira.chave));
});

test("descarta da interface registros corrompidos ou que apontem para rotas alheias", () => {
  const storage = armazenamento();
  const item = salvarIntencao(storage, "teste", 11, "post", "/estoque/entrada", entrada);
  const nome = prefixoIntencoes("teste", 11) + item.chave;
  for (const raw of ["{", JSON.stringify({ ...item, url: "/auth/logout" }),
    JSON.stringify({ ...item, corpo: '{"materialId":{"toString":7}}' })]) {
    storage.setItem(nome, raw);
    assert.deepEqual(listarIntencoes(storage, "teste", 11), { itens: [], corrompida: true });
    assert.throws(() => recuperarIntencao(storage, "teste", 11, item.chave));
    assert.equal(storage.getItem(nome), raw);
  }
});

test("falha antes da rede se não for possível confirmar a gravação", () => {
  assert.throws(() => salvarIntencao({ setItem() {}, getItem: () => null },
    "teste", 11, "post", "/estoque/entrada", entrada), /não persistida/);
});

test("valida contrato e recurso em todos os métodos antes de permitir a retomada", () => {
  const auditoria = { motivo: "inventário", lancadoPor: "A", autorizadoPor: "B" };
  const casos = [
    ["post", "/estoque/entrada", entrada,
      [{ materialId: "7" }, { quantidade: 4 }, { metragem: 0 }, { custoUnitarioEntrada: -1 }]],
    ["post", "/estoque/ajustes",
      { ...auditoria, materialId: 7, localEstoqueId: 2, valor: 4, tipo: "AJUSTE_POSITIVO" },
      [{ tipo: "ENTRADA" }, { motivo: ["inventário"] }, { valor: -4 }]],
    ["post", "/estoque/transferencias",
      { ...auditoria, materialId: 7, origemId: 2, destinoId: 3, valor: 4 },
      [{ destinoId: 2 }, { autorizadoPor: null }]],
    ["post", "/estoque/unidades-rastreaveis",
      { materialId: 7, localEstoqueId: 2, codigo: "R02", metragemInicial: 4, observacao: "" },
      [{ codigo: " " }, { observacao: [] }]],
    ["patch", "/estoque/unidades-rastreaveis/7/transferir", { ...auditoria, destinoId: 3 },
      [{ materialId: 7 }, { destinoId: "3" }, { lancadoPor: "" }]],
  ];
  for (const [method, url, body, alteracoes] of casos) {
    const storage = armazenamento();
    const item = salvarIntencao(storage, "teste", 11, method, url, body);
    const nome = prefixoIntencoes("teste", 11) + item.chave;
    const invalidos = [
      { url: [url] }, { method: ["post"] }, { corpo: "{}" }, { corpo: "[]" },
      { versao: "1" }, { funcionario: "11" }, { chave: "inválida" }, { ambiente: "outro" },
      ...alteracoes.map(campos => ({ corpo: JSON.stringify({ ...body, ...campos }) })),
    ];
    for (const campos of invalidos) {
      const raw = JSON.stringify({ ...item, ...campos });
      storage.setItem(nome, raw);
      assert.deepEqual(listarIntencoes(storage, "teste", 11), { itens: [], corrompida: true });
      assert.throws(() => recuperarIntencao(storage, "teste", 11, item.chave));
      assert.equal(storage.getItem(nome), raw);
    }
  }
  assert.throws(() => salvarIntencao(armazenamento(), "teste", 11, "patch",
    "/estoque/unidades-rastreaveis/9007199254740993/transferir", { ...auditoria, destinoId: 3 }));
});

test("confere a precisão decimal pelo literal original e aceita zeros equivalentes", () => {
  const storage = armazenamento();
  const item = salvarIntencao(storage, "teste", 11, "post", "/estoque/entrada", entrada);
  const nome = prefixoIntencoes("teste", 11) + item.chave;
  function ler(campos) {
    const corpo = '{"materialId":7,"funcionarioId":11,"localEstoqueId":2,' + campos + '}';
    storage.setItem(nome, JSON.stringify({ ...item, corpo }));
    return listarIntencoes(storage, "teste", 11);
  }
  for (const campos of ['"metragem":0.001', '"metragem":0.1250000', '"metragem":1e-3',
    '"metragem":99999999999.999,"custoUnitarioEntrada":0.0001', '"quantidade":2147483647.000']) {
    assert.equal(ler(campos).itens.length, 1, campos);
  }
  for (const campos of ['"metragem":0.0001', '"metragem":0.12500000000000000001',
    '"metragem":100000000000', '"metragem":0.125,"custoUnitarioEntrada":0.00001',
    '"quantidade":2147483648', '"quantidade":1.0000000000000000001']) {
    assert.equal(ler(campos).corrompida, true, campos);
  }
});

test("preserva o pedido decimal de entrada, ajuste e transferência no replay", () => {
  const storage = armazenamento();
  const auditoria = { motivo: "inventário", lancadoPor: "A", autorizadoPor: "B" };
  const casos = [
    ["/estoque/entrada", { materialId: 7, funcionarioId: 11, localEstoqueId: 2, metragem: 0.125 }],
    ["/estoque/ajustes", { materialId: 7, localEstoqueId: 2, valor: 0.125, tipo: "AJUSTE_NEGATIVO", ...auditoria }],
    ["/estoque/transferencias", { materialId: 7, origemId: 1, destinoId: 2, valor: 0.125, ...auditoria }],
  ];
  const itens = casos.map(([rota, corpo]) => salvarIntencao(storage, "teste", 11, "post", rota, corpo));
  assert.deepEqual(listarIntencoes(storage, "teste", 11), { itens, corrompida: false });
  for (const item of itens) {
    assert.deepEqual(recuperarIntencao(storage, "teste", 11, item.chave), item);
  }
});
