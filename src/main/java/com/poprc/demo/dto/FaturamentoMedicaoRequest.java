package com.poprc.demo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.poprc.demo.model.Faturamento;
import java.math.BigDecimal;

/** Entrada da medição: referências por ID e somente os valores editáveis. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FaturamentoMedicaoRequest(
        Referencia contrato,
        Referencia projeto,
        Referencia ordemServico,
        BigDecimal valorMedicao,
        String servicosExecutados) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Referencia(Long id) {}

    public Long contratoId() {
        return idObrigatorio(contrato, "O contrato é obrigatório.");
    }

    public Long projetoId() {
        return idObrigatorio(projeto, "O projeto é obrigatório.");
    }

    public Long ordemServicoId() {
        return idObrigatorio(ordemServico, "A Ordem de Serviço concluída é obrigatória.");
    }

    /** Nunca transporta identidade, situação, dados fiscais ou atributos das referências. */
    public Faturamento dadosEditaveis() {
        var dados = new Faturamento();
        dados.setValorMedicao(valorMedicao);
        dados.setServicosExecutados(servicosExecutados);
        return dados;
    }

    private static Long idObrigatorio(Referencia referencia, String mensagem) {
        if (referencia == null || referencia.id() == null) {
            throw new IllegalArgumentException(mensagem);
        }
        return referencia.id();
    }
}
