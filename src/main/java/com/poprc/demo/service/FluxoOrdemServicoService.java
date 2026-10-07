package com.poprc.demo.service;

import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.HistoricoStatusOS;
import com.poprc.demo.model.OrdemServico;
import com.poprc.demo.model.StatusOS;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.EvidenciaFotoRepository;
import com.poprc.demo.repository.HistoricoStatusOSRepository;
import com.poprc.demo.repository.OrdemRetiradaRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FluxoOrdemServicoService {

    private final OrdemServicoRepository ordemServicoRepository;
    private final HistoricoStatusOSRepository historicoRepository;
    private final EvidenciaFotoRepository evidenciaFotoRepository;
    private final ComarcaRepository comarcaRepository;
    private final OrdemRetiradaRepository ordemRetiradaRepository;

    @Transactional
    public OrdemServico iniciar(OrdemServico ordem, String responsavel) {
        ordem.setStatus(StatusOS.AGUARDANDO_VISTORIA);
        OrdemServico salva = ordemServicoRepository.save(ordem);
        registrar(salva, null, StatusOS.AGUARDANDO_VISTORIA, "OS_CRIADA", responsavel);
        return salva;
    }

    @Transactional
    public OrdemServico transicionarPorUsuario(Long ordemId, StatusOS destino, String responsavel) {
        OrdemServico ordem = buscarAtiva(ordemId);
        StatusOS atual = statusAtual(ordem);

        // Compatibilidade com clientes antigos: aprovar o relatório enviava CONCLUIDA.
        if (atual == StatusOS.AGUARDANDO_VALIDACAO
                && (destino == StatusOS.CONCLUIDA || destino == StatusOS.AGUARDANDO_DEVOLUCAO)) {
            destino = proximoStatusAposValidacao(ordemId);
        }
        validarTransicao(atual, destino);
        if (atual != destino && (destino == StatusOS.AGUARDANDO_ENCERRAMENTO
                || destino == StatusOS.CONCLUIDA)) {
            throw new IllegalStateException(
                    "A homologação do As-Built e o encerramento da obra devem usar as operações formais.");
        }
        validarRequisitos(ordem, destino);
        return aplicar(ordem, destino, eventoUsuario(atual, destino), responsavel);
    }

    @Transactional
    public OrdemServico registrarVistoriaLiberada(Long ordemId, String responsavel) {
        OrdemServico ordem = buscarAtiva(ordemId);
        StatusOS atual = statusAtual(ordem);
        if (atual == StatusOS.AGUARDANDO_RETIRADA) {
            return ordem;
        }
        if (atual != StatusOS.AGUARDANDO_VISTORIA && atual != StatusOS.ABERTA) {
            throw transicaoInvalida(atual, StatusOS.AGUARDANDO_RETIRADA);
        }
        return aplicar(ordem, StatusOS.AGUARDANDO_RETIRADA, "VISTORIA_LIBERADA", responsavel);
    }

    @Transactional
    public OrdemServico registrarRetirada(Long ordemId, String responsavel) {
        OrdemServico ordem = buscarAtiva(ordemId);
        StatusOS atual = statusAtual(ordem);
        if (atual == StatusOS.EM_EXECUCAO) {
            return ordem;
        }
        if (atual != StatusOS.AGUARDANDO_RETIRADA) {
            throw new IllegalStateException(
                    "Conclua a vistoria antes de executar a retirada de materiais da OS.");
        }
        return aplicar(ordem, StatusOS.EM_EXECUCAO, "MATERIAIS_RETIRADOS", responsavel);
    }

    @Transactional
    public void validarRetiradaPermitida(Long ordemId) {
        OrdemServico ordem = buscarAtiva(ordemId);
        StatusOS atual = statusAtual(ordem);
        if (atual != StatusOS.AGUARDANDO_RETIRADA && atual != StatusOS.EM_EXECUCAO) {
            throw new IllegalStateException(
                    "Conclua a vistoria antes de executar a retirada de materiais da OS.");
        }
    }

    @Transactional
    public OrdemServico registrarDevolucao(Long ordemId, String responsavel) {
        OrdemServico ordem = buscarAtiva(ordemId);
        if (statusAtual(ordem) != StatusOS.AGUARDANDO_DEVOLUCAO || !todasOrdensRetiradaDevolvidas(ordemId)) {
            return ordem;
        }
        return aplicar(ordem, StatusOS.AGUARDANDO_AUDITORIA, "MATERIAIS_DEVOLVIDOS", responsavel);
    }

    @Transactional
    public OrdemServico registrarAsBuiltHomologado(Long ordemId, String responsavel) {
        OrdemServico ordem = buscarAtiva(ordemId);
        StatusOS atual = statusAtual(ordem);
        if (atual == StatusOS.AGUARDANDO_ENCERRAMENTO) {
            return ordem;
        }
        if (atual != StatusOS.AGUARDANDO_AUDITORIA) {
            throw new IllegalStateException(
                    "A OS precisa estar com a devolução concluída e aguardando auditoria para homologar o As-Built.");
        }
        return aplicar(ordem, StatusOS.AGUARDANDO_ENCERRAMENTO, "AS_BUILT_HOMOLOGADO", responsavel);
    }

    @Transactional
    public OrdemServico registrarEncerramento(Long ordemId, String responsavel) {
        OrdemServico ordem = buscarAtiva(ordemId);
        StatusOS atual = statusAtual(ordem);
        if (atual == StatusOS.CONCLUIDA) {
            return ordem;
        }
        if (atual != StatusOS.AGUARDANDO_ENCERRAMENTO) {
            throw transicaoInvalida(atual, StatusOS.CONCLUIDA);
        }
        return aplicar(ordem, StatusOS.CONCLUIDA, "OBRA_ENCERRADA", responsavel);
    }

    @Transactional(readOnly = true)
    public List<HistoricoStatusOS> listarHistorico(Long ordemId) {
        if (!ordemServicoRepository.existsById(ordemId)) {
            throw new IllegalArgumentException("Ordem de Serviço não encontrada.");
        }
        return historicoRepository.findByOrdemServicoIdOrderByRegistradoEmAscIdAsc(ordemId);
    }

    private OrdemServico buscarAtiva(Long ordemId) {
        OrdemServico ordem = ordemServicoRepository.findByIdForUpdate(ordemId)
                .orElseThrow(() -> new IllegalArgumentException("Ordem de Serviço não encontrada."));
        if (Boolean.TRUE.equals(ordem.getArquivado())) {
            throw new IllegalStateException("A Ordem de Serviço está arquivada e não pode ser alterada.");
        }
        return ordem;
    }

    private void validarTransicao(StatusOS atual, StatusOS destino) {
        if (destino == null) {
            throw new IllegalArgumentException("O novo status da OS é obrigatório.");
        }
        if (atual == destino) {
            return;
        }
        boolean valida = switch (atual) {
            case ABERTA -> destino == StatusOS.AGUARDANDO_VISTORIA || destino == StatusOS.EM_EXECUCAO;
            case AGUARDANDO_VISTORIA -> destino == StatusOS.AGUARDANDO_RETIRADA;
            case AGUARDANDO_RETIRADA -> destino == StatusOS.EM_EXECUCAO;
            case EM_EXECUCAO -> destino == StatusOS.AGUARDANDO_VALIDACAO;
            case AGUARDANDO_VALIDACAO -> destino == StatusOS.EM_EXECUCAO
                    || destino == StatusOS.AGUARDANDO_DEVOLUCAO
                    || destino == StatusOS.AGUARDANDO_AUDITORIA;
            case AGUARDANDO_DEVOLUCAO -> destino == StatusOS.AGUARDANDO_AUDITORIA;
            case AGUARDANDO_AUDITORIA -> destino == StatusOS.AGUARDANDO_ENCERRAMENTO;
            case AGUARDANDO_ENCERRAMENTO -> destino == StatusOS.CONCLUIDA;
            case CONCLUIDA -> destino == StatusOS.FATURADA;
            case FATURADA -> false;
        };
        if (!valida) {
            throw transicaoInvalida(atual, destino);
        }
    }

    private void validarRequisitos(OrdemServico ordem, StatusOS destino) {
        if (destino == StatusOS.AGUARDANDO_AUDITORIA
                && !todasOrdensRetiradaDevolvidas(ordem.getId())) {
            throw new IllegalStateException(
                    "Todas as Ordens de Retirada precisam estar devolvidas antes da auditoria.");
        }
        if (destino == StatusOS.AGUARDANDO_VALIDACAO) {
            if (ordem.getChecklist() == null || ordem.getChecklist().isBlank()
                    || ordem.getChecklist().matches("(?s).*\"atividades\"\\s*:\\s*\\[\\s*].*")) {
                throw new IllegalStateException(
                        "Registre ao menos uma atividade no checklist antes de enviar a OS para validação.");
            }
            if (!evidenciaFotoRepository.existsByOrdemServicoId(ordem.getId())) {
                throw new IllegalStateException(
                        "Envie ao menos uma evidência fotográfica antes de enviar a OS para validação.");
            }
        }
        if (destino == StatusOS.FATURADA) {
            Comarca comarca = comarcaRepository.findByOrdemServicoId(ordem.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "A OS precisa estar vinculada a uma obra antes do faturamento."));
            if (!"CONCLUIDA".equals(comarca.getSituacao())) {
                throw new IllegalStateException(
                        "Encerre formalmente a obra antes de marcar a OS como faturada.");
            }
        }
    }

    private OrdemServico aplicar(OrdemServico ordem, StatusOS destino, String evento, String responsavel) {
        StatusOS anterior = statusAtual(ordem);
        if (anterior == destino) {
            return ordem;
        }
        ordem.setStatus(destino);
        OrdemServico salva = ordemServicoRepository.save(ordem);
        registrar(salva, anterior, destino, evento, responsavel);
        return salva;
    }

    private void registrar(OrdemServico ordem, StatusOS anterior, StatusOS novo, String evento,
            String responsavel) {
        HistoricoStatusOS historico = new HistoricoStatusOS();
        historico.setOrdemServico(ordem);
        historico.setStatusAnterior(anterior);
        historico.setStatusNovo(novo);
        historico.setEvento(evento);
        historico.setResponsavel(normalizarResponsavel(responsavel));
        historico.setRegistradoEm(LocalDateTime.now());
        historicoRepository.save(historico);
    }

    private StatusOS proximoStatusAposValidacao(Long ordemId) {
        return todasOrdensRetiradaDevolvidas(ordemId)
                ? StatusOS.AGUARDANDO_AUDITORIA
                : StatusOS.AGUARDANDO_DEVOLUCAO;
    }

    private boolean todasOrdensRetiradaDevolvidas(Long ordemId) {
        var ordens = ordemRetiradaRepository.findByOrdemServicoIdOrderByDataGeracaoDesc(ordemId);
        return !ordens.isEmpty() && ordens.stream().allMatch(or -> "DEVOLVIDA".equals(or.getStatus()));
    }

    private String eventoUsuario(StatusOS atual, StatusOS destino) {
        if (atual == StatusOS.AGUARDANDO_VALIDACAO && destino == StatusOS.EM_EXECUCAO) {
            return "RELATORIO_TECNICO_RECUSADO";
        }
        if (atual == StatusOS.AGUARDANDO_VALIDACAO
                && (destino == StatusOS.AGUARDANDO_DEVOLUCAO
                        || destino == StatusOS.AGUARDANDO_AUDITORIA)) {
            return "RELATORIO_TECNICO_APROVADO";
        }
        if (destino == StatusOS.AGUARDANDO_VALIDACAO) {
            return "RELATORIO_TECNICO_ENVIADO";
        }
        if (destino == StatusOS.FATURADA) {
            return "OS_FATURADA";
        }
        return "STATUS_ATUALIZADO";
    }

    private StatusOS statusAtual(OrdemServico ordem) {
        return ordem.getStatus() != null ? ordem.getStatus() : StatusOS.ABERTA;
    }

    private IllegalStateException transicaoInvalida(StatusOS atual, StatusOS destino) {
        return new IllegalStateException("Transição de status inválida: " + atual + " → " + destino + ".");
    }

    private String normalizarResponsavel(String responsavel) {
        return responsavel == null || responsavel.isBlank() ? "Sistema" : responsavel.trim();
    }
}
