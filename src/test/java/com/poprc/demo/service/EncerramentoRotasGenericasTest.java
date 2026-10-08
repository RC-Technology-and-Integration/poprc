package com.poprc.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.OrdemServico;
import com.poprc.demo.model.StatusOS;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.DocumentoInternoRepository;
import com.poprc.demo.repository.EvidenciaFotoRepository;
import com.poprc.demo.repository.HistoricoHomologacaoAsBuiltRepository;
import com.poprc.demo.repository.HistoricoStatusOSRepository;
import com.poprc.demo.repository.MaterialItemRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.MovimentacaoEstoqueRepository;
import com.poprc.demo.repository.OrdemRetiradaRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.repository.ProjetoRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EncerramentoRotasGenericasTest {

    @Test
    void naoAvancaParaAuditoriaSemDevolucao() {
        assertSaltoProibido(StatusOS.AGUARDANDO_DEVOLUCAO, StatusOS.AGUARDANDO_AUDITORIA);
    }

    @Test
    void naoAvancaParaEncerramentoSemHomologacao() {
        assertSaltoProibido(StatusOS.AGUARDANDO_AUDITORIA, StatusOS.AGUARDANDO_ENCERRAMENTO);
    }

    @Test
    void naoConcluiOsSemDocumentoFinal() {
        assertSaltoProibido(StatusOS.AGUARDANDO_ENCERRAMENTO, StatusOS.CONCLUIDA);
    }

    @Test
    void progressoPermaneceEditavelMasSituacaoConcluidaExigeEncerramentoFormal() {
        ComarcaRepository comarcas = mock(ComarcaRepository.class);
        Comarca obra = new Comarca();
        obra.setId(9L);
        obra.setSituacao("EM_ANDAMENTO");
        when(comarcas.findByIdForUpdate(9L)).thenReturn(Optional.of(obra));
        when(comarcas.save(any(Comarca.class))).thenAnswer(inv -> inv.getArgument(0));
        ComarcaService service = new ComarcaService(comarcas,
                mock(HistoricoHomologacaoAsBuiltRepository.class), mock(MaterialItemRepository.class),
                mock(MaterialRepository.class), mock(MovimentacaoEstoqueRepository.class),
                mock(OrdemRetiradaRepository.class), mock(DocumentoInternoRepository.class),
                mock(ProjetoRepository.class), mock(FluxoOrdemServicoService.class),
                mock(SaldoLocalService.class), mock(SimulacaoDocumentoService.class));
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));

        assertThrows(IllegalStateException.class,
                () -> service.atualizarProgresso(9L, BigDecimal.valueOf(100), "CONCLUIDA"));
        assertEquals("EM_ANDAMENTO", obra.getSituacao());
        service.atualizarProgresso(9L, BigDecimal.valueOf(75), "EM_ANDAMENTO");
        assertEquals(0, obra.getPercentualConcluido().compareTo(BigDecimal.valueOf(75)));
    }

    private void assertSaltoProibido(StatusOS origem, StatusOS destino) {
        OrdemServicoRepository ordens = mock(OrdemServicoRepository.class);
        HistoricoStatusOSRepository historico = mock(HistoricoStatusOSRepository.class);
        EvidenciaFotoRepository evidencias = mock(EvidenciaFotoRepository.class);
        ComarcaRepository comarcas = mock(ComarcaRepository.class);
        OrdemRetiradaRepository retiradas = mock(OrdemRetiradaRepository.class);
        OrdemServico os = new OrdemServico();
        os.setId(1L);
        os.setStatus(origem);
        when(ordens.findByIdForUpdate(1L)).thenReturn(Optional.of(os));
        when(ordens.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));
        when(retiradas.findByOrdemServicoIdOrderByDataGeracaoDesc(1L)).thenReturn(List.of());
        FluxoOrdemServicoService fluxo = new FluxoOrdemServicoService(
                ordens, historico, evidencias, comarcas, retiradas);

        assertThrows(IllegalStateException.class,
                () -> fluxo.transicionarPorUsuario(1L, destino, "Teste"));
        assertEquals(origem, os.getStatus());
    }
}
