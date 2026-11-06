-- Identifica o algoritmo sem apagar/recalcular intenções anteriores; V41 é preservada.
ALTER TABLE retirada_v2_operacoes ADD COLUMN algoritmo varchar(20) NOT NULL DEFAULT 'JSON_V1'
 CHECK (algoritmo IN ('JSON_V1','CANONICO_V2'));
COMMENT ON COLUMN retirada_v2_operacoes.algoritmo IS 'JSON_V1 conserva hash e compara as duas ordens legadas conhecidas de ANEXAR; CANONICO_V2 usa JSON recursivamente ordenado, restrito a comandos OR V2.';
