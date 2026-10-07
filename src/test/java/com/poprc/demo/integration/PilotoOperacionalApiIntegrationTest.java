package com.poprc.demo.integration;

import com.lowagie.text.pdf.PdfReader;
import com.poprc.demo.model.Contrato;
import com.poprc.demo.model.Funcionario;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.PerfilAcesso;
import com.poprc.demo.model.ProjetoStatus;
import com.poprc.demo.model.StatusOS;
import com.poprc.demo.model.TipoControleEstoque;
import com.poprc.demo.model.TipoMovimentacao;
import com.poprc.demo.model.UnidadeMedida;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.ContratoRepository;
import com.poprc.demo.repository.DocumentoInternoRepository;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.MovimentacaoEstoqueRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.service.EstoqueService;
import com.poprc.demo.service.SaldoLocalService;
import com.poprc.demo.storage.UploadStorage;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.enabled=true", "app.security.dev-login-enabled=false", "app.security.zoho-enabled=false"
})
@Transactional
// csrf() de outros testes substitui o repositorio do filtro compartilhado; este piloto usa cookies reais.
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class PilotoOperacionalApiIntegrationTest {
    private static final String SENHA_TESTE = "PilotoTeste123";
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager entityManager;
    @Autowired private PasswordEncoder encoder;
    @Autowired private FuncionarioRepository funcionarios;
    @Autowired private ContratoRepository contratos;
    @Autowired private ProjetoRepository projetos;
    @Autowired private ComarcaRepository comarcas;
    @Autowired private OrdemServicoRepository ordens;
    @Autowired private MaterialRepository materiais;
    @Autowired private DocumentoInternoRepository documentos;
    @Autowired private MovimentacaoEstoqueRepository movimentacoes;
    @Autowired private EstoqueService estoque;
    @Autowired private SaldoLocalService saldos;

    @ParameterizedTest(name = "Ciclo autenticado com {0} unidade(s) devolvida(s) por material")
    @ValueSource(ints = {0, 1})
    void executaPilotoComUploadsAssinaturasPdfESaldoConciliado(int devolvida) throws Exception {
        assertThat(UploadStorage.root().endsWith(Path.of("target", "test-uploads")))
                .as("uploads de teste devem permanecer isolados em target/test-uploads: %s", UploadStorage.root())
                .isTrue();
        Funcionario admin = usuario("Admin piloto", "12345678909", PerfilAcesso.ADMIN);
        Funcionario supervisor = usuario("Supervisor piloto", "39053344705", PerfilAcesso.SUPERVISOR_TECNICO);
        Funcionario tecnico = usuario("Tecnico piloto", "16899535009", PerfilAcesso.TECNICO);
        Funcionario almoxarife = usuario("Estoque piloto", "93541134780", PerfilAcesso.ESTOQUE);
        Sessao sessaoAdmin = login(admin);
        Sessao sessaoSupervisor = login(supervisor);
        Sessao sessaoTecnico = login(tecnico);
        Sessao sessaoEstoque = login(almoxarife);
        byte[] imagem = imagemTeste();
        String assinatura = "data:image/png;base64," + Base64.getEncoder().encodeToString(imagem);

        Contrato contrato = new Contrato();
        contrato.setContrato("PILOTO-" + UUID.randomUUID());
        contrato.setCliente("Cliente sintetico de homologacao");
        contrato.setVigenciaInicio(LocalDate.now());
        contrato.setVigenciaFim(LocalDate.now().plusYears(1));
        contrato = contratos.saveAndFlush(contrato);
        Material bucha = material("Bucha piloto", 62, "0.15");
        Material porca = material("Porca piloto", 54, "0.09");
        BigDecimal valorInicial = valorEstoque(bucha.getId(), porca.getId());

        long projetoId = json(sessaoAdmin, post("/api/projetos"), Map.of(
                "contrato", Map.of("id", contrato.getId()), "responsavel", Map.of("id", supervisor.getId()),
                "dataInicio", LocalDate.now().toString(), "dataFim", LocalDate.now().plusDays(1).toString(),
                "nomeComarcaVinculada", "Obra piloto API")).path("projeto").path("id").asLong();
        json(sessaoAdmin, put("/api/projetos/{id}/equipe", projetoId), Map.of("membros", List.of(
                Map.of("funcionarioId", supervisor.getId(), "papel", "LIDER_EQUIPE", "responsavelPrincipal", true),
                Map.of("funcionarioId", tecnico.getId(), "papel", "TECNICO", "responsavelPrincipal", false))));
        long comarcaId = comarcas.findByProjetoId(projetoId).orElseThrow().getId();
        LocalDateTime inicio = LocalDateTime.now();
        JsonNode os = json(sessaoAdmin, post("/api/ordens-servico"), Map.of(
                "contratoId", contrato.getId(), "projetoId", projetoId, "descricao", "Piloto API sintetico",
                "dataHoraInicio", inicio.toString(), "dataHoraFim", inicio.plusHours(4).toString(),
                "deadline", inicio.plusDays(1).toString(), "materiais", List.of(
                        Map.of("materialId", bucha.getId(), "quantidadePrevista", 2),
                        Map.of("materialId", porca.getId(), "quantidadePrevista", 2))));
        long osId = os.path("id").asLong();
        assertThat(os.path("status").asText()).isEqualTo("AGUARDANDO_VISTORIA");
        assertThat(os.path("numeroOs").asText()).isEqualTo(contrato.getContrato() + " - OS 01");
        JsonNode listaOr = json(sessaoAdmin, get("/api/ordens-retirada/os/{id}", osId));
        assertThat(listaOr.size()).isEqualTo(1);
        long orId = listaOr.get(0).path("id").asLong();
        assertThat(listaOr.get(0).path("numeroOr").asText()).isEqualTo(os.path("numeroOs").asText() + " - OR 01");
        conferirSaldo(bucha.getId(), 62, 2);
        conferirSaldo(porca.getId(), 54, 2);

        // Documento inicial e final passam pelas mesmas rotas usadas pela interface.
        assinarDocumento(sessaoAdmin, comarcaId, "VISTORIA_INICIAL_OS", assinatura);
        upload(sessaoSupervisor, "/api/comarcas/" + comarcaId + "/vistoria/foto", "foto", imagem);
        json(sessaoSupervisor, patch("/api/comarcas/{id}/vistoria/assinatura", comarcaId),
                Map.of("assinaturaBase64", assinatura));
        json(sessaoSupervisor, patch("/api/comarcas/{id}/avancar-etapa", comarcaId), Map.of());
        assertThat(ordens.findById(osId).orElseThrow().getStatus()).isEqualTo(StatusOS.AGUARDANDO_RETIRADA);
        JsonNode retirada = json(sessaoEstoque, patch("/api/ordens-retirada/{id}/executar", orId), Map.of(
                "conferidoPor", almoxarife.getNome(), "levadoPor", supervisor.getNome(),
                "assinaturaConferenteBase64", assinatura, "assinaturaRetiranteBase64", assinatura));
        assertThat(retirada.path("status").asText()).isEqualTo("RETIRADA");
        conferirSaldo(bucha.getId(), 60, 0);
        conferirSaldo(porca.getId(), 52, 0);

        JsonNode atividades = json(sessaoTecnico, get("/api/atividades-padrao/ativas"));
        assertThat(atividades.isEmpty()).isFalse();
        String checklist = mapper.writeValueAsString(Map.of("registradoPor", tecnico.getNome(),
                "atividades", List.of(atividades.get(0))));
        json(sessaoTecnico, put("/api/ordens-servico/{id}/checklist", osId), Map.of("checklist", checklist));
        JsonNode evidencia = json(sessaoTecnico, multipart("/api/campo/upload-foto")
                .file(new MockMultipartFile("file", "piloto.png", "image/png", imagem))
                .param("ordemServicoId", Long.toString(osId)).param("funcionarioId", tecnico.getId().toString())
                .param("latitude", "-7.11532").param("longitude", "-34.86100"));
        assertThat(json(sessaoTecnico, get("/api/campo/evidencias/os/{id}", osId)).size()).isEqualTo(1);
        assertThat(executar(sessaoTecnico, get("/api/campo/evidencias/{id}/arquivo", evidencia.path("id").asLong()))
                .getResponse().getContentAsByteArray()).isEqualTo(imagem);
        json(sessaoTecnico, put("/api/ordens-servico/{id}/status", osId),
                Map.of("status", "AGUARDANDO_VALIDACAO", "responsavel", tecnico.getNome()));
        json(sessaoSupervisor, put("/api/ordens-servico/{id}/status", osId),
                Map.of("status", "AGUARDANDO_DEVOLUCAO", "responsavel", supervisor.getNome()));
        json(sessaoSupervisor, patch("/api/comarcas/{id}/avancar-etapa", comarcaId), Map.of());
        upload(sessaoSupervisor, "/api/comarcas/" + comarcaId + "/virada-rede/prova", "foto", imagem);
        json(sessaoSupervisor, patch("/api/comarcas/{id}/virada-rede", comarcaId),
                Map.of("checklist", "Conectividade validada no teste", "concluida", true));

        JsonNode retorno = json(sessaoEstoque, patch("/api/ordens-retirada/{id}/devolver", orId), Map.of(
                "devolvidoPor", supervisor.getNome(), "recebidoPor", almoxarife.getNome(),
                "assinaturaRecebimentoBase64", assinatura, "itens", List.of(
                        Map.of("itemId", retirada.path("itens").get(0).path("id").asLong(), "quantidadeDevolvida", devolvida),
                        Map.of("itemId", retirada.path("itens").get(1).path("id").asLong(), "quantidadeDevolvida", devolvida))));
        assertThat(retorno.path("status").asText()).isEqualTo("DEVOLVIDA");
        assertThat(ordens.findById(osId).orElseThrow().getStatus()).isEqualTo(StatusOS.AGUARDANDO_AUDITORIA);
        JsonNode auditoria = json(sessaoAdmin, get("/api/comarcas/{id}/auditoria", comarcaId));
        for (JsonNode item : auditoria.path("materiais")) {
            json(sessaoAdmin, put("/api/comarcas/materiais/{id}/auditoria", item.path("id").asLong())
                    .param("quantidadeAuditada", Integer.toString(2 - devolvida)));
        }
        String statusAsBuilt = devolvida == 0 ? "HOMOLOGADO" : "HOMOLOGADO_COM_DIVERGENCIA";
        if (devolvida > 0) {
            for (String corpo : List.of("{}", "{\"justificativa\":null}",
                    "{\"justificativa\":\"\"}", "{\"justificativa\":\"   \"}")) {
                mvc.perform(patch("/api/comarcas/{id}/as-built/homologar", comarcaId)
                        .cookie(sessaoAdmin.cookie(), sessaoAdmin.csrf())
                        .header("X-XSRF-TOKEN", sessaoAdmin.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                        .andExpect(status().isBadRequest());
                entityManager.clear();
                assertThat(comarcas.findById(comarcaId).orElseThrow().getAsBuiltStatus())
                        .isEqualTo("DIVERGENTE");
                assertThat(ordens.findById(osId).orElseThrow().getStatus())
                        .isEqualTo(StatusOS.AGUARDANDO_AUDITORIA);
                assertThat(json(sessaoAdmin, get("/api/comarcas/{id}/auditoria", comarcaId)
                        ).path("homologacoes").size()).isZero();
            }
            mvc.perform(put("/api/projetos/{id}/as-built/homologar", projetoId)
                    .cookie(sessaoAdmin.cookie(), sessaoAdmin.csrf())
                    .header("X-XSRF-TOKEN", sessaoAdmin.csrf().getValue()))
                    .andExpect(status().isBadRequest());
            entityManager.clear();
            assertThat(comarcas.findById(comarcaId).orElseThrow().getAsBuiltStatus())
                    .isEqualTo("DIVERGENTE");
        }
        Map<String, String> pedidoHomologacao = devolvida == 0 ? Map.of()
                : Map.of("justificativa", "  Consumo inferior ao previsto no piloto  ");
        JsonNode homologacao = json(sessaoAdmin,
                patch("/api/comarcas/{id}/as-built/homologar", comarcaId), pedidoHomologacao);
        assertThat(homologacao.path("asBuiltStatus").asText()).isEqualTo(statusAsBuilt);
        JsonNode consultaHomologacao = json(sessaoAdmin, get("/api/comarcas/{id}/auditoria", comarcaId));
        assertThat(consultaHomologacao.path("homologacoes").size()).isEqualTo(1);
        assertThat(consultaHomologacao.path("homologacoes").get(0).path("justificativa").asText())
                .isEqualTo(devolvida == 0 ? "" : "Consumo inferior ao previsto no piloto");
        assertThat(consultaHomologacao.path("homologacoes").get(0).path("responsavel").asText())
                .isEqualTo(admin.getNome());
        long documentoId = assinarDocumento(sessaoAdmin, comarcaId, "ENCERRAMENTO_OS", assinatura);
        JsonNode encerramento = json(sessaoAdmin, patch("/api/comarcas/{id}/concluir", comarcaId),
                Map.of("concluidaPor", admin.getNome()));
        assertThat(encerramento.path("situacao").asText()).isEqualTo("CONCLUIDA");
        assertThat(ordens.findById(osId).orElseThrow().getStatus()).isEqualTo(StatusOS.CONCLUIDA);
        assertThat(projetos.findById(projetoId).orElseThrow().getStatus()).isEqualTo(ProjetoStatus.CONCLUIDO);
        assertThat(comarcas.findById(comarcaId).orElseThrow().getPercentualConcluido()).isEqualByComparingTo("100");
        JsonNode historico = json(sessaoAdmin, get("/api/ordens-servico/{id}/historico-status", osId));
        assertThat(historico.valueStream().map(item -> item.path("evento").asText()).toList()).containsExactly(
                "OS_CRIADA", "VISTORIA_LIBERADA", "MATERIAIS_RETIRADOS", "RELATORIO_TECNICO_ENVIADO",
                "RELATORIO_TECNICO_APROVADO", "MATERIAIS_DEVOLVIDOS", "AS_BUILT_HOMOLOGADO", "OBRA_ENCERRADA");
        assertThat(json(sessaoAdmin, get("/api/ordens-retirada/{id}/documentos", orId)).size()).isEqualTo(3);
        conferirSaldo(bucha.getId(), 60 + devolvida, 0);
        conferirSaldo(porca.getId(), 52 + devolvida, 0);
        assertThat(valorInicial.subtract(valorEstoque(bucha.getId(), porca.getId())))
                .isEqualByComparingTo(new BigDecimal("0.24").multiply(BigDecimal.valueOf(2 - devolvida)));
        var movimentos = movimentacoes.findByComarcaIdOrderByDataMovimentacaoDesc(comarcaId);
        assertThat(movimentos.stream().filter(m -> m.getTipo() == TipoMovimentacao.RETIRADA_OR).count()).isEqualTo(2);
        assertThat(movimentos.stream().filter(m -> m.getTipo() == TipoMovimentacao.DEVOLUCAO_OR).count())
                .isEqualTo(devolvida > 0 ? 2 : 0);
        assertThat(movimentos.stream().filter(m -> m.getTipo() == TipoMovimentacao.RETIRADA_OR
                || m.getTipo() == TipoMovimentacao.DEVOLUCAO_OR).toList()).allSatisfy(m -> {
            assertThat(m.getOrdemServico().getId()).isEqualTo(osId);
            assertThat(m.getOrdemRetirada().getId()).isEqualTo(orId);
            assertThat(m.getAutorizadoPor()).isEqualTo(almoxarife.getNome());
            assertThat(m.getRetiradoPor()).isEqualTo(supervisor.getNome());
        });
        var documento = documentos.findById(documentoId).orElseThrow();
        assertThat(Files.isRegularFile(UploadStorage.directory("documentos")
                .resolve(Path.of(documento.getPdfPath()).getFileName()))).isTrue();
    }

    private long assinarDocumento(Sessao sessao, long comarcaId, String tipo, String assinatura) throws Exception {
        String conteudo = mapper.writeValueAsString(Map.of("observacoes", "DOCUMENTO SINTETICO - TESTE AUTOMATIZADO"));
        JsonNode documento = json(sessao, post("/api/documentos-internos/vistoria"), Map.of(
                "comarcaId", comarcaId, "tipo", tipo, "conteudoJson", conteudo, "recebidoPor", sessao.nome()));
        long id = documento.path("id").asLong();
        List<String> papeis = List.of("TECNICO", "GESTOR_RC", "GERENTE_FORUM");
        for (int i = 0; i < papeis.size(); i++) {
            documento = json(sessao, patch("/api/documentos-internos/{id}/assinaturas/{papel}", id, papeis.get(i)),
                    Map.of("assinaturaBase64", assinatura, "nomeAssinante", "Teste " + papeis.get(i)));
            assertThat(documento.path("status").asText()).isEqualTo(i == 2 ? "REGISTRADO" : "PARCIALMENTE_ASSINADO");
        }
        assertThat(json(sessao, get("/api/documentos-internos/{id}/assinaturas/log", id)).size()).isEqualTo(3);
        assertThat(json(sessao, get("/api/documentos-internos/{id}/integridade", id)).path("integro").asBoolean()).isTrue();
        byte[] pdf = executar(sessao, get("/api/documentos-internos/{id}/pdf", id)).getResponse().getContentAsByteArray();
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isGreaterThan(0);
        }
        return id;
    }

    private Funcionario usuario(String nome, String cpf, PerfilAcesso perfil) {
        Funcionario usuario = new Funcionario();
        usuario.setNome(nome);
        usuario.setCpf(cpf);
        usuario.setFuncao(perfil.name());
        usuario.setPerfilAcesso(perfil);
        usuario.setAtivo(true);
        usuario.setSenhaHash(encoder.encode(SENHA_TESTE));
        usuario.setTrocaSenhaObrigatoria(false);
        return funcionarios.saveAndFlush(usuario);
    }

    private Sessao login(Funcionario usuario) throws Exception {
        Cookie cookie = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("cpf", usuario.getCpf(), "senha", SENHA_TESTE))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
        assertThat(cookie).isNotNull();
        MvcResult resultadoCsrf = mvc.perform(get("/api/auth/csrf").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        JsonNode token = mapper.readTree(resultadoCsrf.getResponse().getContentAsString());
        Cookie csrf = resultadoCsrf.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        assertThat(csrf.getValue()).isEqualTo(token.path("token").asText());
        assertThat(token.path("headerName").asText()).isEqualTo("X-XSRF-TOKEN");
        return new Sessao(cookie, csrf, usuario.getNome());
    }

    private Material material(String nome, int quantidade, String custo) {
        Material material = new Material();
        material.setNome(nome);
        material.setPartNumber("PILOTO-" + UUID.randomUUID());
        material.setTipoControle(TipoControleEstoque.UNIDADE);
        material.setUnidadeMedida(UnidadeMedida.UNIDADE);
        material.setCategoria("MATERIAL_CONSUMO");
        material.setQuantidadeDisponivel(quantidade);
        material.setCustoMedio(new BigDecimal(custo));
        material.setLocalizacao("Deposito piloto API");
        return estoque.cadastrarMaterial(material);
    }

    private void conferirSaldo(Long id, int quantidade, int reserva) {
        Material material = materiais.findById(id).orElseThrow();
        assertThat(material.getQuantidadeDisponivel()).isEqualTo(quantidade);
        assertThat(material.getQuantidadeReservada()).isEqualTo(reserva);
        assertThat(saldos.listarSaldos(id).stream().mapToInt(s -> s.getQuantidadeDisponivel()).sum()).isEqualTo(quantidade);
        assertThat(saldos.listarSaldos(id).stream().mapToInt(s -> s.getQuantidadeReservada()).sum()).isEqualTo(reserva);
    }

    private BigDecimal valorEstoque(Long... ids) {
        return List.of(ids).stream().map(id -> materiais.findById(id).orElseThrow())
                .map(m -> m.getCustoMedio().multiply(BigDecimal.valueOf(m.getQuantidadeDisponivel())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void upload(Sessao sessao, String url, String campo, byte[] imagem) throws Exception {
        executar(sessao, multipart(url).file(new MockMultipartFile(campo, "piloto.png", "image/png", imagem)));
    }

    private JsonNode json(Sessao sessao, AbstractMockHttpServletRequestBuilder<?> request, Object corpo) throws Exception {
        return json(sessao, request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo)));
    }

    private JsonNode json(Sessao sessao, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        return mapper.readTree(executar(sessao, request).getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult executar(Sessao sessao, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        MvcResult result = mvc.perform(request.cookie(sessao.cookie(), sessao.csrf())
                .header("X-XSRF-TOKEN", sessao.csrf().getValue()))
                .andExpect(status().is2xxSuccessful()).andReturn();
        entityManager.flush();
        entityManager.clear();
        return result;
    }

    private byte[] imagemTeste() throws Exception {
        BufferedImage imagem = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
        var graphics = imagem.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 120, 40);
        graphics.setColor(Color.BLACK);
        graphics.drawString("TESTE SINTETICO", 4, 24);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(imagem, "png", bytes);
        return bytes.toByteArray();
    }

    private record Sessao(Cookie cookie, Cookie csrf, String nome) { }
}
