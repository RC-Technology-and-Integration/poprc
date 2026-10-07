package com.poprc.demo.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class SimulacaoDocumentoServiceTest {
    @Test void exigeFlagEHomologacao() {
        for (var config : new SimulacaoDocumentoService[] {
                new SimulacaoDocumentoService(false, "homologacao"),
                new SimulacaoDocumentoService(true, "producao"),
                new SimulacaoDocumentoService(true, "nao-definido") }) {
            assertFalse(config.isHabilitada());
            assertThrows(IllegalStateException.class, () -> config.exigirHabilitada(true));
            assertDoesNotThrow(() -> config.exigirHabilitada(false));
        }
        assertDoesNotThrow(() -> new SimulacaoDocumentoService(true, "homologacao").exigirHabilitada(true));
    }
    @Test void dadosFicticiosNaoReutilizamIdentidadesEConservamTextoDoCenario() {
        var node = new ObjectMapper().readTree(SimulacaoDocumentoService.conteudoFicticio(
                "{\"empresa\":\"Instituição real\",\"tecnicoResponsavel\":\"Pessoa real\",\"descricaoServicos\":\"Cenário TESTE\"}"));
        assertEquals("Cenário TESTE", node.get("descricaoServicos").asText());
        assertTrue(node.get("empresa").asText().contains("FICTÍCIA"));
        assertTrue(node.get("tecnicoResponsavel").asText().contains("TESTE FICTÍCIO"));
    }
}
