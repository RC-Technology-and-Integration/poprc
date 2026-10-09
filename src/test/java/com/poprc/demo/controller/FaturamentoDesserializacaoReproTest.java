package com.poprc.demo.controller;

import com.poprc.demo.model.Contrato;
import com.poprc.demo.model.Faturamento;
import com.poprc.demo.model.OrdemServico;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.model.SituacaoFaturamento;
import com.poprc.demo.model.StatusOS;
import com.poprc.demo.model.TipoContratante;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.ContratoRepository;
import com.poprc.demo.repository.FaturamentoRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.service.FaturamentoService;
import com.poprc.demo.service.FluxoOrdemServicoService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Reprodução histórica da entrada por entidade. A regressão corrigida está em FaturamentoMedicaoEntradaTest. */
class FaturamentoDesserializacaoReproTest {
    private static final String PAYLOAD = """
            {"contrato":{"id":2},"projeto":{"id":12},"ordemServico":{"id":11},
             "valorMedicao":1,"servicosExecutados":"TESTE — SEM VALIDADE OPERACIONAL. Medição fictícia."}
            """;

    private MockMvc mvc(FaturamentoService service) {
        return MockMvcBuilders.standaloneSetup(new EntradaLegada(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
                .build();
    }

    /** Mantém a reprodução isolada mesmo depois de substituir a entrada do controller real. */
    @org.springframework.web.bind.annotation.RestController
    static class EntradaLegada {
        private final FaturamentoService service;
        EntradaLegada(FaturamentoService service) { this.service = service; }
        @org.springframework.web.bind.annotation.PostMapping("/api/faturamentos")
        org.springframework.http.ResponseEntity<Faturamento> criar(
                @org.springframework.web.bind.annotation.RequestBody Faturamento dados) {
            return org.springframework.http.ResponseEntity.status(201).body(service.registrarMedicao(
                    dados, dados.getContrato().getId(), dados.getProjeto().getId(), dados.getOrdemServico().getId()));
        }
    }

    @Test
    void payloadDoFormularioReproduz400AntesDoServicoPorSimulacaoAusente() throws Exception {
        FaturamentoService service = mock(FaturamentoService.class);
        var result = mvc(service).perform(post("/api/faturamentos")
                .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.erro").value(
                        "Os dados enviados estão incompletos ou possuem formato inválido."))
                .andExpect(jsonPath("$.timestamp").exists()).andReturn();
        var failure = assertInstanceOf(HttpMessageNotReadableException.class, result.getResolvedException());
        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("simulacao"));
        assertTrue(failure.getCause().getMessage().contains("boolean"));
        verifyNoInteractions(service);
        System.out.println("REPRO_HTTP=" + result.getResponse().getStatus()
                + " BODY=" + result.getResponse().getContentAsString());
        System.out.println("REPRO_CAUSA=" + failure.getCause().getClass().getName()
                + ": " + failure.getCause().getMessage());
    }

    @Test
    void flagExplicitaTrueOuFalseApenasNoPayloadLocalPermiteChegarAoController() throws Exception {
        for (boolean flag : new boolean[]{true, false}) {
            FaturamentoService service = mock(FaturamentoService.class);
            when(service.registrarMedicao(any(Faturamento.class), eq(2L), eq(12L), eq(11L)))
                    .thenAnswer(call -> call.getArgument(0));
            String payload = PAYLOAD.replace("\"ordemServico\":{\"id\":11}",
                    "\"ordemServico\":{\"id\":11,\"simulacao\":" + flag + "}");
            mvc(service).perform(post("/api/faturamentos").contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andExpect(status().isCreated());
            verify(service).registrarMedicao(any(Faturamento.class), eq(2L), eq(12L), eq(11L));
        }
    }

    @Test
    void booleanNullExplicitoTambemReproduzRecusa() throws Exception {
        FaturamentoService service = mock(FaturamentoService.class);
        mvc(service).perform(post("/api/faturamentos").contentType(MediaType.APPLICATION_JSON)
                .content(PAYLOAD.replace("\"ordemServico\":{\"id\":11}",
                        "\"ordemServico\":{\"id\":11,\"simulacao\":null}")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void regraAtualAceitaMedicaoPositivaComVinculosConcluidosSemConsultarCustoDeMaterial() {
        var repo = mock(FaturamentoRepository.class);
        var contratos = mock(ContratoRepository.class);
        var projetos = mock(ProjetoRepository.class);
        var ordens = mock(OrdemServicoRepository.class);
        var service = new FaturamentoService(repo, contratos, projetos,
                mock(ComarcaRepository.class), ordens, mock(FluxoOrdemServicoService.class));
        var contrato = new Contrato();
        contrato.setId(2L);
        contrato.setTipoContratante(TipoContratante.SETOR_PUBLICO);
        contrato.setValorGlobal(new BigDecimal("100"));
        var projeto = new Projeto();
        projeto.setId(12L);
        projeto.setContrato(contrato);
        var os = new OrdemServico();
        os.setId(11L);
        os.setProjeto(projeto);
        os.setStatus(StatusOS.CONCLUIDA);
        os.setSimulacao(true);
        when(contratos.findById(2L)).thenReturn(Optional.of(contrato));
        when(projetos.findById(12L)).thenReturn(Optional.of(projeto));
        when(ordens.findById(11L)).thenReturn(Optional.of(os));
        when(repo.save(any(Faturamento.class))).thenAnswer(call -> call.getArgument(0));
        var entrada = new Faturamento();
        entrada.setValorMedicao(BigDecimal.ONE);
        entrada.setServicosExecutados("TESTE — SEM VALIDADE OPERACIONAL");
        var salvo = service.registrarMedicao(entrada, 2L, 12L, 11L);
        assertEquals(SituacaoFaturamento.A_FATURAR, salvo.getSituacao());
        assertEquals(BigDecimal.ONE, salvo.getValorMedicao());
        assertSame(os, salvo.getOrdemServico());
        assertNull(salvo.getNumeroNotaFiscal());
        verify(repo).save(entrada);
        assertEquals(StatusOS.CONCLUIDA, os.getStatus());
    }
}
