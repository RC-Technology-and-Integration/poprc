import test from "node:test";
import assert from "node:assert/strict";
import { suppliedRevision } from "../buildRevision.mjs";

test("revisão fornecida preserva o SHA completo", () => {
  const fixture = "a".repeat(40);
  assert.equal(suppliedRevision(` ${fixture} `), fixture);
});
test("não aceita o antigo SHA curto nem placeholders operacionais", () => {
  for (const value of ["fe7828b", "dokploy", "HEAD", "z".repeat(40)]) {
    assert.throws(() => suppliedRevision(value));
  }
  assert.equal(suppliedRevision(undefined), "");
});
