-- Não presume que qualquer documento anterior era simulação.
ALTER TABLE ordens_servico ADD COLUMN simulacao BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE documentos_internos ADD COLUMN simulacao BOOLEAN NOT NULL DEFAULT FALSE;

-- A classificação é definida na criação e nunca convertida, nem por atualização SQL.
CREATE FUNCTION impedir_conversao_simulacao_documental() RETURNS trigger AS $$
BEGIN
    IF NEW.simulacao IS DISTINCT FROM OLD.simulacao THEN
        RAISE EXCEPTION 'A identificação de simulação documental é imutável';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER ordens_servico_simulacao_imutavel BEFORE UPDATE ON ordens_servico
    FOR EACH ROW EXECUTE FUNCTION impedir_conversao_simulacao_documental();
CREATE TRIGGER documentos_internos_simulacao_imutavel BEFORE UPDATE ON documentos_internos
    FOR EACH ROW EXECUTE FUNCTION impedir_conversao_simulacao_documental();
