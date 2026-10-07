package com.poprc.demo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.MaterialItem;
import com.poprc.demo.model.MovimentacaoEstoque;
import com.poprc.demo.model.TipoMovimentacao;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.HistoricoHomologacaoAsBuiltRepository;
import com.poprc.demo.repository.MaterialItemRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.MovimentacaoEstoqueRepository;
import com.poprc.demo.service.ComarcaService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class AsBuiltConcorrenciaIntegrationTest {
    enum Ajuste { QUANTIDADE_AUDITADA, MATERIAIS_FALTANTES, TIMELINE }

    @Autowired private ComarcaService comarcaService;
    @Autowired private ComarcaRepository comarcas;
    @Autowired private MaterialRepository materiaisEstoque;
    @Autowired private MaterialItemRepository materiais;
    @Autowired private MovimentacaoEstoqueRepository movimentacoes;
    @Autowired private HistoricoHomologacaoAsBuiltRepository historico;
    @Autowired private TransactionTemplate transacoes;
    @Autowired private JdbcTemplate jdbc;

    private Long comarcaId;
    private Long materialId;
    private Long estoqueId;
    private Long movimentacaoId;

    @BeforeEach
    void preparar() {
        Comarca comarca = new Comarca();
        comarca.setNomeComarca("Obra sintética concorrente");
        comarca.setSituacao("EM_ANDAMENTO");
        comarca = comarcas.saveAndFlush(comarca);
        comarcaId = comarca.getId();

        Material estoque = new Material();
        estoque.setNome("Material sintético concorrente");
        estoque.setPartNumber("CONCORRENTE-" + UUID.randomUUID());
        estoque = materiaisEstoque.saveAndFlush(estoque);
        estoqueId = estoque.getId();

        MaterialItem item = new MaterialItem();
        item.setComarca(comarca);
        item.setMaterial(estoque);
        item.setNomeMaterial(estoque.getNome());
        item.setQuantidadePrevista(BigDecimal.ONE);
        item.setQuantidadeAuditada(BigDecimal.ONE);
        item.setEstoqueBaixado(true);
        materialId = materiais.saveAndFlush(item).getId();

        MovimentacaoEstoque retirada = new MovimentacaoEstoque();
        retirada.setComarca(comarca);
        retirada.setMaterial(estoque);
        retirada.setTipo(TipoMovimentacao.RETIRADA_OR);
        retirada.setQuantidade(1);
        retirada.setDataMovimentacao(LocalDateTime.now());
        movimentacaoId = movimentacoes.saveAndFlush(retirada).getId();
    }

    @AfterEach
    void removerDadosSinteticos() {
        historico.deleteAll(historico.findByComarcaIdOrderByIdDesc(comarcaId));
        movimentacoes.deleteById(movimentacaoId);
        materiais.deleteById(materialId);
        comarcas.deleteById(comarcaId);
        materiaisEstoque.deleteById(estoqueId);
    }

    @ParameterizedTest
    @EnumSource(Ajuste.class)
    void ajusteIniciadoDuranteHomologacaoNaoAlteraRegistroHomologado(Ajuste tipo) throws Exception {
        var controle = new ControleConcorrencia();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> homologacao = executor.submit(() -> transacoes.executeWithoutResult(status -> {
                comarcaService.homologarAsBuilt(comarcaId, null);
                controle.primeiraOperacaoConcluida.countDown();
                controle.aguardarLiberacao();
            }));
            controle.aguardarPrimeira();
            Future<Throwable> ajuste = executor.submit(() -> capturarErro(() -> {
                switch (tipo) {
                    case QUANTIDADE_AUDITADA -> comarcaService.atualizarQuantidadeAuditada(
                            materialId, BigDecimal.valueOf(2));
                    case MATERIAIS_FALTANTES -> comarcaService.atualizarMateriaisFaltantes(
                            comarcaId, true, java.util.List.of(materialId), "Falta sintética");
                    case TIMELINE -> comarcaService.atualizarTimelineMaterial(
                            materialId, LocalDateTime.of(2026, 9, 29, 9, 0), null, null);
                }
            }));
            aguardarLockDaComarca(ajuste);
            controle.liberar.countDown();
            homologacao.get(10, TimeUnit.SECONDS);
            assertThat(ajuste.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reabra o As-Built");
            MaterialItem persistido = materiais.findById(materialId).orElseThrow();
            assertThat(persistido.getQuantidadeAuditada()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(persistido.getMaterialFaltante()).isFalse();
            assertThat(persistido.getDataHoraSolicitacao()).isNull();
            Comarca obra = comarcas.findById(comarcaId).orElseThrow();
            assertThat(obra.getAsBuiltStatus()).isEqualTo("HOMOLOGADO");
            assertThat(obra.getFaltouMaterial()).isFalse();
        } finally {
            controle.liberar.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void homologacaoEsperaAjusteEValidaQuantidadeAtualizada() throws Exception {
        var controle = new ControleConcorrencia();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> ajuste = executor.submit(() -> transacoes.executeWithoutResult(status -> {
                comarcaService.atualizarQuantidadeAuditada(materialId, BigDecimal.valueOf(2));
                controle.primeiraOperacaoConcluida.countDown();
                controle.aguardarLiberacao();
            }));
            controle.aguardarPrimeira();
            Future<Throwable> homologacao = executor.submit(() -> capturarErro(() ->
                    comarcaService.homologarAsBuilt(comarcaId, "Justificativa sintética")));
            aguardarLockDaComarca(homologacao);
            controle.liberar.countDown();
            ajuste.get(10, TimeUnit.SECONDS);
            assertThat(homologacao.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Conciliação pendente");
            assertThat(materiais.findById(materialId).orElseThrow().getQuantidadeAuditada())
                    .isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(comarcas.findById(comarcaId).orElseThrow().getAsBuiltStatus()).isNotEqualTo("HOMOLOGADO");
        } finally {
            controle.liberar.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void reaberturaNaoSobrescreveEncerramentoConcorrente() throws Exception {
        Comarca inicial = comarcas.findById(comarcaId).orElseThrow();
        inicial.setAsBuiltStatus("HOMOLOGADO");
        inicial.setSituacao("AS_BUILT_HOMOLOGADO");
        comarcas.saveAndFlush(inicial);

        var controle = new ControleConcorrencia();
        var executor = Executors.newFixedThreadPool(2);
        try {
            // Reproduz o trecho protegido por lock do encerramento formal.
            Future<?> encerramento = executor.submit(() -> transacoes.executeWithoutResult(status -> {
                Comarca travada = comarcas.findByIdForUpdate(comarcaId).orElseThrow();
                travada.setSituacao("CONCLUIDA");
                travada.setDataConclusao(LocalDateTime.now());
                comarcas.saveAndFlush(travada);
                controle.primeiraOperacaoConcluida.countDown();
                controle.aguardarLiberacao();
            }));
            controle.aguardarPrimeira();
            Future<Throwable> reabertura = executor.submit(() -> capturarErro(() ->
                    comarcaService.reabrirAsBuilt(comarcaId)));
            aguardarLockDaComarca(reabertura);
            controle.liberar.countDown();
            encerramento.get(10, TimeUnit.SECONDS);
            assertThat(reabertura.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("concluída não pode");
            Comarca finalizada = comarcas.findById(comarcaId).orElseThrow();
            assertThat(finalizada.getSituacao()).isEqualTo("CONCLUIDA");
            assertThat(finalizada.getDataConclusao()).isNotNull();
            assertThat(finalizada.getAsBuiltStatus()).isEqualTo("HOMOLOGADO");
        } finally {
            controle.liberar.countDown();
            executor.shutdownNow();
        }
    }

    private void aguardarLockDaComarca(Future<?> segundaOperacao) {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < limite) {
            Boolean aguardando = jdbc.queryForObject("""
                    select exists (
                      select 1 from pg_stat_activity
                      where datname = current_database()
                        and pid <> pg_backend_pid()
                        and wait_event_type = 'Lock'
                        and query ilike '%comarcas%'
                    )
                    """, Boolean.class);
            if (Boolean.TRUE.equals(aguardando)) return;
            assertThat(segundaOperacao.isDone()).as("segunda operação deve esperar a trava da comarca").isFalse();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("A segunda operação não aguardou a trava da comarca no PostgreSQL.");
    }

    private Throwable capturarErro(Runnable operacao) {
        try {
            operacao.run();
            return null;
        } catch (RuntimeException erro) {
            return erro;
        }
    }

    private static final class ControleConcorrencia {
        private final CountDownLatch primeiraOperacaoConcluida = new CountDownLatch(1);
        private final CountDownLatch liberar = new CountDownLatch(1);

        void aguardarPrimeira() throws InterruptedException {
            assertThat(primeiraOperacaoConcluida.await(10, TimeUnit.SECONDS)).isTrue();
        }

        void aguardarLiberacao() {
            try {
                assertThat(liberar.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(erro);
            }
        }
    }
}
