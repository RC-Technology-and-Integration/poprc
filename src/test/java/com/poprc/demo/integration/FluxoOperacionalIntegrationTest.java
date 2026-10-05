package com.poprc.demo.integration;

import com.poprc.demo.dto.CriarOrdemServicoRequest;
import com.poprc.demo.dto.DevolverOrdemRetiradaRequest;
import com.poprc.demo.dto.ExecutarOrdemRetiradaRequest;
import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.Contrato;
import com.poprc.demo.model.Funcionario;
import com.poprc.demo.model.DocumentoInterno;
import com.poprc.demo.model.EvidenciaFoto;
import com.poprc.demo.model.Faturamento;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.MaterialItem;
import com.poprc.demo.model.MovimentacaoEstoque;
import com.poprc.demo.model.OrdemRetirada;
import com.poprc.demo.model.OrdemRetiradaItem;
import com.poprc.demo.model.OrdemServico;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.model.ProjetoStatus;
import com.poprc.demo.model.StatusOS;
import com.poprc.demo.model.SituacaoFaturamento;
import com.poprc.demo.model.TipoControleEstoque;
import com.poprc.demo.model.TipoMovimentacao;
import com.poprc.demo.model.UnidadeMedida;
import com.poprc.demo.model.UnidadeEstoqueRastreavel;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.ContratoRepository;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.repository.DocumentoInternoRepository;
import com.poprc.demo.repository.EvidenciaFotoRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.MovimentacaoEstoqueRepository;
import com.poprc.demo.repository.OrdemRetiradaRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.repository.UnidadeEstoqueRastreavelRepository;
import com.poprc.demo.service.ComarcaService;
import com.poprc.demo.service.EstoqueService;
import com.poprc.demo.service.FaturamentoService;
import com.poprc.demo.service.FluxoOrdemServicoService;
import com.poprc.demo.service.OrdemRetiradaService;
import com.poprc.demo.service.OrdemServicoService;
import com.poprc.demo.service.PendenciaOperacionalService;
import com.poprc.demo.service.UnidadeEstoqueRastreavelService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FluxoOperacionalIntegrationTest {

    private static final String ASSINATURA = "data:image/png;base64,dGVzdGU=";

    @Autowired
    private OrdemServicoService ordemServicoService;
    @Autowired
    private OrdemRetiradaService ordemRetiradaService;
    @Autowired
    private ComarcaService comarcaService;
    @Autowired
    private EstoqueService estoqueService;
    @Autowired
    private FaturamentoService faturamentoService;
    @Autowired
    private FluxoOrdemServicoService fluxoOrdemServicoService;
    @Autowired
    private PendenciaOperacionalService pendenciaOperacionalService;
    @Autowired
    private UnidadeEstoqueRastreavelService unidadeEstoqueRastreavelService;
    @Autowired
    private FuncionarioRepository funcionarioRepository;
    @Autowired
    private ContratoRepository contratoRepository;
    @Autowired
    private ProjetoRepository projetoRepository;
    @Autowired
    private ComarcaRepository comarcaRepository;
    @Autowired
    private MaterialRepository materialRepository;
    @Autowired
    private OrdemRetiradaRepository ordemRetiradaRepository;
    @Autowired
    private OrdemServicoRepository ordemServicoRepository;
    @Autowired
    private DocumentoInternoRepository documentoInternoRepository;
    @Autowired
    private EvidenciaFotoRepository evidenciaFotoRepository;
    @Autowired
    private MovimentacaoEstoqueRepository movimentacaoEstoqueRepository;
    @Autowired
    private UnidadeEstoqueRastreavelRepository unidadeEstoqueRastreavelRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void executaCicloCompletoComRastreabilidadeEHomologacaoDivergente() {
        Cenario cenario = prepararCenario(true);
        OrdemServico os = criarOrdemServico(cenario, BigDecimal.TEN, BigDecimal.ONE);

        assertEquals(cenario.numeroContrato() + " - OS 01", os.getNumeroOs());
        assertEquals(StatusOS.AGUARDANDO_RETIRADA,
                ordemServicoService.atualizarStatus(os.getId(), StatusOS.AGUARDANDO_RETIRADA).getStatus());
        OrdemRetirada or = unicaOrDaOs(os);
        assertEquals(os.getNumeroOs() + " - OR 01", or.getNumeroOr());
        assertEquals("GERADA", or.getStatus());

        Material consumoReservado = materialRepository.findById(cenario.consumoId()).orElseThrow();
        Material ferramentaReservada = materialRepository.findById(cenario.ferramentaId()).orElseThrow();
        assertEquals(10, consumoReservado.getQuantidadeReservada());
        assertEquals(1, ferramentaReservada.getQuantidadeReservada());

        or = ordemRetiradaService.executarRetirada(or.getId(), retiradaAssinada());

        assertEquals("RETIRADA", or.getStatus());
        Long responsavelId = projetoRepository.findById(cenario.projetoId())
                .orElseThrow().getResponsavel().getId();
        assertTrue(pendenciaOperacionalService.listar("TECNICO", responsavelId).stream()
                .anyMatch(item -> os.getId().equals(item.ordemServicoId())
                        && "REGISTRAR_EXECUCAO".equals(item.tipo())));
        assertNotNull(or.getDataRetirada());
        assertEquals("Conferente Teste", or.getConferidoPor());
        assertEquals("Técnico Teste", or.getLevadoPor());
        assertEquals(90, materialRepository.findById(cenario.consumoId()).orElseThrow().getQuantidadeDisponivel());
        assertEquals(4, materialRepository.findById(cenario.ferramentaId()).orElseThrow().getQuantidadeDisponivel());

        List<MovimentacaoEstoque> aposRetirada = movimentacaoEstoqueRepository
                .findByComarcaIdOrderByDataMovimentacaoDesc(cenario.comarcaId());
        assertEquals(2, contarMovimentos(aposRetirada, TipoMovimentacao.RETIRADA_OR));
        assertTrue(aposRetirada.stream()
                .filter(movimento -> TipoMovimentacao.RETIRADA_OR.equals(movimento.getTipo()))
                .allMatch(movimento -> "Conferente Teste".equals(movimento.getAutorizadoPor())
                        && "Técnico Teste".equals(movimento.getRetiradoPor())
                        && movimento.getOrdemServico() != null
                        && movimento.getOrdemRetirada() != null
                        && movimento.getCustoUnitario().signum() > 0
                        && !Boolean.TRUE.equals(movimento.getCustoEstimado())));
        assertEquals(0, new BigDecimal("150.0000").compareTo(aposRetirada.stream()
                .filter(movimento -> TipoMovimentacao.RETIRADA_OR.equals(movimento.getTipo()))
                .map(MovimentacaoEstoque::getValorTotalMovimentacao)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));

        or = ordemRetiradaService.devolver(or.getId(), devolucao(or, BigDecimal.valueOf(3), BigDecimal.ONE));

        assertEquals("DEVOLVIDA", or.getStatus());
        assertNotNull(or.getDataDevolucao());
        assertEquals(93, materialRepository.findById(cenario.consumoId()).orElseThrow().getQuantidadeDisponivel());
        assertEquals(5, materialRepository.findById(cenario.ferramentaId()).orElseThrow().getQuantidadeDisponivel());

        List<MovimentacaoEstoque> aposDevolucao = movimentacaoEstoqueRepository
                .findByComarcaIdOrderByDataMovimentacaoDesc(cenario.comarcaId());
        assertEquals(2, contarMovimentos(aposDevolucao, TipoMovimentacao.DEVOLUCAO_OR));
        assertTrue(aposDevolucao.stream()
                .filter(movimento -> TipoMovimentacao.DEVOLUCAO_OR.equals(movimento.getTipo()))
                .allMatch(movimento -> "Almoxarife Teste".equals(movimento.getAutorizadoPor())
                        && "Técnico Teste".equals(movimento.getRetiradoPor())
                        && movimento.getCustoUnitario().signum() > 0
                        && !Boolean.TRUE.equals(movimento.getCustoEstimado())));
        assertEquals(0, new BigDecimal("115.0000").compareTo(aposDevolucao.stream()
                .filter(movimento -> TipoMovimentacao.DEVOLUCAO_OR.equals(movimento.getTipo()))
                .map(MovimentacaoEstoque::getValorTotalMovimentacao)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));

        MaterialItem consumo = itemDaOr(or, cenario.consumoId());
        MaterialItem ferramenta = itemDaOr(or, cenario.ferramentaId());
        submeterEConcluirOs(os, cenario.projetoId());
        comarcaService.atualizarQuantidadeAuditada(consumo.getId(), BigDecimal.valueOf(7));
        comarcaService.atualizarQuantidadeAuditada(ferramenta.getId(), BigDecimal.ONE);
        for (String justificativaInvalida : List.of("", "   ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> comarcaService.homologarAsBuilt(cenario.comarcaId(), justificativaInvalida));
        }
        assertThrows(IllegalArgumentException.class,
                () -> comarcaService.homologarAsBuilt(cenario.comarcaId(), null));
        assertEquals("DIVERGENTE", comarcaRepository.findById(cenario.comarcaId()).orElseThrow().getAsBuiltStatus());
        Comarca homologacaoLegada = comarcaRepository.findById(cenario.comarcaId()).orElseThrow();
        homologacaoLegada.setAsBuiltStatus("HOMOLOGADO_COM_DIVERGENCIA");
        comarcaRepository.saveAndFlush(homologacaoLegada);
        assertEquals(true, comarcaService.buscarAuditoriaPorComarca(cenario.comarcaId())
                .get("homologacaoLegadaSemJustificativa"));
        assertEquals(true, comarcaService.reabrirAsBuilt(cenario.comarcaId())
                .get("homologacaoLegadaSemJustificativa"));
        Map<String, Object> auditoria = comarcaService.homologarAsBuilt(
                cenario.comarcaId(), "  Consumo auditado abaixo do previsto  ");

        assertEquals("HOMOLOGADO_COM_DIVERGENCIA", auditoria.get("asBuiltStatus"));
        assertEquals("Consumo auditado abaixo do previsto",
                ((Map<?, ?>) ((List<?>) auditoria.get("homologacoes")).get(0)).get("justificativa"));
        assertThrows(IllegalStateException.class,
                () -> comarcaService.homologarAsBuilt(cenario.comarcaId(), "Tentativa de alterar"));
        assertThrows(IllegalArgumentException.class,
                () -> comarcaService.adicionarMaterialPrevisto(
                        cenario.comarcaId(), cenario.consumoId(), "Cabo extra", BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> comarcaService.adicionarItemAdicional(
                        cenario.comarcaId(), cenario.consumoId(), "Cabo extra", BigDecimal.ONE));
        assertEquals("HOMOLOGADO_COM_DIVERGENCIA",
                comarcaRepository.findById(cenario.comarcaId()).orElseThrow().getAsBuiltStatus());
        assertEquals("HOMOLOGADO_COM_DIVERGENCIA",
                projetoRepository.findById(cenario.projetoId()).orElseThrow().getAsBuiltStatus());
        Map<String, Object> reaberta = comarcaService.reabrirAsBuilt(cenario.comarcaId());
        assertEquals("REABERTO_PARA_AJUSTE", reaberta.get("asBuiltStatus"));
        assertEquals("Consumo auditado abaixo do previsto",
                ((Map<?, ?>) ((List<?>) reaberta.get("homologacoes")).get(0)).get("justificativa"));
    }

    @Test
    void encerraObraEProjetoQuandoTodoOCicloEstaConcluido() {
        Cenario cenario = prepararCenario(false);
        OrdemServico os = criarOrdemServico(cenario, BigDecimal.valueOf(4), null);
        OrdemRetirada ordemRetirada = ordemRetiradaService.executarRetirada(
                unicaOrDaOs(os).getId(), retiradaAssinada());
        comarcaService.salvarViradaRede(
                cenario.comarcaId(), "/uploads/comarcas/virada-rede/prova.png",
                "Ping e conectividade validados", true);
        assertEquals(0, BigDecimal.valueOf(90).compareTo(
                comarcaRepository.findById(cenario.comarcaId()).orElseThrow().getPercentualConcluido()));

        IllegalStateException saltoInvalido = assertThrows(IllegalStateException.class,
                () -> ordemServicoService.atualizarStatus(os.getId(), StatusOS.CONCLUIDA));
        assertTrue(saltoInvalido.getMessage().contains("Transição de status inválida"));

        ordemServicoService.atualizarChecklist(os.getId(),
                "{\"atividades\":[{\"id\":1,\"nome\":\"Execução validada\"}]}");
        IllegalStateException semEvidencia = assertThrows(IllegalStateException.class,
                () -> ordemServicoService.atualizarStatus(os.getId(), StatusOS.AGUARDANDO_VALIDACAO));
        assertTrue(semEvidencia.getMessage().contains("evidência fotográfica"));

        registrarEvidencia(os, cenario.projetoId());
        ordemServicoService.atualizarStatus(os.getId(), StatusOS.AGUARDANDO_VALIDACAO);
        IllegalStateException checklistEncerrado = assertThrows(IllegalStateException.class,
                () -> ordemServicoService.atualizarChecklist(os.getId(), "alteração tardia"));
        assertTrue(checklistEncerrado.getMessage().contains("checklist"));
        ordemServicoService.atualizarStatus(os.getId(), StatusOS.EM_EXECUCAO);
        ordemServicoService.atualizarChecklist(os.getId(),
                "{\"atividades\":[{\"id\":1,\"nome\":\"Relatório corrigido\"}]}");
        ordemServicoService.atualizarStatus(os.getId(), StatusOS.AGUARDANDO_VALIDACAO);
        ordemServicoService.atualizarStatus(os.getId(), StatusOS.CONCLUIDA);

        assertEquals(StatusOS.AGUARDANDO_DEVOLUCAO,
                ordemServicoService.atualizarStatus(os.getId(), StatusOS.AGUARDANDO_DEVOLUCAO).getStatus());
        ordemRetirada = ordemRetiradaService.devolver(
                ordemRetirada.getId(), devolucao(ordemRetirada, BigDecimal.ONE, null));
        assertEquals(StatusOS.AGUARDANDO_AUDITORIA,
                ordemServicoService.atualizarStatus(os.getId(), StatusOS.AGUARDANDO_AUDITORIA).getStatus());

        MaterialItem consumo = itemDaOr(ordemRetirada, cenario.consumoId());
        comarcaService.atualizarQuantidadeAuditada(consumo.getId(), BigDecimal.valueOf(3));
        comarcaService.homologarAsBuilt(cenario.comarcaId(), "Consumo parcial registrado");
        assertEquals(StatusOS.AGUARDANDO_ENCERRAMENTO,
                ordemServicoService.atualizarStatus(os.getId(), StatusOS.AGUARDANDO_ENCERRAMENTO).getStatus());
        criarDocumentoFinalRegistrado(cenario.comarcaId());

        ComarcaService.EncerramentoObraResultado resultado = comarcaService
                .concluirObra(cenario.comarcaId(), "Gestor Teste");

        assertEquals("CONCLUIDA", resultado.situacao());
        assertEquals("Gestor Teste", resultado.concluidaPor());
        assertEquals(1, resultado.ordensRetiradaDevolvidas());
        assertNotNull(resultado.concluidaEm());
        assertEquals(ProjetoStatus.CONCLUIDO,
                projetoRepository.findById(cenario.projetoId()).orElseThrow().getStatus());
        assertEquals("HOMOLOGADO_COM_DIVERGENCIA",
                projetoRepository.findById(cenario.projetoId()).orElseThrow().getAsBuiltStatus());
        assertEquals("CONCLUIDA",
                comarcaRepository.findById(cenario.comarcaId()).orElseThrow().getSituacao());
        assertEquals(0, BigDecimal.valueOf(100).compareTo(
                comarcaRepository.findById(cenario.comarcaId()).orElseThrow().getPercentualConcluido()));
        assertEquals(StatusOS.CONCLUIDA,
                ordemServicoService.atualizarStatus(os.getId(), StatusOS.CONCLUIDA).getStatus());
        assertTrue(fluxoOrdemServicoService.listarHistorico(os.getId()).stream()
                .anyMatch(item -> "OBRA_ENCERRADA".equals(item.getEvento())));

        ComarcaService.EncerramentoObraResultado resumoPersistido = comarcaService
                .buscarEncerramento(cenario.comarcaId());
        assertEquals(resultado, resumoPersistido);

        Faturamento medicao = new Faturamento();
        medicao.setServicosExecutados("Execucao e encerramento da obra");
        medicao.setValorMedicao(new BigDecimal("1500.00"));
        Faturamento faturamento = faturamentoService.registrarMedicao(
                medicao, cenario.contratoId(), cenario.projetoId(), os.getId());
        faturamento = faturamentoService.emitirNotaFiscal(
                faturamento.getId(), "NF-TESTE-001", LocalDate.now().plusDays(30));
        assertEquals(SituacaoFaturamento.FATURADO, faturamento.getSituacao());
        assertEquals(StatusOS.FATURADA,
                ordemServicoRepository.findById(os.getId()).orElseThrow().getStatus());
        assertEquals(SituacaoFaturamento.PAGO,
                faturamentoService.darBaixaPagamento(faturamento.getId()).getSituacao());
    }

    @Test
    void bloqueiaEncerramentoSemDocumentoFinalRegistrado() {
        Cenario cenario = prepararCenario(false);
        OrdemServico os = criarOrdemServico(cenario, BigDecimal.valueOf(2), null);
        OrdemRetirada ordemRetirada = ordemRetiradaService.executarRetirada(
                unicaOrDaOs(os).getId(), retiradaAssinada());
        ordemRetirada = ordemRetiradaService.devolver(
                ordemRetirada.getId(), devolucao(ordemRetirada, BigDecimal.ZERO, null));
        submeterEConcluirOs(os, cenario.projetoId());
        MaterialItem consumo = itemDaOr(ordemRetirada, cenario.consumoId());
        comarcaService.atualizarQuantidadeAuditada(consumo.getId(), BigDecimal.valueOf(2));
        comarcaService.homologarAsBuilt(cenario.comarcaId(), null);
        comarcaService.salvarViradaRede(
                cenario.comarcaId(), "/uploads/comarcas/virada-rede/prova.png",
                "Conectividade validada", true);

        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> comarcaService.concluirObra(cenario.comarcaId(), "Gestor Teste"));

        assertTrue(erro.getMessage().contains("documento final"));
        assertEquals(ProjetoStatus.EM_ANDAMENTO,
                projetoRepository.findById(cenario.projetoId()).orElseThrow().getStatus());
    }

    @Test
    void bloqueiaViradaDeRedeAntesDaRetiradaDaOr() {
        Cenario cenario = prepararCenario(false);
        OrdemServico os = criarOrdemServico(cenario, BigDecimal.valueOf(2), null);

        assertEquals(StatusOS.AGUARDANDO_RETIRADA, os.getStatus());

        IllegalArgumentException avancarSemRetirada = assertThrows(IllegalArgumentException.class,
                () -> comarcaService.avancarParaInfraestrutura(cenario.comarcaId()));
        assertTrue(avancarSemRetirada.getMessage().contains("retirada"));

        IllegalArgumentException concluirSemRetirada = assertThrows(IllegalArgumentException.class,
                () -> comarcaService.salvarViradaRede(
                        cenario.comarcaId(), "/uploads/comarcas/virada-rede/prova.png",
                        "Conectividade validada", true));
        assertTrue(concluirSemRetirada.getMessage().contains("retirada"));

        ordemRetiradaService.executarRetirada(unicaOrDaOs(os).getId(), retiradaAssinada());

        Comarca viradaLiberada = comarcaService.avancarParaInfraestrutura(cenario.comarcaId());
        assertEquals(3, viradaLiberada.getEtapaAtual());
        assertEquals("VIRADA_DE_REDE", viradaLiberada.getSituacao());
    }

    @Test
    void bloqueiaSubstituicaoDaFotoDaViradaDeRedePorTexto() {
        Cenario cenario = prepararCenario(false);
        String prova = "/uploads/comarcas/virada-rede/prova.png";
        comarcaService.salvarViradaRede(cenario.comarcaId(), prova, "Checklist inicial", false);

        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> comarcaService.salvarViradaRede(
                        cenario.comarcaId(), "Conectividade validada por texto", "Checklist final", false));

        assertTrue(erro.getMessage().contains("foto enviada"));
        assertEquals(prova, comarcaRepository.findById(cenario.comarcaId()).orElseThrow()
                .getViradaRedeProvasFuncionamento());
    }

    @Test
    void bloqueiaRetiradaSemAsDuasAssinaturasSemBaixarEstoque() {
        Cenario cenario = prepararCenario(false);
        OrdemServico os = criarOrdemServico(cenario, BigDecimal.valueOf(4), null);
        OrdemRetirada or = unicaOrDaOs(os);

        ExecutarOrdemRetiradaRequest request = retiradaAssinada();
        request.setAssinaturaRetiranteBase64(null);

        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> ordemRetiradaService.executarRetirada(or.getId(), request));

        assertTrue(erro.getMessage().contains("Assinatura de quem levou"));
        assertEquals("GERADA", ordemRetiradaRepository.findById(or.getId()).orElseThrow().getStatus());
        Material material = materialRepository.findById(cenario.consumoId()).orElseThrow();
        assertEquals(100, material.getQuantidadeDisponivel());
        assertEquals(4, material.getQuantidadeReservada());
        assertFalse(movimentacaoEstoqueRepository.findByComarcaIdOrderByDataMovimentacaoDesc(cenario.comarcaId())
                .stream().anyMatch(movimento -> TipoMovimentacao.RETIRADA_OR.equals(movimento.getTipo())));
    }

    @Test
    void bloqueiaRetiradaAntesDaConclusaoDaVistoria() {
        Cenario cenario = prepararCenario(false);
        OrdemServico os = ordemServicoService.criar(novaRequisicaoOs(cenario));
        OrdemRetirada or = unicaOrDaOs(os);
        int saldoAntes = materialRepository.findById(cenario.consumoId())
                .orElseThrow().getQuantidadeDisponivel();

        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> ordemRetiradaService.executarRetirada(or.getId(), retiradaAssinada()));

        assertTrue(erro.getMessage().contains("Conclua a vistoria"));
        assertEquals(StatusOS.AGUARDANDO_VISTORIA,
                ordemServicoService.atualizarStatus(os.getId(), StatusOS.AGUARDANDO_VISTORIA).getStatus());
        assertEquals(saldoAntes, materialRepository.findById(cenario.consumoId())
                .orElseThrow().getQuantidadeDisponivel());
    }

    @Test
    void bloqueiaDevolucaoQuandoFerramentaNaoRetornaIntegralmente() {
        Cenario cenario = prepararCenario(true);
        OrdemServico os = criarOrdemServico(cenario, BigDecimal.valueOf(2), BigDecimal.ONE);
        OrdemRetirada or = ordemRetiradaService.executarRetirada(unicaOrDaOs(os).getId(), retiradaAssinada());

        DevolverOrdemRetiradaRequest request = devolucao(or, BigDecimal.ZERO, BigDecimal.ZERO);
        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> ordemRetiradaService.devolver(or.getId(), request));

        assertTrue(erro.getMessage().contains("Ferramentas devem retornar obrigatoriamente"));
    }

    @Test
    void bloqueiaCriacaoDeOsComPrazoAnteriorAoFimPlanejado() {
        Cenario cenario = prepararCenario(false);
        CriarOrdemServicoRequest request = novaRequisicaoOs(cenario);
        request.setDeadline(request.getDataHoraFim().minusMinutes(1));

        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> ordemServicoService.criar(request));

        assertTrue(erro.getMessage().contains("Prazo limite"));
    }

    @Test
    void bloqueiaMaterialDuplicadoNaCriacaoDaOs() {
        Cenario cenario = prepararCenario(false);
        CriarOrdemServicoRequest request = novaRequisicaoOs(cenario);
        request.setMateriais(List.of(
                materialPrevisto(cenario.consumoId(), BigDecimal.ONE),
                materialPrevisto(cenario.consumoId(), BigDecimal.TEN)));

        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> ordemServicoService.criar(request));

        assertTrue(erro.getMessage().contains("mais de uma vez"));
    }

    @Test
    void registraEResolvePendenciaCriticaDeMaterialFaltante() {
        Cenario cenario = prepararCenario(false);
        OrdemServico os = criarOrdemServico(cenario, BigDecimal.valueOf(4), null);
        MaterialItem materialPrevisto = itemDaOr(unicaOrDaOs(os), cenario.consumoId());

        IllegalArgumentException semMaterial = assertThrows(IllegalArgumentException.class,
                () -> comarcaService.atualizarMateriaisFaltantes(
                        cenario.comarcaId(), true, List.of(), "Cabo insuficiente em campo"));
        assertTrue(semMaterial.getMessage().contains("Selecione ao menos um material"));

        comarcaService.atualizarMateriaisFaltantes(
                cenario.comarcaId(), true, List.of(materialPrevisto.getId()), "Cabo insuficiente em campo");

        assertTrue(pendenciaOperacionalService.listar("ESTOQUE", null).stream()
                .anyMatch(item -> os.getId().equals(item.ordemServicoId())
                        && "MATERIAL_FALTANTE".equals(item.tipo())
                        && "CRITICA".equals(item.prioridade())));
        Comarca comFalta = comarcaRepository.findById(cenario.comarcaId()).orElseThrow();
        assertTrue(comFalta.getFaltouMaterial());
        assertEquals("Cabo insuficiente em campo", comFalta.getDescricaoMaterialFaltante());

        comarcaService.atualizarMateriaisFaltantes(cenario.comarcaId(), false, List.of(), null);

        assertFalse(pendenciaOperacionalService.listar("ESTOQUE", null).stream()
                .anyMatch(item -> os.getId().equals(item.ordemServicoId())
                        && "MATERIAL_FALTANTE".equals(item.tipo())));
        Comarca resolvida = comarcaRepository.findById(cenario.comarcaId()).orElseThrow();
        assertFalse(resolvida.getFaltouMaterial());
        assertEquals(null, resolvida.getDescricaoMaterialFaltante());
        assertEquals(null, resolvida.getPendencias());
    }

    @Test
    void permiteMultiplasOrsSequenciaisEBloqueiaOrSobreposta() {
        Cenario cenario = prepararCenario(false);
        OrdemServico os = criarOrdemServico(cenario, BigDecimal.valueOf(4), null);
        OrdemRetirada primeiraOr = unicaOrDaOs(os);

        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> ordemRetiradaService.criarAdicionalParaOs(os.getId(), "Gestor Teste"));
        assertTrue(erro.getMessage().contains("OR ativa"));

        primeiraOr = ordemRetiradaService.executarRetirada(primeiraOr.getId(), retiradaAssinada());
        ordemRetiradaService.devolver(primeiraOr.getId(), devolucao(primeiraOr, BigDecimal.ONE, null));

        OrdemRetirada segundaOr = ordemRetiradaService.criarAdicionalParaOs(os.getId(), "Gestor Teste");
        assertEquals(os.getNumeroOs() + " - OR 02", segundaOr.getNumeroOr());
        assertEquals("GERADA", segundaOr.getStatus());
        assertEquals(2, ordemRetiradaRepository.findByOrdemServicoIdOrderByDataGeracaoDesc(os.getId()).size());
    }

    @Test
    void impedeExecucaoDuplicadaDaMesmaOrSemBaixarEstoqueNovamente() {
        Cenario cenario = prepararCenario(false);
        OrdemServico os = criarOrdemServico(cenario, BigDecimal.valueOf(4), null);
        OrdemRetirada ordemRetirada = unicaOrDaOs(os);

        ordemRetiradaService.executarRetirada(ordemRetirada.getId(), retiradaAssinada());
        int saldoDepoisDaPrimeiraRetirada = materialRepository.findById(cenario.consumoId())
                .orElseThrow().getQuantidadeDisponivel();

        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> ordemRetiradaService.executarRetirada(ordemRetirada.getId(), retiradaAssinada()));

        assertTrue(erro.getMessage().contains("não está disponível"));
        assertEquals(saldoDepoisDaPrimeiraRetirada,
                materialRepository.findById(cenario.consumoId()).orElseThrow().getQuantidadeDisponivel());
        assertEquals(1, contarMovimentos(
                movimentacaoEstoqueRepository.findByComarcaIdOrderByDataMovimentacaoDesc(cenario.comarcaId()),
                TipoMovimentacao.RETIRADA_OR));
    }

    @Test
    void impedeDevolucaoDuplicadaDaMesmaOrSemCreditarEstoqueNovamente() {
        Cenario cenario = prepararCenario(false);
        OrdemServico os = criarOrdemServico(cenario, BigDecimal.valueOf(4), null);
        OrdemRetirada ordemRetirada = unicaOrDaOs(os);

        ordemRetirada = ordemRetiradaService.executarRetirada(ordemRetirada.getId(), retiradaAssinada());
        DevolverOrdemRetiradaRequest request = devolucao(ordemRetirada, BigDecimal.ONE, null);
        ordemRetiradaService.devolver(ordemRetirada.getId(), request);
        int saldoDepoisDaPrimeiraDevolucao = materialRepository.findById(cenario.consumoId())
                .orElseThrow().getQuantidadeDisponivel();

        Long ordemRetiradaId = ordemRetirada.getId();
        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> ordemRetiradaService.devolver(ordemRetiradaId, request));

        assertTrue(erro.getMessage().contains("precisa estar retirada"));
        assertEquals(saldoDepoisDaPrimeiraDevolucao,
                materialRepository.findById(cenario.consumoId()).orElseThrow().getQuantidadeDisponivel());
        assertEquals(1, contarMovimentos(
                movimentacaoEstoqueRepository.findByComarcaIdOrderByDataMovimentacaoDesc(cenario.comarcaId()),
                TipoMovimentacao.DEVOLUCAO_OR));
    }

    @Test
    void rastreiaRetiradaEDevolucaoParcialDeBobina() throws Exception {
        Cenario cenario = prepararCenario(false);
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        Material bobina = new Material();
        bobina.setNome("Cabo em Bobina " + sufixo);
        bobina.setPartNumber("BOB-" + sufixo);
        bobina.setCategoria("MATERIAL_CONSUMO");
        bobina.setTipoControle(TipoControleEstoque.BOBINA);
        bobina.setUnidadeMedida(UnidadeMedida.METRO);
        bobina.setMetragemDisponivel(BigDecimal.ZERO);
        bobina.setLocalizacao("Estoque Central");
        bobina = estoqueService.cadastrarMaterial(bobina);

        UnidadeEstoqueRastreavel unidade = unidadeEstoqueRastreavelService.cadastrar(
                bobina.getId(), "BOBINA-" + sufixo, BigDecimal.valueOf(100), "Teste de integração", null);

        CriarOrdemServicoRequest request = novaRequisicaoOs(cenario);
        request.setMateriais(List.of(materialPrevisto(bobina.getId(), BigDecimal.valueOf(30))));
        OrdemServico os = ordemServicoService.criar(request);
        liberarVistoria(cenario.comarcaId());
        OrdemRetirada ordemRetirada = unicaOrDaOs(os);
        OrdemRetiradaItem item = ordemRetirada.getItens().getFirst();

        ExecutarOrdemRetiradaRequest retirada = retiradaAssinada();
        ExecutarOrdemRetiradaRequest.AlocacaoRequest alocacao = new ExecutarOrdemRetiradaRequest.AlocacaoRequest();
        alocacao.setItemId(item.getId());
        alocacao.setUnidadeRastreavelId(unidade.getId());
        alocacao.setMetragem(BigDecimal.valueOf(30));
        alocacao.setEvidenciaFotoBase64(fotoValida());
        alocacao.setEvidenciaFotoNome("retirada.png");
        retirada.setAlocacoes(List.of(alocacao));
        ordemRetirada = ordemRetiradaService.executarRetirada(ordemRetirada.getId(), retirada);

        assertEquals(0, BigDecimal.valueOf(70).compareTo(
                unidadeEstoqueRastreavelRepository.findById(unidade.getId()).orElseThrow().getMetragemAtual()));
        assertEquals(0, BigDecimal.valueOf(70).compareTo(
                materialRepository.findById(bobina.getId()).orElseThrow().getMetragemDisponivel()));
        var alocacaoRetirada = ordemRetirada.getItens().getFirst().getAlocacoes().getFirst();
        assertNotNull(alocacaoRetirada.getEvidenciaRetiradaPath());
        assertEquals("retirada.png", alocacaoRetirada.getEvidenciaRetiradaNome());
        assertNotNull(alocacaoRetirada.getEvidenciaRetiradaData());
        assertEquals(0, BigDecimal.valueOf(70).compareTo(
                alocacaoRetirada.getMetragemRestanteAposRetirada()));

        DevolverOrdemRetiradaRequest devolucao = new DevolverOrdemRetiradaRequest();
        devolucao.setDevolvidoPor("Técnico Teste");
        devolucao.setRecebidoPor("Almoxarife Teste");
        devolucao.setAssinaturaRecebimentoBase64(ASSINATURA);
        devolucao.setItens(List.of());
        DevolverOrdemRetiradaRequest.AlocacaoDevolucaoRequest retorno =
                new DevolverOrdemRetiradaRequest.AlocacaoDevolucaoRequest();
        retorno.setAlocacaoId(ordemRetirada.getItens().getFirst().getAlocacoes().getFirst().getId());
        retorno.setMetragemDevolvida(BigDecimal.TEN);
        retorno.setEvidenciaFotoBase64(fotoValida());
        retorno.setEvidenciaFotoNome("retorno.png");
        devolucao.setAlocacoes(List.of(retorno));

        ordemRetirada = ordemRetiradaService.devolver(ordemRetirada.getId(), devolucao);

        assertEquals(0, BigDecimal.valueOf(80).compareTo(
                unidadeEstoqueRastreavelRepository.findById(unidade.getId()).orElseThrow().getMetragemAtual()));
        assertEquals(0, BigDecimal.valueOf(80).compareTo(
                materialRepository.findById(bobina.getId()).orElseThrow().getMetragemDisponivel()));
        var alocacaoDevolvida = ordemRetirada.getItens().getFirst().getAlocacoes().getFirst();
        assertNotNull(alocacaoDevolvida.getEvidenciaDevolucaoPath());
        assertEquals("retorno.png", alocacaoDevolvida.getEvidenciaDevolucaoNome());
        assertNotNull(alocacaoDevolvida.getEvidenciaDevolucaoData());
        assertEquals(0, BigDecimal.valueOf(80).compareTo(
                alocacaoDevolvida.getMetragemRestanteAposDevolucao()));
    }

    private String fotoValida() throws Exception {
        BufferedImage imagem = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(imagem, "png", output);
        return "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private Cenario prepararCenario(boolean comFerramenta) {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);

        Funcionario responsavel = new Funcionario();
        responsavel.setNome("Responsável " + sufixo);
        responsavel.setFuncao("Técnico");
        responsavel = funcionarioRepository.save(responsavel);

        Contrato contrato = new Contrato();
        contrato.setCliente("Cliente Integração");
        contrato.setContrato("Contrato INT-" + sufixo);
        contrato.setVigenciaInicio(LocalDate.now());
        contrato.setVigenciaFim(LocalDate.now().plusYears(1));
        contrato = contratoRepository.save(contrato);

        Projeto projeto = new Projeto();
        projeto.setContrato(contrato);
        projeto.setResponsavel(responsavel);
        projeto.setStatus(ProjetoStatus.EM_ANDAMENTO);
        projeto.setDataInicio(LocalDate.now());
        projeto.setDataFim(LocalDate.now().plusMonths(1));
        projeto = projetoRepository.save(projeto);

        Comarca comarca = new Comarca();
        comarca.setNomeComarca("Comarca Integração " + sufixo);
        comarca.setProjeto(projeto);
        comarca.setPercentualConcluido(BigDecimal.ZERO);
        comarca = comarcaRepository.save(comarca);

        Material consumo = novoMaterial("Cabo Integração", "CAB-" + sufixo, "MATERIAL_CONSUMO", 100);
        Material ferramenta = comFerramenta
                ? novoMaterial("Alicate Integração", "ALI-" + sufixo, "FERRAMENTA", 5)
                : null;

        entityManager.flush();
        entityManager.clear();
        return new Cenario(contrato.getId(), projeto.getId(), comarca.getId(), consumo.getId(),
                ferramenta != null ? ferramenta.getId() : null, contrato.getContrato());
    }

    private void criarDocumentoFinalRegistrado(Long comarcaId) {
        DocumentoInterno documento = new DocumentoInterno();
        documento.setComarca(comarcaRepository.findById(comarcaId).orElseThrow());
        documento.setTipo("ENCERRAMENTO_OS");
        documento.setStatus("REGISTRADO");
        documento.setConteudoJson("{}");
        documento.setCriadoPor("Gestor Teste");
        documento.setRecebidoPor("Gerente Teste");
        documento.setDataGeracao(LocalDateTime.now());
        documento.setPdfPath("/uploads/documentos/encerramento-teste.pdf");
        documentoInternoRepository.save(documento);
    }

    private void submeterEConcluirOs(OrdemServico ordemServico, Long projetoId) {
        ordemServicoService.atualizarStatus(ordemServico.getId(), StatusOS.EM_EXECUCAO);
        ordemServicoService.atualizarChecklist(ordemServico.getId(),
                "{\"atividades\":[{\"id\":1,\"nome\":\"Execução validada\"}]}");
        registrarEvidencia(ordemServico, projetoId);
        ordemServicoService.atualizarStatus(ordemServico.getId(), StatusOS.AGUARDANDO_VALIDACAO);
        ordemServicoService.atualizarStatus(ordemServico.getId(), StatusOS.CONCLUIDA);
    }

    private void registrarEvidencia(OrdemServico ordemServico, Long projetoId) {
        EvidenciaFoto evidencia = new EvidenciaFoto();
        evidencia.setCaminhoArquivo("/uploads/evidencias/teste-" + UUID.randomUUID() + ".png");
        evidencia.setLatitude("-5.800000");
        evidencia.setLongitude("-35.200000");
        evidencia.setDataUpload(LocalDateTime.now());
        evidencia.setOrdemServico(ordemServico);
        evidencia.setFuncionario(projetoRepository.findById(projetoId).orElseThrow().getResponsavel());
        evidenciaFotoRepository.save(evidencia);
    }

    private Material novoMaterial(String nome, String partNumber, String categoria, int quantidade) {
        Material material = new Material();
        material.setNome(nome);
        material.setPartNumber(partNumber);
        material.setCategoria(categoria);
        material.setTipoControle(TipoControleEstoque.UNIDADE);
        material.setUnidadeMedida(UnidadeMedida.UNIDADE);
        material.setQuantidadeDisponivel(quantidade);
        material.setCustoMedio("FERRAMENTA".equals(categoria)
                ? new BigDecimal("100.0000")
                : new BigDecimal("5.0000"));
        material.setLocalizacao("Estoque Central");
        return estoqueService.cadastrarMaterial(material);
    }

    private OrdemServico criarOrdemServico(Cenario cenario, BigDecimal quantidadeConsumo,
            BigDecimal quantidadeFerramenta) {
        CriarOrdemServicoRequest request = novaRequisicaoOs(cenario);

        CriarOrdemServicoRequest.MaterialPrevistoRequest consumo = materialPrevisto(
                cenario.consumoId(), quantidadeConsumo);
        if (quantidadeFerramenta != null) {
            request.setMateriais(List.of(consumo,
                    materialPrevisto(cenario.ferramentaId(), quantidadeFerramenta)));
        } else {
            request.setMateriais(List.of(consumo));
        }
        OrdemServico ordem = ordemServicoService.criar(request);
        liberarVistoria(cenario.comarcaId());
        return ordem;
    }

    private void liberarVistoria(Long comarcaId) {
        Comarca comarca = comarcaRepository.findById(comarcaId).orElseThrow();
        comarca.setFotoVistoriaUrl("/uploads/teste/vistoria.png");
        comarca.setAssinaturaBase64(ASSINATURA);
        comarcaRepository.save(comarca);
        comarcaService.avancarParaInfraestrutura(comarcaId);
    }

    private CriarOrdemServicoRequest novaRequisicaoOs(Cenario cenario) {
        CriarOrdemServicoRequest request = new CriarOrdemServicoRequest();
        request.setContratoId(cenario.contratoId());
        request.setProjetoId(cenario.projetoId());
        request.setDescricao("Execução do cenário de integração");
        LocalDateTime inicio = LocalDateTime.now().plusDays(1);
        request.setDataHoraInicio(inicio);
        request.setDataHoraFim(inicio.plusHours(8));
        request.setDeadline(inicio.plusDays(1));
        request.setMateriais(List.of(materialPrevisto(cenario.consumoId(), BigDecimal.ONE)));
        return request;
    }

    private CriarOrdemServicoRequest.MaterialPrevistoRequest materialPrevisto(Long materialId,
            BigDecimal quantidade) {
        CriarOrdemServicoRequest.MaterialPrevistoRequest item =
                new CriarOrdemServicoRequest.MaterialPrevistoRequest();
        item.setMaterialId(materialId);
        item.setQuantidadePrevista(quantidade);
        return item;
    }

    private OrdemRetirada unicaOrDaOs(OrdemServico os) {
        List<OrdemRetirada> ordens = ordemRetiradaRepository
                .findByOrdemServicoIdOrderByDataGeracaoDesc(os.getId());
        assertEquals(1, ordens.size());
        return ordens.getFirst();
    }

    private ExecutarOrdemRetiradaRequest retiradaAssinada() {
        ExecutarOrdemRetiradaRequest request = new ExecutarOrdemRetiradaRequest();
        request.setConferidoPor("Conferente Teste");
        request.setLevadoPor("Técnico Teste");
        request.setAssinaturaConferenteBase64(ASSINATURA);
        request.setAssinaturaRetiranteBase64(ASSINATURA);
        request.setAlocacoes(List.of());
        return request;
    }

    private DevolverOrdemRetiradaRequest devolucao(OrdemRetirada or, BigDecimal consumo,
            BigDecimal ferramenta) {
        DevolverOrdemRetiradaRequest request = new DevolverOrdemRetiradaRequest();
        request.setDevolvidoPor("Técnico Teste");
        request.setRecebidoPor("Almoxarife Teste");
        request.setAssinaturaRecebimentoBase64(ASSINATURA);
        request.setAlocacoes(List.of());
        request.setItens(or.getItens().stream()
                .map(item -> itemDevolucao(item, "FERRAMENTA".equals(item.getCategoria()) ? ferramenta : consumo))
                .toList());
        return request;
    }

    private DevolverOrdemRetiradaRequest.ItemDevolucaoRequest itemDevolucao(OrdemRetiradaItem item,
            BigDecimal quantidade) {
        DevolverOrdemRetiradaRequest.ItemDevolucaoRequest devolucao =
                new DevolverOrdemRetiradaRequest.ItemDevolucaoRequest();
        devolucao.setItemId(item.getId());
        devolucao.setQuantidadeDevolvida(quantidade);
        return devolucao;
    }

    private MaterialItem itemDaOr(OrdemRetirada or, Long materialId) {
        return or.getItens().stream()
                .filter(item -> item.getMaterial().getId().equals(materialId))
                .map(OrdemRetiradaItem::getMaterialItem)
                .findFirst()
                .orElseThrow();
    }

    private long contarMovimentos(List<MovimentacaoEstoque> movimentos, TipoMovimentacao tipo) {
        return movimentos.stream().filter(movimento -> tipo.equals(movimento.getTipo())).count();
    }

    private record Cenario(Long contratoId, Long projetoId, Long comarcaId, Long consumoId,
            Long ferramentaId, String numeroContrato) {
    }
}
