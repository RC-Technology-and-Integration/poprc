package com.poprc.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poprc.demo.DemoApplication;
import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.Funcionario;
import com.poprc.demo.model.OrdemServico;
import com.poprc.demo.model.PerfilAcesso;
import com.poprc.demo.model.StatusOS;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.enabled=true", "app.security.dev-login-enabled=false",
        "app.security.zoho-enabled=false"
})
@Transactional
class EncerramentoRotasGenericasIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private FuncionarioRepository funcionarios;
    @Autowired private OrdemServicoRepository ordens;
    @Autowired private ComarcaRepository comarcas;

    private Funcionario gestor;
    private OrdemServico os;
    private Comarca obra;

    @BeforeEach
    void preparar() {
        gestor = new Funcionario();
        gestor.setNome("Gestor teste encerramento");
        gestor.setPerfilAcesso(PerfilAcesso.ADMIN);
        gestor = funcionarios.saveAndFlush(gestor);

        os = new OrdemServico();
        os.setNumeroOs("ENC-TEST-" + UUID.randomUUID());
        os.setStatus(StatusOS.AGUARDANDO_DEVOLUCAO);
        os = ordens.saveAndFlush(os);

        obra = new Comarca();
        obra.setNomeComarca("Obra teste encerramento");
        obra.setOrdemServico(os);
        obra.setSituacao("EM_ANDAMENTO");
        obra.setPercentualConcluido(BigDecimal.TEN);
        obra = comarcas.saveAndFlush(obra);
    }

    @ParameterizedTest
    @CsvSource({
            "AGUARDANDO_DEVOLUCAO, AGUARDANDO_AUDITORIA",
            "AGUARDANDO_AUDITORIA, AGUARDANDO_ENCERRAMENTO",
            "AGUARDANDO_ENCERRAMENTO, CONCLUIDA"
    })
    void putStatusNaoSaltaEtapasFormais(String origem, String destino) throws Exception {
        os.setStatus(StatusOS.valueOf(origem));
        ordens.saveAndFlush(os);

        mvc.perform(autenticada(put("/api/ordens-servico/{id}/status", os.getId()))
                .content("{\"status\":\"" + destino + "\"}"))
                .andExpect(status().isBadRequest());

        entityManager.flush();
        entityManager.clear();
        assertThat(ordens.findById(os.getId()).orElseThrow().getStatus()).isEqualTo(StatusOS.valueOf(origem));
        assertThat(comarcas.findById(obra.getId()).orElseThrow().getSituacao()).isEqualTo("EM_ANDAMENTO");
    }

    @Test
    void patchProgressoNaoMarcaConclusaoMasMantemProgressoEditavel() throws Exception {
        mvc.perform(autenticada(patch("/api/comarcas/{id}/progresso", obra.getId()))
                .content("{\"percentualConcluido\":100,\"situacao\":\"CONCLUIDA\"}"))
                .andExpect(status().isBadRequest());
        entityManager.flush();
        entityManager.clear();
        Comarca persistida = comarcas.findById(obra.getId()).orElseThrow();
        assertThat(persistida.getSituacao()).isEqualTo("EM_ANDAMENTO");
        assertThat(persistida.getPercentualConcluido()).isEqualByComparingTo("10");
        assertThat(persistida.getDataConclusao()).isNull();

        mvc.perform(autenticada(patch("/api/comarcas/{id}/progresso", obra.getId()))
                .content("{\"percentualConcluido\":75,\"situacao\":\"EM_EXECUCAO\"}"))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        persistida = comarcas.findById(obra.getId()).orElseThrow();
        assertThat(persistida.getSituacao()).isEqualTo("EM_EXECUCAO");
        assertThat(persistida.getPercentualConcluido()).isEqualByComparingTo("75");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void patchConcorrenteNaoDesfazEncerramentoPersistido() throws Exception {
        Long obraId = obra.getId();
        CountDownLatch encerramentoGravado = new CountDownLatch(1);
        CountDownLatch liberarEncerramento = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var encerramento = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                Comarca travada = comarcas.findByIdForUpdate(obraId).orElseThrow();
                travada.setSituacao("CONCLUIDA");
                travada.setDataConclusao(LocalDateTime.now());
                comarcas.saveAndFlush(travada);
                encerramentoGravado.countDown();
                try {
                    if (!liberarEncerramento.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("O PATCH concorrente não iniciou.");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(encerramentoGravado.await(5, TimeUnit.SECONDS)).isTrue();
            var patch = executor.submit(() -> mvc.perform(
                    autenticada(patch("/api/comarcas/{id}/progresso", obraId))
                            .content("{\"percentualConcluido\":75,\"situacao\":\"EM_EXECUCAO\"}"))
                    .andReturn().getResponse().getStatus());
            assertThrows(TimeoutException.class, () -> patch.get(250, TimeUnit.MILLISECONDS));
            liberarEncerramento.countDown();
            encerramento.get(5, TimeUnit.SECONDS);
            assertThat(patch.get(5, TimeUnit.SECONDS)).isEqualTo(400);
            Comarca persistida = comarcas.findById(obraId).orElseThrow();
            assertThat(persistida.getSituacao()).isEqualTo("CONCLUIDA");
            assertThat(persistida.getDataConclusao()).isNotNull();
        } finally {
            liberarEncerramento.countDown();
            executor.shutdownNow();
        }
    }

    private MockHttpServletRequestBuilder autenticada(MockHttpServletRequestBuilder request) {
        UsuarioAutenticado usuario = UsuarioAutenticado.de(gestor, "CPF_SENHA");
        return request.contentType(MediaType.APPLICATION_JSON)
                .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                        usuario, null, usuario.getAuthorities())))
                .with(csrf());
    }
}
