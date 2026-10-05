package com.poprc.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poprc.demo.DemoApplication;
import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.Funcionario;
import com.poprc.demo.model.PerfilAcesso;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.model.ProjetoMembro;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.repository.ProjetoMembroRepository;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "app.security.dev-login-enabled=false",
        "app.security.zoho-enabled=false"
})
class ComarcaPatchAutorizacaoIntegrationTest {
    enum Operacao { PROGRESSO, PENDENCIAS, ATUALIZACAO }

    @Autowired private MockMvc mockMvc;
    @Autowired private FuncionarioRepository funcionarios;
    @Autowired private ProjetoRepository projetos;
    @Autowired private ProjetoMembroRepository membros;
    @Autowired private ComarcaRepository comarcas;

    private Funcionario tecnicoOutraEquipe;
    private Funcionario tecnicoDaEquipe;
    private Funcionario supervisor;
    private Long obraId;
    private Long projetoDaObraId;
    private Long projetoOutraEquipeId;
    private Long membroId;

    @BeforeEach
    void prepararEquipes() {
        tecnicoOutraEquipe = funcionario("Tecnico outra equipe", PerfilAcesso.TECNICO);
        tecnicoDaEquipe = funcionario("Tecnico da equipe", PerfilAcesso.TECNICO);
        supervisor = funcionario("Supervisor", PerfilAcesso.SUPERVISOR_TECNICO);

        Projeto projetoDaObra = projeto(supervisor);
        projetoDaObraId = projetoDaObra.getId();
        projetoOutraEquipeId = projeto(tecnicoOutraEquipe).getId();

        ProjetoMembro membro = new ProjetoMembro();
        membro.setProjeto(projetoDaObra);
        membro.setFuncionario(tecnicoDaEquipe);
        membro.setPapel("TECNICO");
        membroId = membros.saveAndFlush(membro).getId();

        Comarca obra = new Comarca();
        obra.setNomeComarca("Obra HTTP autorizacao");
        obra.setProjeto(projetoDaObra);
        obra.setPercentualConcluido(new BigDecimal("10"));
        obra.setSituacao("EM_ANDAMENTO");
        obra.setPendencias("original");
        obraId = comarcas.saveAndFlush(obra).getId();
    }

    @AfterEach
    void removerDadosSinteticos() {
        if (obraId != null) comarcas.deleteById(obraId);
        if (membroId != null) membros.deleteById(membroId);
        if (projetoDaObraId != null) projetos.deleteById(projetoDaObraId);
        if (projetoOutraEquipeId != null) projetos.deleteById(projetoOutraEquipeId);
        if (tecnicoOutraEquipe != null) funcionarios.deleteById(tecnicoOutraEquipe.getId());
        if (tecnicoDaEquipe != null) funcionarios.deleteById(tecnicoDaEquipe.getId());
        if (supervisor != null) funcionarios.deleteById(supervisor.getId());
    }

    @ParameterizedTest
    @EnumSource(Operacao.class)
    void tecnicoDeOutraEquipeRecebe403SemAlteracaoPersistida(Operacao operacao) throws Exception {
        mockMvc.perform(requisicao(operacao, obraId, tecnicoOutraEquipe))
                .andExpect(status().isForbidden());
        conferirEstado(operacao, false);
    }

    @ParameterizedTest
    @EnumSource(Operacao.class)
    void tecnicoMembroDaEquipePodeAlterar(Operacao operacao) throws Exception {
        mockMvc.perform(requisicao(operacao, obraId, tecnicoDaEquipe))
                .andExpect(status().isOk());
        conferirEstado(operacao, true);
    }

    @ParameterizedTest
    @EnumSource(Operacao.class)
    void supervisorPodeAlterar(Operacao operacao) throws Exception {
        mockMvc.perform(requisicao(operacao, obraId, supervisor))
                .andExpect(status().isOk());
        conferirEstado(operacao, true);
    }

    @ParameterizedTest
    @EnumSource(Operacao.class)
    void obraInexistenteRetorna404(Operacao operacao) throws Exception {
        mockMvc.perform(requisicao(operacao, Long.MAX_VALUE, tecnicoDaEquipe))
                .andExpect(status().isNotFound());
        conferirEstado(operacao, false);
    }

    private Funcionario funcionario(String nome, PerfilAcesso perfil) {
        Funcionario funcionario = new Funcionario();
        funcionario.setNome(nome);
        funcionario.setPerfilAcesso(perfil);
        funcionario.setAtivo(true);
        return funcionarios.saveAndFlush(funcionario);
    }

    private Projeto projeto(Funcionario responsavel) {
        Projeto projeto = new Projeto();
        projeto.setResponsavel(responsavel);
        return projetos.saveAndFlush(projeto);
    }

    private MockHttpServletRequestBuilder requisicao(Operacao operacao, Long id, Funcionario funcionario) {
        MockHttpServletRequestBuilder request = switch (operacao) {
            case PROGRESSO -> patch("/api/comarcas/{id}/progresso", id)
                    .content("{\"percentualConcluido\":50,\"situacao\":\"EM_EXECUCAO\"}");
            case PENDENCIAS -> patch("/api/comarcas/{id}/pendencias", id)
                    .content("{\"pendencias\":\"nova\"}");
            case ATUALIZACAO -> patch("/api/comarcas/{id}", id)
                    .content("{\"percentualConcluido\":50,\"pendencias\":\"nova\"}");
        };
        UsuarioAutenticado usuario = UsuarioAutenticado.de(funcionario, "CPF_SENHA");
        return request.contentType(MediaType.APPLICATION_JSON)
                .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                        usuario, null, usuario.getAuthorities())))
                .with(csrf());
    }

    private void conferirEstado(Operacao operacao, boolean alterada) {
        Comarca persistida = comarcas.findById(obraId).orElseThrow();
        boolean progressoAlterado = alterada && operacao != Operacao.PENDENCIAS;
        boolean pendenciasAlteradas = alterada && operacao != Operacao.PROGRESSO;
        assertThat(persistida.getPercentualConcluido())
                .isEqualByComparingTo(progressoAlterado ? "50" : "10");
        assertThat(persistida.getSituacao())
                .isEqualTo(progressoAlterado && operacao == Operacao.PROGRESSO
                        ? "EM_EXECUCAO" : "EM_ANDAMENTO");
        assertThat(persistida.getPendencias()).isEqualTo(pendenciasAlteradas ? "nova" : "original");
    }
}
