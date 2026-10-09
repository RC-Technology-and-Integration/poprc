package com.poprc.demo.controller;

import com.poprc.demo.model.*;
import com.poprc.demo.repository.*;
import com.poprc.demo.service.*;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FaturamentoMedicaoEntradaTest {
    private FaturamentoRepository repo;
    private ContratoRepository contratos;
    private ProjetoRepository projetos;
    private OrdemServicoRepository ordens;
    private Projeto projeto;
    private OrdemServico os;
    private Faturamento existente;
    private MockMvc mvc;
    private static final String PAYLOAD = """
            {"contrato":{"id":2},"projeto":{"id":12},"ordemServico":{"id":11},
             "valorMedicao":1,"servicosExecutados":" TESTE — SEM VALIDADE OPERACIONAL "}
            """;

    @BeforeEach void preparar() {
        repo = mock(FaturamentoRepository.class);
        contratos = mock(ContratoRepository.class);
        projetos = mock(ProjetoRepository.class);
        ordens = mock(OrdemServicoRepository.class);
        var contrato = new Contrato(); contrato.setId(2L);
        projeto = new Projeto(); projeto.setId(12L); projeto.setContrato(contrato);
        os = new OrdemServico(); os.setId(11L); os.setProjeto(projeto);
        os.setStatus(StatusOS.CONCLUIDA); os.setSimulacao(true);
        existente = new Faturamento(); existente.setId(7L);
        existente.setSituacao(SituacaoFaturamento.A_FATURAR);
        existente.setValorMedicao(BigDecimal.TEN);
        when(contratos.findById(2L)).thenReturn(Optional.of(contrato));
        when(projetos.findById(12L)).thenReturn(Optional.of(projeto));
        when(ordens.findById(11L)).thenReturn(Optional.of(os));
        when(repo.findById(7L)).thenReturn(Optional.of(existente));
        when(repo.save(any())).thenAnswer(c -> c.getArgument(0));
        var service = new FaturamentoService(repo, contratos, projetos,
                mock(ComarcaRepository.class), ordens, mock(FluxoOrdemServicoService.class));
        mvc = MockMvcBuilders.standaloneSetup(new FaturamentoController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build())).build();
    }

    @Test void criaEEditaComReferenciasSomentePorId() throws Exception {
        mvc.perform(post("/api/faturamentos").contentType("application/json").content(PAYLOAD))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ordemServico.simulacao").value(true));
        mvc.perform(put("/api/faturamentos/7").contentType("application/json").content(PAYLOAD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ordemServico.simulacao").value(true));
        assertSame(os, existente.getOrdemServico());
        assertEquals(BigDecimal.ONE, existente.getValorMedicao());
        assertEquals("TESTE — SEM VALIDADE OPERACIONAL", existente.getServicosExecutados());
    }

    @Test void rejeicoesNaoGravamNemAlteramEdicao() throws Exception {
        for (boolean editar : new boolean[]{false, true}) {
            rejeitar(PAYLOAD.replace("\"contrato\":{\"id\":2}", "\"contrato\":null"), editar);
            rejeitar(PAYLOAD.replace("\"projeto\":{\"id\":12}", "\"projeto\":{}"), editar);
            rejeitar(PAYLOAD.replace("\"ordemServico\":{\"id\":11}", "\"ordemServico\":{}"), editar);
            rejeitar(PAYLOAD.replace("\"id\":2", "\"id\":999"), editar);
            rejeitar(PAYLOAD.replace("\"id\":12", "\"id\":999"), editar);
            rejeitar(PAYLOAD.replace("\"id\":11", "\"id\":999"), editar);
            rejeitar(PAYLOAD.replace("\"valorMedicao\":1", "\"valorMedicao\":0"), editar);
            rejeitar(PAYLOAD.replace("\"valorMedicao\":1", "\"valorMedicao\":-1"), editar);
            rejeitar(PAYLOAD.replace("\"valorMedicao\":1", "\"valorMedicao\":null"), editar);
            projeto.setArquivado(true); rejeitar(PAYLOAD, editar); projeto.setArquivado(false);
            os.setArquivado(true); rejeitar(PAYLOAD, editar); os.setArquivado(false);
            os.setStatus(StatusOS.FATURADA); rejeitar(PAYLOAD, editar); os.setStatus(StatusOS.CONCLUIDA);
            var contrato = projeto.getContrato(); projeto.setContrato(new Contrato());
            rejeitar(PAYLOAD, editar); projeto.setContrato(contrato);
            os.setProjeto(new Projeto()); rejeitar(PAYLOAD, editar); os.setProjeto(projeto);
        }
        existente.setSituacao(SituacaoFaturamento.FATURADO); rejeitar(PAYLOAD, true);
        mvc.perform(put("/api/faturamentos/999").contentType("application/json").content(PAYLOAD))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test void camposControladosPeloServidorEFlagDoClienteNaoSaoCopiados() throws Exception {
        for (boolean simulacaoPersistida : new boolean[]{false, true}) {
            os.setSimulacao(simulacaoPersistida);
            for (String flag : new String[]{"true", "false", "null"}) {
                String ataque = PAYLOAD.replace("\"ordemServico\":{\"id\":11}",
                        "\"ordemServico\":{\"id\":11,\"simulacao\":" + flag
                                + ",\"status\":\"FATURADA\",\"arquivado\":true}")
                        .replace("\"valorMedicao\"", "\"id\":99,\"situacao\":\"PAGO\","
                                + "\"numeroNotaFiscal\":\"FORJADA\",\"impostoTotal\":999,\"valorMedicao\"");
                mvc.perform(post("/api/faturamentos").contentType("application/json").content(ataque))
                        .andExpect(status().isCreated());
                mvc.perform(put("/api/faturamentos/7").contentType("application/json").content(ataque))
                        .andExpect(status().isOk());
                assertSame(os, existente.getOrdemServico());
                assertEquals(simulacaoPersistida, os.isSimulacao());
                assertEquals(StatusOS.CONCLUIDA, os.getStatus());
                assertFalse(os.getArquivado());
                assertEquals(7L, existente.getId());
                assertEquals(SituacaoFaturamento.A_FATURAR, existente.getSituacao());
                assertNull(existente.getNumeroNotaFiscal());
                assertNull(existente.getImpostoTotal());
            }
        }
        var captor = org.mockito.ArgumentCaptor.forClass(Faturamento.class);
        verify(repo, times(12)).save(captor.capture());
        for (var salvo : captor.getAllValues()) {
            assertTrue(salvo.getId() == null || salvo.getId().equals(7L));
            assertEquals(SituacaoFaturamento.A_FATURAR, salvo.getSituacao());
            assertNull(salvo.getNumeroNotaFiscal());
            assertNull(salvo.getImpostoTotal());
            assertSame(os, salvo.getOrdemServico());
        }
    }

    private void rejeitar(String payload, boolean editar) throws Exception {
        mvc.perform((editar ? put("/api/faturamentos/7") : post("/api/faturamentos"))
                .contentType("application/json").content(payload)).andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
        assertEquals(BigDecimal.TEN, existente.getValorMedicao());
        assertNull(existente.getOrdemServico());
    }
}
