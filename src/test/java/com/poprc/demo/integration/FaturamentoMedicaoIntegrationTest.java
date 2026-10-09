package com.poprc.demo.integration;

import com.poprc.demo.model.*;
import com.poprc.demo.repository.*;
import com.poprc.demo.security.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Executar somente em PostgreSQL descartável *_test; transações revertidas ao final. */
@SpringBootTest(properties = {"spring.config.import=", "app.security.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FaturamentoMedicaoIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ContratoRepository contratos;
    @Autowired ProjetoRepository projetos;
    @Autowired OrdemServicoRepository ordens;
    @Autowired FuncionarioRepository funcionarios;
    @Autowired FaturamentoRepository faturamentos;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    private Contrato contrato;
    private Projeto projeto;
    private OrdemServico os;
    private UsernamePasswordAuthenticationToken auth;
    private MockHttpSession sessao;

    @BeforeEach void preparar() {
        var admin = new Funcionario(); admin.setNome("TESTE ADMIN");
        admin.setEmail("teste-" + UUID.randomUUID() + "@example.invalid");
        admin.setPerfilAcesso(PerfilAcesso.ADMIN); admin = funcionarios.save(admin);
        var principal = UsuarioAutenticado.de(admin);
        auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        sessao = new MockHttpSession();
        sessao.setAttribute(SessaoAutenticacaoService.REAUTENTICADO_EM, Instant.now());
        contrato = new Contrato(); contrato.setContrato("TESTE-" + UUID.randomUUID());
        contrato = contratos.save(contrato);
        projeto = new Projeto(); projeto.setContrato(contrato); projeto = projetos.save(projeto);
        os = criarOs(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void criaEEditaPorIdComMapperRealEPersistencia(boolean simulacaoPersistida) throws Exception {
        if (!simulacaoPersistida) os = criarOs(false);
        long antes = faturamentos.count();
        String resposta = mvc.perform(post("/api/faturamentos").with(authentication(auth)).with(csrf())
                .session(sessao).contentType("application/json").content(payload("1")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.situacao").value("A_FATURAR"))
                .andReturn().getResponse().getContentAsString();
        Long id = mapper.readTree(resposta).get("id").longValue();
        em.flush(); em.clear();
        assertEquals(antes + 1, faturamentos.count());
        assertEquals(simulacaoPersistida, faturamentos.findById(id).orElseThrow().getOrdemServico().isSimulacao());
        mvc.perform(put("/api/faturamentos/" + id).with(authentication(auth)).with(csrf()).session(sessao)
                .contentType("application/json").content(payload("2"))).andExpect(status().isOk());
        em.flush(); em.clear();
        // Campos extras são descartados; nunca são copiados para a entidade persistida.
        String ataque = payload("2").replace("\"ordemServico\":{\"id\":" + os.getId() + "}",
                "\"ordemServico\":{\"id\":" + os.getId() + ",\"simulacao\":" + !simulacaoPersistida
                        + ",\"status\":\"FATURADA\",\"arquivado\":true}")
                .replace("\"valorMedicao\"", "\"id\":999999,\"situacao\":\"PAGO\",\"numeroNotaFiscal\":\"FORJADA\","
                        + "\"dataEmissao\":\"2030-01-01\",\"dataVencimento\":\"2030-01-02\","
                        + "\"dataPagamento\":\"2030-01-02\",\"competenciaFiscal\":\"2030-02-20\","
                        + "\"aliquotaImpostoRetido\":1,\"aliquotaImpostoPagar\":1,"
                        + "\"impostoRetido\":99,\"impostoPagar\":99,\"impostoTotal\":198,\"valorMedicao\"");
        mvc.perform(put("/api/faturamentos/" + id).with(authentication(auth)).with(csrf()).session(sessao)
                .contentType("application/json").content(ataque)).andExpect(status().isOk());
        em.flush(); em.clear();
        var salvo = faturamentos.findById(id).orElseThrow();
        assertEquals(0, new BigDecimal("2").compareTo(salvo.getValorMedicao()));
        assertEquals(SituacaoFaturamento.A_FATURAR, salvo.getSituacao());
        assertNull(salvo.getNumeroNotaFiscal());
        assertEquals(simulacaoPersistida, salvo.getOrdemServico().isSimulacao());
        assertEquals(StatusOS.CONCLUIDA, salvo.getOrdemServico().getStatus());
        assertFalse(salvo.getOrdemServico().getArquivado());
        assertCamposFiscaisNulos(salvo);
        String outraResposta = mvc.perform(post("/api/faturamentos").with(authentication(auth)).with(csrf())
                .session(sessao).contentType("application/json").content(ataque))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long outroId = mapper.readTree(outraResposta).get("id").longValue();
        assertNotEquals(999999L, outroId);
        em.flush(); em.clear();
        var outro = faturamentos.findById(outroId).orElseThrow();
        assertEquals(SituacaoFaturamento.A_FATURAR, outro.getSituacao());
        assertEquals(simulacaoPersistida, outro.getOrdemServico().isSimulacao());
        assertCamposFiscaisNulos(outro);
        assertEquals(antes + 2, faturamentos.count());
    }

    @Test void recusasHttpNaoPersistemMedicoes() throws Exception {
        var existente = new Faturamento(); existente.setContrato(contrato); existente.setProjeto(projeto);
        existente.setOrdemServico(os); existente.setValorMedicao(BigDecimal.TEN);
        existente.setSituacao(SituacaoFaturamento.A_FATURAR);
        existente = faturamentos.saveAndFlush(existente);
        Long id = existente.getId();
        long antes = faturamentos.count();
        for (String corpo : new String[]{payload("0"), payload("1").replace("\"id\":" + contrato.getId(), "\"id\":999999"), "{"}) {
            mvc.perform(post("/api/faturamentos").with(authentication(auth)).with(csrf()).session(sessao)
                    .contentType("application/json").content(corpo)).andExpect(status().isBadRequest());
        }
        for (String perfil : new String[]{"TECNICO", "ESTOQUE", "AUDITOR", "SUPERVISOR_TECNICO"}) {
            for (var pedido : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                    post("/api/faturamentos"), put("/api/faturamentos/" + id)}) {
                var snapshot = jdbc.queryForList("SELECT * FROM faturamentos ORDER BY id");
                mvc.perform(pedido.with(user("TESTE").roles(perfil)).with(csrf())
                        .contentType("application/json").content(payload("1"))).andExpect(status().isForbidden());
                em.flush();
                assertEquals(snapshot, jdbc.queryForList("SELECT * FROM faturamentos ORDER BY id"));
            }
        }
        for (boolean editar : new boolean[]{false, true}) {
            String rota = editar ? "/api/faturamentos/" + id : "/api/faturamentos";
            var snapshot = jdbc.queryForList("SELECT * FROM faturamentos ORDER BY id");
            mvc.perform((editar ? put(rota) : post(rota)).with(authentication(auth)).with(csrf())
                    .contentType("application/json").content(payload("1"))).andExpect(status().is(428));
            mvc.perform((editar ? put(rota) : post(rota)).with(authentication(auth)).session(sessao)
                    .contentType("application/json").content(payload("1"))).andExpect(status().isForbidden());
            mvc.perform((editar ? put(rota) : post(rota)).with(csrf())
                    .contentType("application/json").content(payload("1"))).andExpect(status().isUnauthorized());
            em.flush();
            assertEquals(snapshot, jdbc.queryForList("SELECT * FROM faturamentos ORDER BY id"));
        }
        em.flush(); em.clear(); assertEquals(antes, faturamentos.count());
    }

    @Test void referenciasVinculosEEstadosInvalidosNaoGravamNemModificamEdicao() throws Exception {
        var existente = new Faturamento(); existente.setContrato(contrato); existente.setProjeto(projeto);
        existente.setOrdemServico(os); existente.setValorMedicao(BigDecimal.TEN);
        existente.setSituacao(SituacaoFaturamento.A_FATURAR);
        existente = faturamentos.saveAndFlush(existente);
        Long id = existente.getId(); long antes = faturamentos.count();
        for (String corpo : new String[]{
                payload("1").replace("\"contrato\":{\"id\":" + contrato.getId() + "}", "\"contrato\":{\"id\":999999}"),
                payload("1").replace("\"projeto\":{\"id\":" + projeto.getId() + "}", "\"projeto\":{\"id\":999999}"),
                payload("1").replace("\"ordemServico\":{\"id\":" + os.getId() + "}", "\"ordemServico\":{\"id\":999999}"),
                payload("0"), payload("-1"), payload("null")}) {
            recusarCriacaoEEdicao(corpo, id);
        }
        var outroContrato = new Contrato(); outroContrato.setContrato("TESTE-" + UUID.randomUUID());
        outroContrato = contratos.saveAndFlush(outroContrato);
        recusarCriacaoEEdicao(payload("1").replace("\"contrato\":{\"id\":" + contrato.getId() + "}",
                "\"contrato\":{\"id\":" + outroContrato.getId() + "}"), id);
        var outroProjeto = new Projeto(); outroProjeto.setContrato(contrato);
        outroProjeto = projetos.saveAndFlush(outroProjeto);
        recusarCriacaoEEdicao(payload("1").replace("\"projeto\":{\"id\":" + projeto.getId() + "}",
                "\"projeto\":{\"id\":" + outroProjeto.getId() + "}"), id);
        projeto.setArquivado(true); projetos.saveAndFlush(projeto);
        recusarCriacaoEEdicao(payload("1"), id);
        projeto.setArquivado(false); projetos.saveAndFlush(projeto);
        os.setArquivado(true); ordens.saveAndFlush(os); recusarCriacaoEEdicao(payload("1"), id);
        os.setArquivado(false); os.setStatus(StatusOS.FATURADA); ordens.saveAndFlush(os);
        recusarCriacaoEEdicao(payload("1"), id);
        em.flush(); em.clear();
        assertEquals(antes, faturamentos.count());
        var salvo = faturamentos.findById(id).orElseThrow();
        assertEquals(0, BigDecimal.TEN.compareTo(salvo.getValorMedicao()));
        assertEquals(SituacaoFaturamento.A_FATURAR, salvo.getSituacao());
        assertEquals(os.getId(), salvo.getOrdemServico().getId());
        assertEquals(projeto.getId(), salvo.getProjeto().getId());
        assertEquals(contrato.getId(), salvo.getContrato().getId());
        assertNull(salvo.getServicosExecutados());
    }

    private void recusarCriacaoEEdicao(String corpo, Long id) throws Exception {
        for (var pedido : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                post("/api/faturamentos"), put("/api/faturamentos/" + id)}) {
            var antes = jdbc.queryForList("SELECT * FROM faturamentos ORDER BY id");
            mvc.perform(pedido.with(authentication(auth)).with(csrf()).session(sessao)
                    .contentType("application/json").content(corpo)).andExpect(status().isBadRequest());
            em.flush();
            assertEquals(antes, jdbc.queryForList("SELECT * FROM faturamentos ORDER BY id"));
        }
    }

    @Test void medicaoJaFaturadaNaoPodeSerEditada() throws Exception {
        var existente = new Faturamento(); existente.setContrato(contrato); existente.setProjeto(projeto);
        existente.setOrdemServico(os); existente.setValorMedicao(BigDecimal.TEN);
        existente.setSituacao(SituacaoFaturamento.FATURADO);
        existente = faturamentos.saveAndFlush(existente);
        var antes = jdbc.queryForList("SELECT * FROM faturamentos ORDER BY id");
        mvc.perform(put("/api/faturamentos/" + existente.getId()).with(authentication(auth)).with(csrf())
                .session(sessao).contentType("application/json").content(payload("1")))
                .andExpect(status().isBadRequest());
        em.flush(); em.clear();
        assertEquals(antes, jdbc.queryForList("SELECT * FROM faturamentos ORDER BY id"));
    }

    private OrdemServico criarOs(boolean simulacao) {
        var nova = new OrdemServico(); nova.setNumeroOs("TESTE-" + UUID.randomUUID());
        nova.setContrato(contrato); nova.setProjeto(projeto); nova.setSimulacao(simulacao);
        nova.setStatus(StatusOS.CONCLUIDA);
        return ordens.saveAndFlush(nova);
    }

    private void assertCamposFiscaisNulos(Faturamento faturamento) {
        assertNull(faturamento.getNumeroNotaFiscal());
        assertNull(faturamento.getDataEmissao());
        assertNull(faturamento.getDataVencimento());
        assertNull(faturamento.getDataPagamento());
        assertNull(faturamento.getCompetenciaFiscal());
        assertNull(faturamento.getAliquotaImpostoRetido());
        assertNull(faturamento.getAliquotaImpostoPagar());
        assertNull(faturamento.getImpostoRetido());
        assertNull(faturamento.getImpostoPagar());
        assertNull(faturamento.getImpostoTotal());
    }

    private String payload(String valor) {
        return "{\"contrato\":{\"id\":" + contrato.getId() + "},\"projeto\":{\"id\":" + projeto.getId()
                + "},\"ordemServico\":{\"id\":" + os.getId() + "},\"valorMedicao\":" + valor
                + ",\"servicosExecutados\":\"TESTE — SEM VALIDADE OPERACIONAL\"}";
    }
}
