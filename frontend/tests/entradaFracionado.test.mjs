import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import React from 'react';
import { salvarIntencao, prefixoIntencoes } from '../src/utils/intencoesEstoque.js';
import { transformWithOxc } from 'vite';

// Exercise the actual handler and input JSX, without a server, database or copied payload builder.
const source = readFileSync(new URL('../src/pages/PainelEstoque.jsx', import.meta.url), 'utf8');
const start = source.indexOf('  const executarIntencaoEstoque = async');
const end = source.indexOf('\n  const abrirModalOr', start);
assert.ok(start >= 0 && end > start);
const handler = source.slice(start, end);
const field = [...source.matchAll(/<input\b[\s\S]*?\/>/g)]
  .map(([jsx]) => jsx).find(jsx => jsx.includes('name="quantidade"'));
assert.ok(field);
const labelStart = source.lastIndexOf('<label', source.indexOf(field));
const label = source.slice(labelStart, source.indexOf('</label>', labelStart) + 8);

for (const [tipoControle, unidadeMedida, value, decimal] of [
  ['FRACIONADO', 'UNIDADE', '0.125', true],
  ['FRACIONADO', 'PACOTE', '0.125', true],
  ['METRAGEM', 'METRO', '0.125', true],
  ['UNIDADE', 'UNIDADE', '2', false],
  ['PECA_COM_COMPRIMENTO', 'PECA', '2', false],
]) {
  test(`entrada ${tipoControle}/${unidadeMedida}: payload e campo`, async () => {
    const calls = [];
    const context = {
      React,
      materialEntradaSelecionado: { tipoControle, unidadeMedida },
      formData: { materialId: '1', quantidade: value, custoUnitarioEntrada: '1.2345', funcionarioId: '2', localEstoqueId: '3' },
      handleInputChange() {},
      unidadeMaterial: material => ({ UNIDADE: 'un', PACOTE: 'pacote', METRO: 'm', PECA: 'peça' })[material.unidadeMedida],
      api: { get: async () => ({ data: { funcionarioId: 2, perfil: "ESTOQUE" } }), request: async config => { calls.push(config); return { status: 201, data: { id: 1 } }; } },
      escopoIntencoes: "teste", ambienteEstoque: "teste", funcionarioEstoque: 2,
      salvarIntencao, prefixoIntencoes, atualizarIntencoes() {},
      revisaoSessao: 1, revisaoAtualSessao: () => 1, exigirSessaoAtual() {},
      localStorage: {
        setItem(key, value) { this[key] = value; },
        getItem(key) { return this[key]; },
        removeItem(key) { delete this[key]; },
      },
      setSuccessMessage() {}, setTimeout() {}, handleCloseModal() {}, fetchData() {},
      intencaoEstoque: { current: null },
      crypto: globalThis.crypto,
      setEnviandoOperacaoEstoque() {},
      setErroOperacaoEstoque(error) { if (error) throw new Error(error); },
      getApiErrorMessage: error => error.message,
      console,
    };
    vm.createContext(context);
    await vm.runInContext(`${handler}\nhandleSubmitEntrada({preventDefault(){}})`, context);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/estoque/entrada');
    assert.equal(calls[0].method, 'post');
    assert.match(calls[0].headers['Idempotency-Key'], /^[0-9a-f-]{36}$/);
    const payload = JSON.parse(calls[0].data);
    assert.equal(payload.quantidade, decimal ? null : 2);
    assert.equal(payload.metragem, decimal ? 0.125 : null);
    assert.equal(payload.custoUnitarioEntrada, 1.2345);
    assert.equal(payload.localEstoqueId, 3);
    assert.equal(payload.funcionarioId, 2);

    const input = vm.runInContext((await transformWithOxc(`(${field})`, 'entrada.jsx', { jsx: { runtime: 'classic' } })).code, context);
    assert.equal(Number(input.props.min), decimal ? 0.001 : 1);
    assert.equal(Number(input.props.step), decimal ? 0.001 : 1);
    assert.equal(input.props.required, true);
    const caption = vm.runInContext((await transformWithOxc(`(${label})`, 'label.jsx', { jsx: { runtime: 'classic' } })).code, context).props.children;
    assert.equal(caption, tipoControle === 'METRAGEM' ? 'Metragem'
      : tipoControle === 'FRACIONADO' ? `Quantidade (${context.unidadeMaterial(context.materialEntradaSelecionado)})` : 'Quantidade');
  });
}
