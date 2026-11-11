CREATE TABLE estoque_intencoes (
    funcionario_id BIGINT NOT NULL,
    chave UUID NOT NULL,
    operacao TEXT NOT NULL,
    pedido JSONB NOT NULL,
    resultado TEXT,
    PRIMARY KEY (funcionario_id, chave)
);
