-- Expansão aditiva. Nenhuma OR/documento/movimento legado é convertido.
ALTER TABLE ordens_retirada ADD COLUMN novo_fluxo boolean NOT NULL DEFAULT false;
ALTER TABLE comarca_materiais ADD COLUMN quantidade_retirada_v2 numeric(14,3) NOT NULL DEFAULT 0 CHECK (quantidade_retirada_v2 >= 0);
CREATE TABLE retiradas_v2 (
 or_id bigint PRIMARY KEY REFERENCES ordens_retirada(id), revisao bigint NOT NULL DEFAULT 0,
 revisao_conteudo bigint NOT NULL DEFAULT 0,
 local_id bigint NOT NULL REFERENCES locais_estoque(id), destino_id varchar(100) NOT NULL,
 destino text NOT NULL, motivo text NOT NULL, dados text NOT NULL,
 fisico varchar(30) NOT NULL DEFAULT 'RASCUNHO', documental varchar(30) NOT NULL DEFAULT 'NAO_EMITIDO',
 versao_emitida integer NOT NULL DEFAULT 0, entrega_id uuid UNIQUE,
 entregue_em timestamptz, operador_entrega bigint REFERENCES funcionarios(id), prazo_anexacao timestamptz,
 prazo_revisao timestamptz, comprovante_ativo bigint, devolvida boolean NOT NULL DEFAULT false,
 autor bigint NOT NULL REFERENCES funcionarios(id), criado_em timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK (fisico IN ('RASCUNHO','RESERVADA','ENTREGUE','ENTREGUE_PARCIAL','CANCELADA')),
 CHECK (documental IN ('NAO_EMITIDO','EMITIDO','AGUARDANDO_COMPROVANTE','ANEXADO','REJEITADO','CONFERIDO'))
);
CREATE TABLE retirada_v2_itens (
 or_id bigint NOT NULL REFERENCES retiradas_v2(or_id), material_id bigint NOT NULL REFERENCES materiais(id),
 material_item_id bigint REFERENCES comarca_materiais(id), nome text NOT NULL, unidade varchar(30) NOT NULL,
 controle varchar(40) NOT NULL, categoria varchar(50) NOT NULL, solicitado numeric(14,3) NOT NULL CHECK(solicitado>0),
 efetivo numeric(14,3) NOT NULL DEFAULT 0 CHECK(efetivo>=0 AND efetivo<=solicitado),
 devolvido numeric(14,3) NOT NULL DEFAULT 0 CHECK(devolvido>=0 AND devolvido<=efetivo),
 custo numeric(15,4) NOT NULL, PRIMARY KEY(or_id,material_id)
);
CREATE TABLE retirada_v2_reservas (
 or_id bigint NOT NULL, material_id bigint NOT NULL, local_id bigint NOT NULL REFERENCES locais_estoque(id),
 quantidade numeric(14,3) NOT NULL CHECK(quantidade>0), origem_os numeric(14,3) NOT NULL CHECK(origem_os>=0 AND origem_os<=quantidade),
 PRIMARY KEY(or_id,material_id), FOREIGN KEY(or_id,material_id) REFERENCES retirada_v2_itens(or_id,material_id)
);
CREATE INDEX r2_reserva_material ON retirada_v2_reservas(material_id,local_id);
CREATE TABLE retirada_v2_operacoes (
 escopo varchar(100) NOT NULL, chave uuid NOT NULL, hash varchar(64) NOT NULL,
 or_id bigint NOT NULL REFERENCES ordens_retirada(id), resultado text NOT NULL,
 PRIMARY KEY(escopo,chave)
);
CREATE TABLE retirada_v2_arquivos (
 id bigserial PRIMARY KEY, or_id bigint NOT NULL REFERENCES retiradas_v2(or_id), versao integer NOT NULL,
 tipo varchar(20) NOT NULL CHECK(tipo IN ('EMISSAO','COMPROVANTE','TERMO','DEVOLUCAO')),
 caminho varchar(100) NOT NULL UNIQUE, hash varchar(64) NOT NULL, mime varchar(50) NOT NULL,
 nome text NOT NULL, autor bigint NOT NULL REFERENCES funcionarios(id), criado_em timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX r2_emissao_versao ON retirada_v2_arquivos(or_id,versao) WHERE tipo='EMISSAO';
CREATE TABLE retirada_v2_emissoes (
 or_id bigint NOT NULL REFERENCES retiradas_v2(or_id), versao integer NOT NULL,
 snapshot text NOT NULL, autor bigint NOT NULL REFERENCES funcionarios(id), criado_em timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(or_id,versao)
);
CREATE TABLE retirada_v2_eventos (
 id bigserial PRIMARY KEY, or_id bigint NOT NULL REFERENCES retiradas_v2(or_id), operacao varchar(60) NOT NULL,
 autor bigint NOT NULL REFERENCES funcionarios(id), ocorrido_em timestamptz NOT NULL DEFAULT clock_timestamp(), dados text NOT NULL
);
CREATE TABLE retirada_v2_autorizacoes (
 id bigserial PRIMARY KEY, or_id bigint NOT NULL REFERENCES retiradas_v2(or_id), revisao bigint NOT NULL,
 tipo varchar(30) NOT NULL CHECK(tipo IN ('AVULSA','EXCECAO_DOCUMENTAL')), motivo text NOT NULL,
 pendencias text NOT NULL, autor bigint NOT NULL REFERENCES funcionarios(id), criado_em timestamptz NOT NULL DEFAULT clock_timestamp(),
 consumida boolean NOT NULL DEFAULT false
);
CREATE INDEX r2_fila ON retiradas_v2(documental,entregue_em);
CREATE FUNCTION r2_evento_imutavel() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Evento de retirada imutável'; END $$;
CREATE TRIGGER r2_eventos_append_only BEFORE UPDATE OR DELETE ON retirada_v2_eventos FOR EACH ROW EXECUTE FUNCTION r2_evento_imutavel();
-- Guards no commit: nem escritor legado pode deixar reserva nova sem cobertura.
CREATE FUNCTION r2_validar_cobertura() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE mid bigint; p numeric; r numeric; lp numeric; lr numeric;
BEGIN
 IF TG_TABLE_NAME='materiais' THEN
  IF (NEW.tipo_controle IS DISTINCT FROM OLD.tipo_controle OR NEW.unidade_medida IS DISTINCT FROM OLD.unidade_medida)
  AND EXISTS(SELECT 1 FROM retirada_v2_itens i JOIN retiradas_v2 v ON v.or_id=i.or_id WHERE i.material_id=NEW.id AND (v.fisico='RESERVADA' OR (v.entrega_id IS NOT NULL AND v.devolvida=false)))
  THEN RAISE EXCEPTION 'Conversão de unidade/tipo com obrigação física V2 aberta'; END IF;
  mid:=COALESCE(NEW.id,OLD.id); ELSE mid:=COALESCE(NEW.material_id,OLD.material_id); END IF;
 IF NOT EXISTS(SELECT 1 FROM retirada_v2_itens WHERE material_id=mid) THEN RETURN NULL; END IF;
 SELECT CASE WHEN tipo_controle IN ('FRACIONADO','METRAGEM','BOBINA','ROLO') THEN metragem_disponivel ELSE quantidade_disponivel END,
 CASE WHEN tipo_controle IN ('FRACIONADO','METRAGEM','BOBINA','ROLO') THEN metragem_reservada ELSE quantidade_reservada END INTO p,r FROM materiais WHERE id=mid;
 SELECT coalesce(sum(CASE WHEN m.tipo_controle IN ('FRACIONADO','METRAGEM','BOBINA','ROLO') THEN s.metragem_disponivel ELSE s.quantidade_disponivel END),0),
 coalesce(sum(CASE WHEN m.tipo_controle IN ('FRACIONADO','METRAGEM','BOBINA','ROLO') THEN s.metragem_reservada ELSE s.quantidade_reservada END),0)
 INTO lp,lr FROM saldos_materiais_locais s JOIN materiais m ON m.id=s.material_id WHERE m.id=mid;
 IF p<0 OR r<0 OR p<r OR p IS NULL OR r IS NULL OR p<>lp OR r<>lr
 OR (SELECT coalesce(sum(quantidade),0) FROM retirada_v2_reservas WHERE material_id=mid)>r
 OR EXISTS(SELECT 1 FROM retirada_v2_reservas v JOIN materiais m ON m.id=v.material_id LEFT JOIN saldos_materiais_locais s ON s.material_id=v.material_id AND s.local_estoque_id=v.local_id
 WHERE v.material_id=mid GROUP BY v.local_id,s.id,m.id HAVING s.id IS NULL
 OR sum(v.quantidade)>CASE WHEN m.tipo_controle IN ('FRACIONADO','METRAGEM','BOBINA','ROLO') THEN s.metragem_reservada ELSE s.quantidade_reservada END
 OR sum(v.quantidade)>CASE WHEN m.tipo_controle IN ('FRACIONADO','METRAGEM','BOBINA','ROLO') THEN s.metragem_disponivel ELSE s.quantidade_disponivel END)
 THEN RAISE EXCEPTION 'Saldo/reserva global e por depósito inconsistente para material %',mid; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER r2_cobertura_material AFTER UPDATE ON materiais DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION r2_validar_cobertura();
CREATE CONSTRAINT TRIGGER r2_cobertura_local AFTER INSERT OR UPDATE OR DELETE ON saldos_materiais_locais DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION r2_validar_cobertura();
CREATE CONSTRAINT TRIGGER r2_cobertura_reserva AFTER INSERT OR UPDATE OR DELETE ON retirada_v2_reservas DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION r2_validar_cobertura();

CREATE TRIGGER r2_emissao_append_only BEFORE UPDATE OR DELETE ON retirada_v2_emissoes FOR EACH ROW EXECUTE FUNCTION r2_evento_imutavel();
CREATE TRIGGER r2_arquivos_append_only BEFORE UPDATE OR DELETE ON retirada_v2_arquivos FOR EACH ROW EXECUTE FUNCTION r2_evento_imutavel();

CREATE INDEX r2_reserva_material_local ON retirada_v2_reservas(material_id,local_id);
CREATE INDEX r2_itens_material ON retirada_v2_itens(material_id);
CREATE INDEX r2_itens_previsto ON retirada_v2_itens(material_item_id);
CREATE INDEX r2_eventos_ordem ON retirada_v2_eventos(or_id,id);
CREATE INDEX r2_arquivos_ordem ON retirada_v2_arquivos(or_id,id);
CREATE INDEX r2_autorizacao_ordem ON retirada_v2_autorizacoes(or_id,revisao,tipo);
