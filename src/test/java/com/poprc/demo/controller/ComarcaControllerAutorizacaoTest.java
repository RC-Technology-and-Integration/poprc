package com.poprc.demo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.model.ProjetoMembro;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.DocumentoInternoRepository;
import com.poprc.demo.repository.EvidenciaFotoRepository;
import com.poprc.demo.repository.HistoricoHomologacaoAsBuiltRepository;
import com.poprc.demo.repository.MaterialItemRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.MovimentacaoEstoqueRepository;
import com.poprc.demo.repository.OrdemRetiradaRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.repository.ProjetoMembroRepository;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import com.poprc.demo.service.AcessoOperacionalService;
import com.poprc.demo.service.ArquivamentoService;
import com.poprc.demo.service.ComarcaService;
import com.poprc.demo.service.FluxoOrdemServicoService;
import com.poprc.demo.service.SaldoLocalService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

class ComarcaControllerAutorizacaoTest {
    private static final Long OBRA_ID = 42L;

    private ComarcaRepository comarcaRepository;
    private ProjetoRepository projetoRepository;
    private ProjetoMembroRepository projetoMembroRepository;
    private ComarcaController controller;
    private Comarca obra;

    enum Operacao { PROGRESSO, PENDENCIAS, ATUALIZACAO }

    @BeforeEach
    void setUp() {
        comarcaRepository = mock(ComarcaRepository.class);
        projetoRepository = mock(ProjetoRepository.class);
        projetoMembroRepository = mock(ProjetoMembroRepository.class);
        AcessoOperacionalService acesso = new AcessoOperacionalService(
                projetoRepository, projetoMembroRepository,
                mock(OrdemServicoRepository.class), comarcaRepository,
                mock(OrdemRetiradaRepository.class), mock(EvidenciaFotoRepository.class),
                mock(MaterialItemRepository.class));
        ComarcaService comarcaService = new ComarcaService(
                comarcaRepository, mock(HistoricoHomologacaoAsBuiltRepository.class),
                mock(MaterialItemRepository.class), mock(MaterialRepository.class),
                mock(MovimentacaoEstoqueRepository.class), mock(OrdemRetiradaRepository.class),
                mock(DocumentoInternoRepository.class), projetoRepository,
                mock(FluxoOrdemServicoService.class), mock(SaldoLocalService.class),
                mock(com.poprc.demo.service.SimulacaoDocumentoService.class));
        ReflectionTestUtils.setField(comarcaService, "entityManager", mock(EntityManager.class));
        controller = new ComarcaController(comarcaService, comarcaRepository,
                mock(ArquivamentoService.class), acesso);

        Projeto projeto = new Projeto();
        projeto.setId(20L);
        obra = new Comarca();
        obra.setId(OBRA_ID);
        obra.setProjeto(projeto);
        obra.setPercentualConcluido(new BigDecimal("10"));
        obra.setSituacao("EM_ANDAMENTO");
        obra.setPendencias("original");
        when(comarcaRepository.findById(OBRA_ID)).thenReturn(Optional.of(obra));
        when(comarcaRepository.findByIdForUpdate(OBRA_ID)).thenReturn(Optional.of(obra));
        when(comarcaRepository.save(obra)).thenReturn(obra);
    }

    @ParameterizedTest
    @EnumSource(Operacao.class)
    void tecnicoDeOutraEquipeNaoAlteraObra(Operacao operacao) {
        when(projetoRepository.findByResponsavelId(7L)).thenReturn(List.of(projeto(10L)));

        assertThatThrownBy(() -> executar(operacao, autenticacao("TECNICO")))
                .isInstanceOf(AccessDeniedException.class);

        verify(comarcaRepository, never()).save(obra);
        assertThat(obra.getPercentualConcluido()).isEqualByComparingTo("10");
        assertThat(obra.getSituacao()).isEqualTo("EM_ANDAMENTO");
        assertThat(obra.getPendencias()).isEqualTo("original");
    }

    @ParameterizedTest
    @EnumSource(Operacao.class)
    void obraInexistenteContinuaRetornandoNaoEncontrado(Operacao operacao) {
        when(comarcaRepository.findById(OBRA_ID)).thenReturn(Optional.empty());

        assertThat(executar(operacao, autenticacao("TECNICO")).getStatusCode().value()).isEqualTo(404);
        verify(comarcaRepository, never()).save(obra);
    }

    @ParameterizedTest
    @EnumSource(Operacao.class)
    void tecnicoMembroDaEquipePodeAlterarObra(Operacao operacao) {
        ProjetoMembro membro = new ProjetoMembro();
        membro.setProjeto(obra.getProjeto());
        when(projetoMembroRepository.findByFuncionarioId(7L)).thenReturn(List.of(membro));

        assertThat(executar(operacao, autenticacao("TECNICO")).getStatusCode().value()).isEqualTo(200);
        verify(comarcaRepository).save(obra);
    }

    @ParameterizedTest
    @EnumSource(Operacao.class)
    void tecnicoResponsavelPodeAlterarObra(Operacao operacao) {
        when(projetoRepository.findByResponsavelId(7L)).thenReturn(List.of(obra.getProjeto()));

        assertThat(executar(operacao, autenticacao("TECNICO")).getStatusCode().value()).isEqualTo(200);
        verify(comarcaRepository).save(obra);
    }

    @ParameterizedTest
    @EnumSource(Operacao.class)
    void supervisorMantemAcesso(Operacao operacao) {
        assertThat(executar(operacao, autenticacao("SUPERVISOR_TECNICO")).getStatusCode().value())
                .isEqualTo(200);
        verify(comarcaRepository).save(obra);
    }

    private ResponseEntity<Comarca> executar(Operacao operacao, Authentication authentication) {
        return switch (operacao) {
            case PROGRESSO -> {
                ComarcaController.ProgressoRequest request = new ComarcaController.ProgressoRequest();
                request.setPercentualConcluido(new BigDecimal("50"));
                request.setSituacao("EM_EXECUCAO");
                yield controller.atualizarProgresso(OBRA_ID, request, authentication);
            }
            case PENDENCIAS -> {
                ComarcaController.PendenciasRequest request = new ComarcaController.PendenciasRequest();
                request.setPendencias("nova");
                yield controller.atualizarPendencias(OBRA_ID, request, authentication);
            }
            case ATUALIZACAO -> {
                ComarcaController.AtualizacaoComarcaRequest request = new ComarcaController.AtualizacaoComarcaRequest();
                request.setPercentualConcluido(new BigDecimal("50"));
                request.setPendencias("nova");
                yield controller.atualizar(OBRA_ID, request, authentication);
            }
        };
    }

    private Authentication autenticacao(String perfil) {
        UsuarioAutenticado usuario = new UsuarioAutenticado(7L, "Tecnico", null, perfil, "CPF_SENHA", false);
        return UsernamePasswordAuthenticationToken.authenticated(usuario, null, usuario.getAuthorities());
    }

    private Projeto projeto(Long id) {
        Projeto projeto = new Projeto();
        projeto.setId(id);
        return projeto;
    }
}
