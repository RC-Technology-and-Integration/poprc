import assert from "node:assert/strict";
import test from "node:test";
import { formatarDataCivil } from "./datas.js";

test("formata data civil sem recuar um dia no fuso do navegador", () => {
  assert.equal(formatarDataCivil("2026-09-24"), "24/09/2026");
});

test("nao apresenta data civil ausente ou invalida", () => {
  assert.equal(formatarDataCivil(null), "--");
  assert.equal(formatarDataCivil("2026-02-30"), "--");
});
