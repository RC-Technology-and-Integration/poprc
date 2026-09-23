package com.poprc.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poprc.demo.DemoApplication;
import com.poprc.demo.model.Contrato;
import com.poprc.demo.model.Funcionario;
import com.poprc.demo.model.PerfilAcesso;
import com.poprc.demo.repository.ContratoRepository;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import java.util.stream.Stream;

@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "app.security.dev-login-enabled=false"
})
@Transactional
class SecurityAuthorizationIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FuncionarioRepository funcionarioRepository;

    @Autowired
    private ContratoRepository contratoRepository;

    @Test
    void apiSemSessaoRetornaNaoAutorizado() throws Exception {
        int status = mockMvc.perform(get("/api/estoque/materiais"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    @Test
    void tecnicoNaoPodeMovimentarEstoque() throws Exception {
        int status = mockMvc.perform(post("/api/estoque/entrada")
                        .with(user("tecnico").roles("TECNICO"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
    }

    @Test
    void perfilEstoquePassaPelaCamadaDeAutorizacao() throws Exception {
        int status = mockMvc.perform(post("/api/estoque/entrada")
                        .with(user("estoquista").roles("ESTOQUE"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401).isNotEqualTo(403);
    }

    @Test
    void tecnicoNaoPodeCriarOrdemDeServico() throws Exception {
        int status = mockMvc.perform(post("/api/ordens-servico")
                        .with(user("tecnico").roles("TECNICO"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
    }

    @Test
    void tecnicoNaoPodeAprovarAPropriaExecucaoPorChamadaDireta() throws Exception {
        mockMvc.perform(put("/api/ordens-servico/999/status")
                        .with(authentication(autenticacao("TECNICO")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"AGUARDANDO_DEVOLUCAO\",\"responsavel\":\"Outro usuário\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tecnicoPodeConsultarAtividadesPadrao() throws Exception {
        int status = mockMvc.perform(get("/api/atividades-padrao")
                        .with(user("tecnico").roles("TECNICO")))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401).isNotEqualTo(403);
    }

    @Test
    void auditorNaoPodeAlterarFluxoOperacionalDaObra() throws Exception {
        int status = mockMvc.perform(patch("/api/comarcas/1/avancar-etapa")
                        .with(user("auditor").roles("AUDITOR"))
                        .with(csrf()))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
    }

    @Test
    void estoquePodeConsultarComarcasParaRastreabilidade() throws Exception {
        int status = mockMvc.perform(get("/api/comarcas")
                        .with(user("estoque").roles("ESTOQUE")))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401).isNotEqualTo(403);
    }

    @Test
    void somenteAdministradorPodeConsultarAuditoriaDeAcessos() throws Exception {
        mockMvc.perform(get("/api/funcionarios/auditoria-acessos")
                        .with(user("administrador").roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/funcionarios/auditoria-acessos")
                        .with(user("supervisor").roles("SUPERVISOR_TECNICO")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/funcionarios/auditoria-acessos")
                        .with(user("estoquista").roles("ESTOQUE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorNaoPodeRegistrarOperacoesDeCampo() throws Exception {
        int status = mockMvc.perform(post("/api/campo/ponto")
                        .with(user("auditor").roles("AUDITOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
    }

    @Test
    void estoqueConsultaApenasOpcoesDeContratoSemDadosFinanceiros() throws Exception {
        Contrato contrato = new Contrato();
        contrato.setCliente("Cliente de teste");
        contrato.setContrato("ESTOQUE-TESTE");
        contrato.setValorGlobal(new java.math.BigDecimal("123456.78"));
        contrato.setEscopo("Escopo reservado");
        contratoRepository.save(contrato);

        String resposta = mockMvc.perform(get("/api/contratos/opcoes-estoque")
                        .with(user("estoquista").roles("ESTOQUE")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(resposta).contains("ESTOQUE-TESTE", "Cliente de teste")
                .doesNotContain("valorGlobal", "escopo", "Escopo reservado", "123456.78");
        mockMvc.perform(get("/api/contratos")
                        .with(user("estoquista").roles("ESTOQUE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorPodeConsultarMasNaoAlterarDocumentosInternos() throws Exception {
        mockMvc.perform(get("/api/documentos-internos/comarca/999")
                        .with(user("auditor").roles("AUDITOR")))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/documentos-internos/999/assinar")
                        .with(user("auditor").roles("AUDITOR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tecnicoNaoPodeAbrirComprovanteFinanceiroDiretamente() throws Exception {
        mockMvc.perform(get("/uploads/financeiro/comprovantes/nota-fiscal.pdf")
                        .with(user("tecnico").roles("TECNICO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void estoqueNaoPodeAbrirEvidenciaDeCampoDiretamente() throws Exception {
        mockMvc.perform(get("/uploads/evidencias/vistoria.png")
                        .with(user("estoquista").roles("ESTOQUE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tecnicoNaoPodeContornarEscopoUsandoUrlEstaticaDeEvidencia() throws Exception {
        mockMvc.perform(get("/uploads/evidencias/vistoria.png")
                        .with(user("tecnico").roles("TECNICO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tecnicoNaoPodeContornarEscopoUsandoUrlEstaticaDeDocumento() throws Exception {
        mockMvc.perform(get("/uploads/documentos/ordens-retirada/or.pdf")
                        .with(user("tecnico").roles("TECNICO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void estoqueNaoPodeAbrirFotoProtegidaDaVistoria() throws Exception {
        mockMvc.perform(get("/api/comarcas/1/vistoria/foto")
                        .with(user("estoquista").roles("ESTOQUE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void estoquePodeConsultarFilaOperacionalDaSuaArea() throws Exception {
        mockMvc.perform(get("/api/pendencias-operacionais")
                        .param("area", "ADMINISTRACAO")
                        .with(authentication(autenticacao("ESTOQUE"))))
                .andExpect(status().isOk());
    }

    @Test
    void tecnicoPodeConsultarFilaLimitadaAsSuasOrdens() throws Exception {
        mockMvc.perform(get("/api/pendencias-operacionais")
                        .param("funcionarioId", "999")
                        .with(authentication(autenticacao("TECNICO"))))
                .andExpect(status().isOk());
    }

    @Test
    void operacaoCriticaSemConfirmacaoRecenteSolicitaSenha() throws Exception {
        mockMvc.perform(post("/api/funcionarios")
                        .with(user("administrador").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.reautenticacaoNecessaria").value(true));
    }

    @ParameterizedTest(name = "{0} consultando {1}: permitido={2}")
    @MethodSource("matrizConsultas")
    void matrizDeConsultaPorModulo(String perfil, String rota, boolean permitido) throws Exception {
        int status = mockMvc.perform(get(rota).with(user("matriz").roles(perfil)))
                .andReturn().getResponse().getStatus();

        if (permitido) {
            assertThat(status).isNotEqualTo(401).isNotEqualTo(403);
        } else {
            assertThat(status).isEqualTo(403);
        }
    }

    @ParameterizedTest(name = "{0} executando {1}: permitido={2}")
    @MethodSource("matrizEscritas")
    void matrizDeEscritaPorModulo(String perfil, String rota, boolean permitido) throws Exception {
        int status = mockMvc.perform(post(rota)
                        .with(user("matriz").roles(perfil))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getStatus();

        if (permitido) {
            assertThat(status).isNotEqualTo(401).isNotEqualTo(403);
        } else {
            assertThat(status).isEqualTo(403);
        }
    }

    private static Stream<Arguments> matrizConsultas() {
        return Stream.of(
                Arguments.of("ADMIN", "/api/dashboard/executivo", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/dashboard/executivo", true),
                Arguments.of("TECNICO", "/api/dashboard/executivo", false),
                Arguments.of("ADMIN", "/api/contratos", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/contratos", true),
                Arguments.of("AUDITOR", "/api/contratos", false),
                Arguments.of("ESTOQUE", "/api/contratos/opcoes-estoque", true),
                Arguments.of("TECNICO", "/api/contratos/opcoes-estoque", false),
                Arguments.of("AUDITOR", "/api/contratos/opcoes-estoque", false),
                Arguments.of("ADMIN", "/api/projetos", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/projetos", true),
                Arguments.of("ESTOQUE", "/api/projetos", false),
                Arguments.of("ADMIN", "/api/funcionarios", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/funcionarios", true),
                Arguments.of("ESTOQUE", "/api/funcionarios", true),
                Arguments.of("TECNICO", "/api/funcionarios", false),
                Arguments.of("ADMIN", "/api/ordens-servico", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/ordens-servico", true),
                Arguments.of("TECNICO", "/api/ordens-servico", true),
                Arguments.of("AUDITOR", "/api/ordens-servico", false),
                Arguments.of("ADMIN", "/api/comarcas", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/comarcas", true),
                Arguments.of("TECNICO", "/api/comarcas", true),
                Arguments.of("ESTOQUE", "/api/comarcas", true),
                Arguments.of("AUDITOR", "/api/comarcas", true),
                Arguments.of("ADMIN", "/api/estoque/materiais", true),
                Arguments.of("ESTOQUE", "/api/estoque/materiais", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/estoque/materiais", true),
                Arguments.of("TECNICO", "/api/estoque/materiais", false),
                Arguments.of("AUDITOR", "/api/estoque/materiais", false),
                Arguments.of("ADMIN", "/api/ordens-retirada", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/ordens-retirada", true),
                Arguments.of("TECNICO", "/api/ordens-retirada", true),
                Arguments.of("ESTOQUE", "/api/ordens-retirada", true),
                Arguments.of("AUDITOR", "/api/ordens-retirada", true),
                Arguments.of("ADMIN", "/api/faturamentos", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/faturamentos", false),
                Arguments.of("AUDITOR", "/api/faturamentos", false),
                Arguments.of("ADMIN", "/api/atividades-padrao", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/atividades-padrao", true),
                Arguments.of("TECNICO", "/api/atividades-padrao", true),
                Arguments.of("ESTOQUE", "/api/atividades-padrao", false),
                Arguments.of("AUDITOR", "/api/atividades-padrao", false));
    }

    private static Stream<Arguments> matrizEscritas() {
        return Stream.of(
                Arguments.of("ADMIN", "/api/contratos", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/contratos", true),
                Arguments.of("TECNICO", "/api/contratos", false),
                Arguments.of("ADMIN", "/api/funcionarios", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/funcionarios", false),
                Arguments.of("ESTOQUE", "/api/funcionarios", false),
                Arguments.of("ADMIN", "/api/atividades-padrao", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/atividades-padrao", false),
                Arguments.of("ADMIN", "/api/ordens-retirada/os/999", true),
                Arguments.of("ESTOQUE", "/api/ordens-retirada/os/999", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/ordens-retirada/os/999", false),
                Arguments.of("TECNICO", "/api/ordens-retirada/os/999", false),
                Arguments.of("AUDITOR", "/api/ordens-retirada/os/999", false),
                Arguments.of("ADMIN", "/api/as-built", true),
                Arguments.of("AUDITOR", "/api/as-built", true),
                Arguments.of("SUPERVISOR_TECNICO", "/api/as-built", false),
                Arguments.of("TECNICO", "/api/as-built", false),
                Arguments.of("ESTOQUE", "/api/as-built", false));
    }

    private UsernamePasswordAuthenticationToken autenticacao(String perfil) {
        Funcionario funcionario = new Funcionario();
        funcionario.setNome("Usuário " + perfil);
        funcionario.setFuncao("Homologação");
        funcionario.setCidade("João Pessoa");
        funcionario.setPerfilAcesso(PerfilAcesso.valueOf(perfil));
        funcionario = funcionarioRepository.saveAndFlush(funcionario);
        UsuarioAutenticado usuario = new UsuarioAutenticado(
                funcionario.getId(), funcionario.getNome(), null, perfil, "CPF_SENHA", false);
        return new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities());
    }
}
