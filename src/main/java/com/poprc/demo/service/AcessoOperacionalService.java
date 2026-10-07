package com.poprc.demo.service;

import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.EvidenciaFoto;
import com.poprc.demo.model.OrdemServico;
import com.poprc.demo.model.OrdemRetirada;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.EvidenciaFotoRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.repository.OrdemRetiradaRepository;
import com.poprc.demo.repository.MaterialItemRepository;
import com.poprc.demo.repository.ProjetoMembroRepository;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.security.UsuarioAutenticado;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AcessoOperacionalService {

    private final ProjetoRepository projetoRepository;
    private final ProjetoMembroRepository projetoMembroRepository;
    private final OrdemServicoRepository ordemServicoRepository;
    private final ComarcaRepository comarcaRepository;
    private final OrdemRetiradaRepository ordemRetiradaRepository;
    private final EvidenciaFotoRepository evidenciaFotoRepository;
    private final MaterialItemRepository materialItemRepository;

    @Transactional(readOnly = true)
    public List<OrdemServico> filtrarOrdensPermitidas(
            List<OrdemServico> ordens,
            Authentication authentication) {
        UsuarioAutenticado usuario = tecnico(authentication);
        if (usuario == null) return ordens;
        Set<Long> projetosPermitidos = projetosPermitidos(usuario.getFuncionarioId());
        return ordens.stream()
                .filter(ordem -> ordem.getProjeto() != null
                        && projetosPermitidos.contains(ordem.getProjeto().getId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Comarca> filtrarComarcasPermitidas(
            List<Comarca> comarcas,
            Authentication authentication) {
        UsuarioAutenticado usuario = tecnico(authentication);
        if (usuario == null) return comarcas;
        Set<Long> projetosPermitidos = projetosPermitidos(usuario.getFuncionarioId());
        return comarcas.stream()
                .filter(comarca -> comarca.getProjeto() != null
                        && projetosPermitidos.contains(comarca.getProjeto().getId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public void garantirAcessoOrdem(Long ordemServicoId, Authentication authentication) {
        UsuarioAutenticado usuario = tecnico(authentication);
        if (usuario == null) return;
        OrdemServico ordem = ordemServicoRepository.findById(ordemServicoId)
                .orElseThrow(() -> new IllegalArgumentException("Ordem de serviço não encontrada."));
        if (ordem.getProjeto() == null
                || !projetosPermitidos(usuario.getFuncionarioId()).contains(ordem.getProjeto().getId())) {
            throw acessoNegado();
        }
    }

    @Transactional(readOnly = true)
    public void garantirAcessoComarca(Long comarcaId, Authentication authentication) {
        UsuarioAutenticado usuario = tecnico(authentication);
        if (usuario == null) return;
        Comarca comarca = comarcaRepository.findById(comarcaId)
                .orElseThrow(() -> new IllegalArgumentException("Obra não encontrada."));
        garantirAcessoProjeto(comarca.getProjeto() != null ? comarca.getProjeto().getId() : null, usuario);
    }

    @Transactional(readOnly = true)
    public void garantirAcessoMaterialPrevisto(Long materialItemId, Authentication authentication) {
        UsuarioAutenticado usuario = tecnico(authentication);
        if (usuario == null) return;
        var material = materialItemRepository.findById(materialItemId)
                .orElseThrow(() -> new IllegalArgumentException("Material previsto não encontrado."));
        garantirAcessoProjeto(material.getComarca() != null && material.getComarca().getProjeto() != null
                ? material.getComarca().getProjeto().getId() : null, usuario);
    }

    @Transactional(readOnly = true)
    public List<OrdemRetirada> filtrarOrdensRetiradaPermitidas(
            List<OrdemRetirada> ordens,
            Authentication authentication) {
        UsuarioAutenticado usuario = tecnico(authentication);
        if (usuario == null) return ordens;
        Set<Long> projetosPermitidos = projetosPermitidos(usuario.getFuncionarioId());
        return ordens.stream()
                .filter(ordem -> {
                    Long projetoId = projetoId(ordem);
                    return projetoId != null && projetosPermitidos.contains(projetoId);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public void garantirAcessoOrdemRetirada(Long ordemRetiradaId, Authentication authentication) {
        UsuarioAutenticado usuario = tecnico(authentication);
        if (usuario == null) return;
        OrdemRetirada ordem = ordemRetiradaRepository.findById(ordemRetiradaId)
                .orElseThrow(() -> new IllegalArgumentException("Ordem de retirada não encontrada."));
        garantirAcessoProjeto(projetoId(ordem), usuario);
    }

    @Transactional(readOnly = true)
    public void garantirAcessoEvidencia(Long evidenciaId, Authentication authentication) {
        UsuarioAutenticado usuario = tecnico(authentication);
        if (usuario == null) return;
        EvidenciaFoto evidencia = evidenciaFotoRepository.findById(evidenciaId)
                .orElseThrow(() -> new IllegalArgumentException("Evidência fotográfica não encontrada."));
        Long projetoId = evidencia.getOrdemServico() != null
                && evidencia.getOrdemServico().getProjeto() != null
                        ? evidencia.getOrdemServico().getProjeto().getId()
                        : null;
        garantirAcessoProjeto(projetoId, usuario);
    }

    private Set<Long> projetosPermitidos(Long funcionarioId) {
        Set<Long> projetos = projetoMembroRepository.findByFuncionarioId(funcionarioId).stream()
                .filter(membro -> membro.getProjeto() != null)
                .map(membro -> membro.getProjeto().getId())
                .collect(Collectors.toSet());
        projetoRepository.findByResponsavelId(funcionarioId).stream()
                .map(projeto -> projeto.getId())
                .forEach(projetos::add);
        return projetos;
    }

    private UsuarioAutenticado tecnico(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof UsuarioAutenticado usuario)
                || !"TECNICO".equals(usuario.getPerfil())) {
            return null;
        }
        return usuario;
    }

    private Long projetoId(OrdemRetirada ordem) {
        if (ordem.getOrdemServico() != null && ordem.getOrdemServico().getProjeto() != null) {
            return ordem.getOrdemServico().getProjeto().getId();
        }
        return ordem.getComarca() != null && ordem.getComarca().getProjeto() != null
                ? ordem.getComarca().getProjeto().getId()
                : null;
    }

    private void garantirAcessoProjeto(Long projetoId, UsuarioAutenticado usuario) {
        if (projetoId == null || !projetosPermitidos(usuario.getFuncionarioId()).contains(projetoId)) {
            throw acessoNegado();
        }
    }

    private AccessDeniedException acessoNegado() {
        return new AccessDeniedException("Esta OS não está atribuída à equipe do técnico autenticado.");
    }
}
