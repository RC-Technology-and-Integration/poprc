package com.poprc.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.poprc.demo.model.OrdemServico;
import com.poprc.demo.model.OrdemRetirada;
import com.poprc.demo.model.EvidenciaFoto;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.model.ProjetoMembro;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.EvidenciaFotoRepository;
import com.poprc.demo.repository.MaterialItemRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.repository.OrdemRetiradaRepository;
import com.poprc.demo.repository.ProjetoMembroRepository;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class AcessoOperacionalServiceTest {

    private ProjetoRepository projetoRepository;
    private ProjetoMembroRepository projetoMembroRepository;
    private OrdemServicoRepository ordemServicoRepository;
    private OrdemRetiradaRepository ordemRetiradaRepository;
    private EvidenciaFotoRepository evidenciaFotoRepository;
    private AcessoOperacionalService service;

    @BeforeEach
    void setUp() {
        projetoRepository = mock(ProjetoRepository.class);
        projetoMembroRepository = mock(ProjetoMembroRepository.class);
        ordemServicoRepository = mock(OrdemServicoRepository.class);
        ordemRetiradaRepository = mock(OrdemRetiradaRepository.class);
        evidenciaFotoRepository = mock(EvidenciaFotoRepository.class);
        service = new AcessoOperacionalService(
                projetoRepository,
                projetoMembroRepository,
                ordemServicoRepository,
                mock(ComarcaRepository.class),
                ordemRetiradaRepository,
                evidenciaFotoRepository,
                mock(MaterialItemRepository.class));
    }

    @Test
    void tecnicoRecebeSomenteOrdensDosProjetosEmQueParticipa() {
        Projeto permitido = projeto(10L);
        Projeto proibido = projeto(20L);
        when(projetoRepository.findByResponsavelId(7L)).thenReturn(List.of(permitido));

        List<OrdemServico> resultado = service.filtrarOrdensPermitidas(
                List.of(ordem(1L, permitido), ordem(2L, proibido)),
                autenticacao("TECNICO"));

        assertThat(resultado).extracting(OrdemServico::getId).containsExactly(1L);
    }

    @Test
    void tecnicoNaoAbreOrdemDeProjetoNaoAtribuido() {
        Projeto proibido = projeto(20L);
        when(ordemServicoRepository.findById(2L)).thenReturn(Optional.of(ordem(2L, proibido)));
        when(projetoRepository.findByResponsavelId(7L)).thenReturn(List.of(projeto(10L)));

        assertThatThrownBy(() -> service.garantirAcessoOrdem(2L, autenticacao("TECNICO")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void tecnicoAdicionalDaEquipePodeAcessarOrdemDoProjeto() {
        Projeto permitido = projeto(10L);
        ProjetoMembro membro = new ProjetoMembro();
        membro.setProjeto(permitido);
        when(projetoMembroRepository.findByFuncionarioId(7L)).thenReturn(List.of(membro));

        List<OrdemServico> resultado = service.filtrarOrdensPermitidas(
                List.of(ordem(1L, permitido), ordem(2L, projeto(20L))),
                autenticacao("TECNICO"));

        assertThat(resultado).extracting(OrdemServico::getId).containsExactly(1L);
    }

    @Test
    void supervisorMantemVisaoOperacionalCompleta() {
        List<OrdemServico> ordens = List.of(ordem(1L, projeto(10L)), ordem(2L, projeto(20L)));

        assertThat(service.filtrarOrdensPermitidas(ordens, autenticacao("SUPERVISOR_TECNICO")))
                .isSameAs(ordens);
    }

    @Test
    void tecnicoRecebeSomenteOrdensRetiradaDosProjetosDaEquipe() {
        Projeto permitido = projeto(10L);
        ProjetoMembro membro = new ProjetoMembro();
        membro.setProjeto(permitido);
        when(projetoMembroRepository.findByFuncionarioId(7L)).thenReturn(List.of(membro));

        List<OrdemRetirada> resultado = service.filtrarOrdensRetiradaPermitidas(
                List.of(ordemRetirada(1L, permitido), ordemRetirada(2L, projeto(20L))),
                autenticacao("TECNICO"));

        assertThat(resultado).extracting(OrdemRetirada::getId).containsExactly(1L);
    }

    @Test
    void tecnicoNaoAbrePdfDeOrdemRetiradaDeOutroProjeto() {
        when(ordemRetiradaRepository.findById(2L))
                .thenReturn(Optional.of(ordemRetirada(2L, projeto(20L))));

        assertThatThrownBy(() -> service.garantirAcessoOrdemRetirada(2L, autenticacao("TECNICO")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void tecnicoDaEquipePodeAbrirArquivoDeEvidenciaDaOrdem() {
        Projeto permitido = projeto(10L);
        ProjetoMembro membro = new ProjetoMembro();
        membro.setProjeto(permitido);
        EvidenciaFoto evidencia = new EvidenciaFoto();
        evidencia.setOrdemServico(ordem(1L, permitido));
        when(projetoMembroRepository.findByFuncionarioId(7L)).thenReturn(List.of(membro));
        when(evidenciaFotoRepository.findById(11L)).thenReturn(Optional.of(evidencia));

        service.garantirAcessoEvidencia(11L, autenticacao("TECNICO"));
    }

    @Test
    void tecnicoNaoAbreArquivoDeEvidenciaDeOutroProjeto() {
        EvidenciaFoto evidencia = new EvidenciaFoto();
        evidencia.setOrdemServico(ordem(2L, projeto(20L)));
        when(evidenciaFotoRepository.findById(11L)).thenReturn(Optional.of(evidencia));

        assertThatThrownBy(() -> service.garantirAcessoEvidencia(11L, autenticacao("TECNICO")))
                .isInstanceOf(AccessDeniedException.class);
    }

    private Authentication autenticacao(String perfil) {
        UsuarioAutenticado usuario = new UsuarioAutenticado(
                7L, "Usuario", null, perfil, "CPF_SENHA", false);
        return UsernamePasswordAuthenticationToken.authenticated(
                usuario, null, usuario.getAuthorities());
    }

    private Projeto projeto(Long id) {
        Projeto projeto = new Projeto();
        projeto.setId(id);
        return projeto;
    }

    private OrdemServico ordem(Long id, Projeto projeto) {
        OrdemServico ordem = new OrdemServico();
        ordem.setId(id);
        ordem.setProjeto(projeto);
        return ordem;
    }

    private OrdemRetirada ordemRetirada(Long id, Projeto projeto) {
        OrdemRetirada retirada = new OrdemRetirada();
        retirada.setId(id);
        retirada.setOrdemServico(ordem(id, projeto));
        return retirada;
    }
}
