package com.poprc.demo.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Contratos exclusivos de novas ORs; não altera os DTOs do regime de assinaturas legado. */
public final class RetiradaV2Request {
    private RetiradaV2Request() {}
    public record Item(Long materialId, Long materialItemId, BigDecimal quantidade) {}
    public record Rascunho(UUID chave, long revisao, Long osId, Long localId, String destinoId,
            String destino, String motivo, String entregaPor, String retirante, String observacoes, List<Item> itens) {}
    public record Efetivo(Long materialId, BigDecimal quantidade) {}
    public record Acao(UUID chave, long revisao, Integer versao, String motivo, List<Efetivo> itens,
            Long arquivoId, String decisao, Boolean autorrevisao, String entregaPor, String retirante,
            String assinaturaRecebimentoBase64) {
        public Acao { autorrevisao = Boolean.TRUE.equals(autorrevisao); }
    }
}
