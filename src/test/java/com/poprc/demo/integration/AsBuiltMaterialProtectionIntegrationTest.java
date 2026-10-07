package com.poprc.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poprc.demo.DemoApplication;
import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.Funcionario;
import com.poprc.demo.model.HistoricoHomologacaoAsBuilt;
import com.poprc.demo.model.MaterialItem;
import com.poprc.demo.model.PerfilAcesso;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.model.ProjetoMembro;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.repository.HistoricoHomologacaoAsBuiltRepository;
import com.poprc.demo.repository.MaterialItemRepository;
import com.poprc.demo.repository.ProjetoMembroRepository;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import com.poprc.demo.service.ComarcaService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
        "app.security.enabled=true", "app.security.dev-login-enabled=false",
        "app.security.zoho-enabled=false"
})
class AsBuiltMaterialProtectionIntegrationTest {
    enum Alteracao { FALTANTES, TIMELINE }

    @Autowired private MockMvc mvc;
    @Autowired private ComarcaService comarcaService;
    @Autowired private FuncionarioRepository funcionarios;
    @Autowired private ProjetoRepository projetos;
    @Autowired private ProjetoMembroRepository membros;
    @Autowired private ComarcaRepository comarcas;
    @Autowired private MaterialItemRepository materiais;
    @Autowired private HistoricoHomologacaoAsBuiltRepository historico;

    private Funcionario admin;
    private Funcionario tecnicoVinculado;
    private Funcionario tecnicoExterno;
    private Funcionario auditor;
    private Long comarcaId;
    private Long materialId;
    private Long projetoId;
    private Long membroId;

    @BeforeEach
    void prepararHomologacaoRegistrada() {
        admin = funcionario("Admin teste", PerfilAcesso.ADMIN);
        tecnicoVinculado = funcionario("Técnico vinculado teste", PerfilAcesso.TECNICO);
        tecnicoExterno = funcionario("Técnico externo teste", PerfilAcesso.TECNICO);
        auditor = funcionario("Auditor teste", PerfilAcesso.AUDITOR);
        Projeto projeto = projetos.saveAndFlush(new Projeto());
        projetoId = projeto.getId();
        ProjetoMembro membro = new ProjetoMembro();
        membro.setProjeto(projeto);
        membro.setFuncionario(tecnicoVinculado);
        membro.setPapel("TECNICO");
        membroId = membros.saveAndFlush(membro).getId();

        Comarca comarca = new Comarca();
        comarca.setNomeComarca("Obra sintética de proteção");
        comarca.setProjeto(projeto);
        comarca.setSituacao("AS_BUILT_HOMOLOGADO");
        comarca.setAsBuiltStatus("HOMOLOGADO");
        comarcaId = comarcas.saveAndFlush(comarca).getId();

        MaterialItem material = new MaterialItem();
        material.setComarca(comarca);
        material.setNomeMaterial("Material sintético");
        material.setQuantidadePrevista(BigDecimal.ONE);
        material.setQuantidadeAuditada(BigDecimal.ONE);
        materialId = materiais.saveAndFlush(material).getId();

        HistoricoHomologacaoAsBuilt registro = new HistoricoHomologacaoAsBuilt();
        registro.setComarca(comarca);
        registro.setStatus("HOMOLOGADO");
        registro.setResponsavel("Admin teste");
        registro.setRegistradoEm(LocalDateTime.now());
        historico.saveAndFlush(registro);
    }

    @AfterEach
    void removerDadosSinteticos() {
        historico.deleteAll(historico.findByComarcaIdOrderByIdDesc(comarcaId));
        materiais.deleteById(materialId);
        comarcas.deleteById(comarcaId);
        membros.deleteById(membroId);
        projetos.deleteById(projetoId);
        funcionarios.deleteById(admin.getId());
        funcionarios.deleteById(tecnicoVinculado.getId());
        funcionarios.deleteById(tecnicoExterno.getId());
        funcionarios.deleteById(auditor.getId());
    }

