package com.poprc.demo.integration;

import com.poprc.demo.controller.DocumentoInternoController;
import com.poprc.demo.dto.*;
import com.poprc.demo.model.*;
import com.poprc.demo.repository.*;
import com.poprc.demo.security.UsuarioAutenticado;
import com.poprc.demo.security.SessaoAutenticacaoService;
import com.poprc.demo.service.*;
import com.poprc.demo.storage.UploadStorage;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.ambiente=homologacao", "app.documentos.simulacao-enabled=true", "app.security.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SimulacaoDocumentoIntegrationTest {
    @Autowired OrdemServicoService osService;
    @Autowired OrdemRetiradaService orService;
    @Autowired ComarcaService comarcaService;
    @Autowired DocumentoInternoController documentoController;
    @Autowired DocumentoPdfService pdfService;
    @Autowired OrdemRetiradaPdfService orPdfService;
    @Autowired FuncionarioRepository funcionarios;
    @Autowired ContratoRepository contratos;
    @Autowired ProjetoRepository projetos;
    @Autowired ComarcaRepository comarcas;
    @Autowired MaterialRepository materiais;
    @Autowired EstoqueService estoqueService;
    @Autowired OrdemServicoRepository ordens;
    @Autowired OrdemRetiradaRepository retiradas;
    @Autowired DocumentoInternoRepository documentos;
    @Autowired EvidenciaFotoRepository fotos;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void criaRecarregaAssinaVersionaERegeneraSemConversao() throws Exception {
        var c = cenario();
        var doc = documentoController.gerarDocumentoVistoria(requisicaoDocumento(c, "VISTORIA_INICIAL_OS"), c.auth()).getBody();
        assertTrue(doc.isSimulacao());
        Long id = doc.getId();
        em.flush(); em.clear();
        doc = documentos.findById(id).orElseThrow();
        assertTrue(doc.isSimulacao());
        assertTrue(doc.getConteudoJson().contains("EMPRESA FICTÍCIA"));
        assertFalse(doc.getConteudoJson().contains("Pessoa externa"));
        mvc.perform(get("/api/documentos-internos/comarca/" + c.comarcaId()).with(authentication(c.auth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].simulacao").value(true));
        var conversao = requisicaoDocumento(c, "VISTORIA_INICIAL_OS");
        conversao.setSimulacao(false);
        mvc.perform(put("/api/documentos-internos/" + id + "/conteudo").with(authentication(c.auth())).with(csrf())
                .contentType("application/json").content(new ObjectMapper().writeValueAsString(conversao)))
                .andExpect(status().isBadRequest());
        assinarTres(id, c);
        em.flush(); em.clear();
        doc = documentos.findById(id).orElseThrow();
        assertEquals("REGISTRADO", doc.getStatus());
        assertTrue(doc.getTecnicoAssinadoPor().startsWith("TESTE"));
        assertEquals(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste(), doc.getAssinaturaTecnicoBase64());
        assertEquals(true, documentoController.verificarIntegridade(id, c.auth()).getBody().get("integro"));
        assertThrows(IllegalStateException.class, () -> documentoController.atualizarConteudoDocumento(id, requisicaoDocumento(c, "VISTORIA_INICIAL_OS"), c.auth()));
        verificarMarca(pdfService.obterPdf(doc));
        Path arquivo = UploadStorage.directory("documentos").resolve(Path.of(doc.getPdfPath()).getFileName()).normalize();
        assertTrue(arquivo.startsWith(UploadStorage.root()));
        Files.delete(arquivo); // Apenas o PDF descartável deste teste; força a regeneração existente.
        verificarMarca(pdfService.obterPdf(doc));
        var motivo = new DocumentoInternoController.InvalidacaoDocumentoRequest();
        motivo.setMotivo("Correção do cenário TESTE");
        var nova = documentoController.invalidarECriarNovaVersao(id, motivo, c.auth()).getBody();
        em.flush(); em.clear();
        nova = documentos.findById(nova.getId()).orElseThrow();
        assertTrue(nova.isSimulacao());
        assertEquals(id, nova.getDocumentoOrigem().getId());
        verificarMarca(pdfService.gerarPdf(nova));
        assertEquals("INVALIDADO", documentos.findById(id).orElseThrow().getStatus());
    }

    @Test void percursoMantemVistoriaConferenciaRelatorioViradaJustificativaEEncerramento() throws Exception {
        var c = cenario(2);
        var or = retiradas.findByOrdemServicoIdOrderByDataGeracaoDesc(c.osId()).getFirst();
        Long orId = or.getId();
        assertTrue(or.isSimulacao());
        verificarMarca(orPdfService.obterPdfArquivado(orId));
        var retirada = new ExecutarOrdemRetiradaRequest();
        retirada.setConferidoPor("TESTE"); retirada.setLevadoPor("TESTE");
        retirada.setAssinaturaConferenteBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
        retirada.setAssinaturaRetiranteBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
        retirada.setAlocacoes(List.of());
        assertThrows(IllegalStateException.class, () -> orService.executarRetirada(orId, retirada));
        var comarca = comarcas.findById(c.comarcaId()).orElseThrow();
        comarca.setFotoVistoriaUrl("/uploads/teste/vistoria.png");
        comarca.setAssinaturaBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
        comarcas.save(comarca);
        comarcaService.avancarParaInfraestrutura(c.comarcaId());
        or = orService.executarRetirada(orId, retirada);
        assertEquals(8, materiais.findById(c.materialId()).orElseThrow().getQuantidadeDisponivel());
        var devolucao = new DevolverOrdemRetiradaRequest();
        devolucao.setDevolvidoPor("TESTE"); devolucao.setRecebidoPor("TESTE");
        devolucao.setAssinaturaRecebimentoBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
        var item = new DevolverOrdemRetiradaRequest.ItemDevolucaoRequest();
        item.setItemId(or.getItens().getFirst().getId()); item.setQuantidadeDevolvida(BigDecimal.ONE);
        devolucao.setItens(List.of(item)); devolucao.setAlocacoes(List.of());
        orService.devolver(orId, devolucao);
        for (var snapshot : orPdfService.listarDocumentos(orId)) {
            assertTrue(snapshot.isSimulacao());
            verificarMarca(orPdfService.obterPdfArquivado(orId, snapshot.getId()));
        }
        assertThrows(IllegalStateException.class, () -> osService.atualizarStatus(c.osId(), StatusOS.CONCLUIDA));
        osService.atualizarChecklist(c.osId(), "{\"atividades\":[{\"nome\":\"TESTE\"}]}");
        assertThrows(IllegalStateException.class, () -> osService.atualizarStatus(c.osId(), StatusOS.AGUARDANDO_VALIDACAO));
        var foto = new EvidenciaFoto();
        foto.setOrdemServico(ordens.findById(c.osId()).orElseThrow());
        foto.setFuncionario(projetos.findById(ordens.findById(c.osId()).orElseThrow().getProjeto().getId()).orElseThrow().getResponsavel());
        foto.setCaminhoArquivo("/uploads/teste/evidencia.png");
        foto.setDataUpload(LocalDateTime.now());
        fotos.save(foto);
        osService.atualizarStatus(c.osId(), StatusOS.AGUARDANDO_VALIDACAO);
        osService.atualizarStatus(c.osId(), StatusOS.CONCLUIDA);
        comarcaService.atualizarQuantidadeAuditada(or.getItens().getFirst().getMaterialItem().getId(), BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class, () -> comarcaService.homologarAsBuilt(c.comarcaId(), null));
        comarcaService.homologarAsBuilt(c.comarcaId(), "Divergência fictícia TESTE para validar justificativa");
        comarcaService.reabrirAsBuilt(c.comarcaId());
        assertEquals("REABERTO_PARA_AJUSTE", comarcas.findById(c.comarcaId()).orElseThrow().getAsBuiltStatus());
        assertTrue(ordens.findById(c.osId()).orElseThrow().isSimulacao());
        comarcaService.homologarAsBuilt(c.comarcaId(), "Divergência fictícia TESTE após reabertura");
        assertThrows(IllegalArgumentException.class, () -> comarcaService.concluirObra(c.comarcaId(), "TESTE"));
        comarcaService.salvarViradaRede(c.comarcaId(), "/uploads/comarcas/virada-rede/teste.png", "Cenário fictício TESTE", true);
        assertThrows(IllegalArgumentException.class, () -> comarcaService.concluirObra(c.comarcaId(), "TESTE"));
        var finalDoc = documentoController.gerarDocumentoVistoria(requisicaoDocumento(c, "ENCERRAMENTO_OS"), c.auth()).getBody();
        Long finalId = finalDoc.getId();
        assinarTres(finalId, c);
        assertEquals("CONCLUIDA", comarcaService.concluirObra(c.comarcaId(), "TESTE").situacao());
        var motivo = new DocumentoInternoController.InvalidacaoDocumentoRequest(); motivo.setMotivo("TESTE");
        assertThrows(IllegalStateException.class, () -> documentoController.invalidarECriarNovaVersao(finalId, motivo, c.auth()));
        assertThrows(IllegalArgumentException.class, () -> comarcaService.reabrirAsBuilt(c.comarcaId()));
        assertTrue(ordens.findById(c.osId()).orElseThrow().isSimulacao());
        assertTrue(documentos.findById(finalId).orElseThrow().isSimulacao());
    }

    @Test void perfisSemPermissaoEUsuarioSemVinculoContinuamBloqueados() throws Exception {
        var c = cenario();
        for (String papel : List.of("ESTOQUE", "AUDITOR")) {
            mvc.perform(post("/api/documentos-internos/vistoria").with(user("TESTE").roles(papel)).with(csrf())
                    .contentType("application/json").content("{\"simulacao\":true}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/ordens-servico").with(user("TESTE").roles("TECNICO")).with(csrf())
                .contentType("application/json").content("{\"simulacao\":true}"))
                .andExpect(status().isForbidden());
        var outsider = new UsuarioAutenticado(99999L, "TESTE EXTERNO", "teste-exterior@example.invalid", "TECNICO");
        var auth = new UsernamePasswordAuthenticationToken(outsider, null, outsider.getAuthorities());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> documentoController.gerarDocumentoVistoria(requisicaoDocumento(c, "VISTORIA_INICIAL_OS"), auth));
    }

    @Test void bancoRecusaConversaoDeSimulacaoEmOficial() {
        var c = cenario();
        em.flush();
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE ordens_servico SET simulacao = false WHERE id = ?", c.osId()));
    }

    private void assinarTres(Long id, Cenario c) throws Exception {
        for (String papel : List.of("TECNICO", "GESTOR_RC", "GERENTE_FORUM")) {
            var request = new DocumentoInternoController.AssinaturaPapelRequest();
            request.setNomeAssinante("TESTE"); request.setAssinaturaBase64(com.poprc.demo.support.AssinaturaTeste.assinaturaTeste());
            assertTrue(documentoController.assinarDocumentoPorPapel(id, papel, request, c.auth()).getBody().isSimulacao());
        }
    }
    private DocumentoInternoController.DocumentoVistoriaRequest requisicaoDocumento(Cenario c, String tipo) {
        var request = new DocumentoInternoController.DocumentoVistoriaRequest();
        request.setComarcaId(c.comarcaId()); request.setTipo(tipo); request.setSimulacao(true);
        request.setConteudoJson("{\"tecnicoResponsavel\":\"Pessoa externa\",\"descricaoServicos\":\"Cenário fictício TESTE\"}");
        request.setRecebidoPor("TESTE FICTÍCIO"); return request;
    }
    private void verificarMarca(byte[] bytes) throws Exception {
        try (var reader = new PdfReader(bytes)) {
            for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                assertTrue(new PdfTextExtractor(reader).getTextFromPage(i).contains(SimulacaoDocumentoService.MARCA));
            }
        }
    }
    private Cenario cenario() {
        return cenario(1);
    }
    private Cenario cenario(int quantidade) {
        String sufixo = UUID.randomUUID().toString();
        var admin = new Funcionario(); admin.setNome("TESTE ADMIN"); admin.setEmail("teste-" + sufixo + "@example.invalid");
        admin.setPerfilAcesso(PerfilAcesso.ADMIN); admin = funcionarios.save(admin);
        var principal = UsuarioAutenticado.de(admin);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        var contrato = new Contrato(); contrato.setContrato("TESTE-" + sufixo); contrato.setCliente("CLIENTE FICTÍCIO TESTE");
        contrato.setVigenciaInicio(LocalDate.now()); contrato.setVigenciaFim(LocalDate.now().plusYears(1)); contrato = contratos.save(contrato);
        var projeto = new Projeto(); projeto.setContrato(contrato); projeto.setResponsavel(admin);
        projeto.setStatus(ProjetoStatus.EM_ANDAMENTO); projeto.setDataInicio(LocalDate.now()); projeto.setDataFim(LocalDate.now().plusMonths(1)); projeto = projetos.save(projeto);
        var comarca = new Comarca(); comarca.setNomeComarca("UNIDADE FICTÍCIA TESTE"); comarca.setProjeto(projeto); comarca = comarcas.save(comarca);
        var material = new Material(); material.setNome("MATERIAL FICTÍCIO TESTE"); material.setPartNumber("TESTE-" + sufixo);
        material.setCategoria("MATERIAL_CONSUMO"); material.setTipoControle(TipoControleEstoque.UNIDADE); material.setUnidadeMedida(UnidadeMedida.UNIDADE);
        material.setQuantidadeDisponivel(10); material.setLocalizacao("ESTOQUE TESTE"); material = estoqueService.cadastrarMaterial(material);
        var request = new CriarOrdemServicoRequest(); request.setSimulacao(true); request.setDescricao("CENÁRIO FICTÍCIO TESTE");
        request.setContratoId(contrato.getId()); request.setProjetoId(projeto.getId());
        request.setDataHoraInicio(LocalDateTime.now().plusDays(1)); request.setDataHoraFim(LocalDateTime.now().plusDays(2)); request.setDeadline(LocalDateTime.now().plusDays(3));
        var item = new CriarOrdemServicoRequest.MaterialPrevistoRequest(); item.setMaterialId(material.getId()); item.setQuantidadePrevista(BigDecimal.valueOf(quantidade)); request.setMateriais(List.of(item));
        var os = osService.criar(request); em.flush();
        return new Cenario(os.getId(), comarca.getId(), material.getId(), auth);
    }
    private record Cenario(Long osId, Long comarcaId, Long materialId, Authentication auth) {}
}