    @ParameterizedTest
    @EnumSource(Alteracao.class)
    void homologacaoBloqueiaAlteracaoSemPersistir(Alteracao alteracao) throws Exception {
        mvc.perform(requisicao(alteracao, admin)).andExpect(status().isBadRequest());
        conferirDadosOriginais();
        assertThat(comarcas.findById(comarcaId).orElseThrow().getAsBuiltStatus()).isEqualTo("HOMOLOGADO");
        assertThat(historico.findByComarcaIdOrderByIdDesc(comarcaId)).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(Alteracao.class)
    void reaberturaFormalPermiteAlteracao(Alteracao alteracao) throws Exception {
        comarcaService.reabrirAsBuilt(comarcaId);
        mvc.perform(requisicao(alteracao, admin)).andExpect(status().isOk());
        conferirAlteracao(alteracao);
        assertThat(historico.findByComarcaIdOrderByIdDesc(comarcaId)).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(Alteracao.class)
    void tecnicoSemVinculoNaoPodeAlterarMesmoAposReabertura(Alteracao alteracao) throws Exception {
        comarcaService.reabrirAsBuilt(comarcaId);
        mvc.perform(requisicao(alteracao, tecnicoExterno)).andExpect(status().isForbidden());
        conferirDadosOriginais();
    }

    @ParameterizedTest
    @EnumSource(Alteracao.class)
    void tecnicoVinculadoPodeAlterarAposReabertura(Alteracao alteracao) throws Exception {
        comarcaService.reabrirAsBuilt(comarcaId);
        mvc.perform(requisicao(alteracao, tecnicoVinculado)).andExpect(status().isOk());
        conferirAlteracao(alteracao);
    }

    @ParameterizedTest
    @EnumSource(Alteracao.class)
    void auditorNaoPodeUsarRotasDeEdicao(Alteracao alteracao) throws Exception {
        comarcaService.reabrirAsBuilt(comarcaId);
        mvc.perform(requisicao(alteracao, auditor)).andExpect(status().isForbidden());
        conferirDadosOriginais();
    }

    private Funcionario funcionario(String nome, PerfilAcesso perfil) {
        Funcionario funcionario = new Funcionario();
        funcionario.setNome(nome);
        funcionario.setPerfilAcesso(perfil);
        funcionario.setAtivo(true);
        return funcionarios.saveAndFlush(funcionario);
    }

    private MockHttpServletRequestBuilder requisicao(Alteracao alteracao, Funcionario funcionario) {
        MockHttpServletRequestBuilder request = switch (alteracao) {
            case FALTANTES -> patch("/api/comarcas/{id}/materiais-faltantes", comarcaId)
                    .content("{\"faltouMaterial\":true,\"materialItemIds\":[" + materialId
                            + "],\"descricao\":\"Falta sintética\"}");
            case TIMELINE -> patch("/api/comarcas/materiais-previstos/{id}/timeline", materialId)
                    .content("{\"dataHoraSolicitacao\":\"2026-09-29T09:00:00\","
                            + "\"dataHoraRetirada\":\"2026-09-29T10:00:00\","
                            + "\"dataHoraUso\":\"2026-09-29T11:00:00\"}");
        };
        UsuarioAutenticado usuario = UsuarioAutenticado.de(funcionario, "CPF_SENHA");
        return request.contentType(MediaType.APPLICATION_JSON)
                .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                        usuario, null, usuario.getAuthorities())))
                .with(csrf());
    }

    private void conferirDadosOriginais() {
        Comarca comarca = comarcas.findById(comarcaId).orElseThrow();
        MaterialItem material = materiais.findById(materialId).orElseThrow();
        assertThat(comarca.getFaltouMaterial()).isFalse();
        assertThat(comarca.getDescricaoMaterialFaltante()).isNull();
        assertThat(material.getMaterialFaltante()).isFalse();
        assertThat(material.getDescricaoFaltante()).isNull();
        assertThat(material.getDataHoraSolicitacao()).isNull();
        assertThat(material.getDataHoraRetirada()).isNull();
        assertThat(material.getDataHoraUso()).isNull();
    }

    private void conferirAlteracao(Alteracao alteracao) {
        Comarca comarca = comarcas.findById(comarcaId).orElseThrow();
        MaterialItem material = materiais.findById(materialId).orElseThrow();
        if (alteracao == Alteracao.FALTANTES) {
            assertThat(comarca.getFaltouMaterial()).isTrue();
            assertThat(material.getMaterialFaltante()).isTrue();
            assertThat(material.getDescricaoFaltante()).isEqualTo("Falta sintética");
        } else {
            assertThat(material.getDataHoraSolicitacao()).isEqualTo(LocalDateTime.of(2026, 9, 29, 9, 0));
            assertThat(material.getDataHoraRetirada()).isEqualTo(LocalDateTime.of(2026, 9, 29, 10, 0));
            assertThat(material.getDataHoraUso()).isEqualTo(LocalDateTime.of(2026, 9, 29, 11, 0));
        }
    }
}
