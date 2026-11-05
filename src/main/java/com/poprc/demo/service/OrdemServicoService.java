package com.poprc.demo.service;

import com.poprc.demo.dto.CriarOrdemServicoRequest;
import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.Contrato;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.MaterialItem;
import com.poprc.demo.model.MaterialProjeto;
import com.poprc.demo.model.OrdemRetirada;
import com.poprc.demo.model.OrdemServico;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.model.StatusOS;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.ContratoRepository;
import com.poprc.demo.repository.MaterialItemRepository;
import com.poprc.demo.repository.MaterialProjetoRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.repository.ProjetoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrdemServicoService {
    @org.springframework.beans.factory.annotation.Autowired
    private ReservaRetiradaV2Guard reservaV2;


    private final OrdemServicoRepository ordemServicoRepository;
    private final ComarcaRepository comarcaRepository;
    private final ContratoRepository contratoRepository;
    private final ProjetoRepository projetoRepository;
    private final MaterialRepository materialRepository;
    private final MaterialProjetoRepository materialProjetoRepository;
    private final MaterialItemRepository materialItemRepository;
    private final ComarcaService comarcaService;
    private final OrdemRetiradaPort ordemRetiradaPort;
    private final FluxoOrdemServicoService fluxoOrdemServicoService;
    private final SimulacaoDocumentoService simulacaoDocumentoService;

    @Transactional
    public OrdemServico criar(CriarOrdemServicoRequest request) {
        if (reservaV2 != null) reservaV2.lock();
        simulacaoDocumentoService.exigirHabilitada(request.isSimulacao());
        if (request.getProjetoId() == null) {
            throw new IllegalArgumentException("Projeto/Comarca alvo é obrigatório para criar a OS.");
        }
        if (request.getContratoId() == null) {
            throw new IllegalArgumentException("Contrato é obrigatório para criar a OS.");
        }
        if (request.getDescricao() == null || request.getDescricao().isBlank()) {
            throw new IllegalArgumentException("Descrição do chamado técnico é obrigatória.");
        }
        validarDatasObrigatorias(request.getDataHoraInicio(), request.getDataHoraFim(), request.getDeadline());
        if (request.getMateriais() == null || request.getMateriais().isEmpty()) {
            throw new IllegalArgumentException("Defina ao menos um material previsto para emitir a OS.");
        }
        validarMateriaisSemDuplicidade(request.getMateriais());

        Contrato contrato = contratoRepository.findByIdForUpdate(request.getContratoId())
                .orElseThrow(() -> new IllegalArgumentException("Contrato não encontrado."));
        Projeto projeto = projetoRepository.findById(request.getProjetoId())
                .orElseThrow(() -> new IllegalArgumentException("Projeto/Comarca alvo não encontrado."));
        if (Boolean.TRUE.equals(contrato.getArquivado())) {
            throw new IllegalStateException("Não é possível emitir OS para um contrato arquivado.");
        }
        if (Boolean.TRUE.equals(projeto.getArquivado())) {
            throw new IllegalStateException("Não é possível emitir OS para um projeto arquivado.");
        }
        if (projeto.getContrato() == null || !contrato.getId().equals(projeto.getContrato().getId())) {
            throw new IllegalArgumentException("O projeto selecionado não pertence ao contrato informado.");
        }
        if (projeto.getResponsavel() == null || projeto.getResponsavel().getId() == null) {
            throw new IllegalArgumentException(
                    "Atribua um funcionário responsável ao projeto antes de emitir a OS.");
        }

        OrdemServico ordemServico = new OrdemServico();
        ordemServico.setSimulacao(request.isSimulacao());
        ordemServico.setNumeroOs(gerarNumeroOs(contrato));
        ordemServico.setContrato(contrato);
        ordemServico.setProjeto(projeto);
        ordemServico.setDescricao(request.getDescricao());
        ordemServico.setDataHoraInicio(request.getDataHoraInicio());
        ordemServico.setDataHoraFim(request.getDataHoraFim());
        ordemServico.setDeadline(request.getDeadline());
        ordemServico.setDataExecucao(request.getDataHoraInicio().toLocalDate());
        ordemServico.setStatus(StatusOS.ABERTA);

        OrdemServico ordemSalva = ordemServicoRepository.save(ordemServico);
        vincularComarcaEPrepararAuditoria(ordemSalva, request.getMateriais());
        return fluxoOrdemServicoService.iniciar(ordemSalva, "Sistema");
    }

    @Transactional
    public OrdemServico atualizarStatus(Long id, StatusOS novoStatus) {
        if (reservaV2 != null) reservaV2.lock();
        return atualizarStatus(id, novoStatus, "Operação manual");
    }

    @Transactional
    public OrdemServico atualizarStatus(Long id, StatusOS novoStatus, String responsavel) {
        if (reservaV2 != null) reservaV2.lock();
        if (!ordemServicoRepository.existsById(id)) {
            return null;
        }
        return fluxoOrdemServicoService.transicionarPorUsuario(id, novoStatus, responsavel);
    }

    public OrdemServico atualizarChecklist(Long id, String checklist) {
        Optional<OrdemServico> ordemOpt = ordemServicoRepository.findById(id);
        if (ordemOpt.isPresent()) {
            OrdemServico ordem = ordemOpt.get();
            validarOrdemAtiva(ordem);
            if (checklistBloqueado(ordem.getStatus())) {
                throw new IllegalStateException(
                        "O checklist não pode ser alterado após o envio da OS para validação.");
            }
            ordem.setChecklist(checklist);
            return ordemServicoRepository.save(ordem);
        }
        return null;
    }

    private void validarOrdemAtiva(OrdemServico ordem) {
        if (Boolean.TRUE.equals(ordem.getArquivado())) {
            throw new IllegalStateException("A Ordem de Serviço está arquivada e não pode ser alterada.");
        }
    }

    private boolean checklistBloqueado(StatusOS status) {
        return status == StatusOS.AGUARDANDO_VALIDACAO
                || status == StatusOS.AGUARDANDO_DEVOLUCAO
                || status == StatusOS.AGUARDANDO_AUDITORIA
                || status == StatusOS.AGUARDANDO_ENCERRAMENTO
                || status == StatusOS.CONCLUIDA
                || status == StatusOS.FATURADA;
    }

    @Transactional
    public Map<String, Object> repararVinculosComarcas() {
        if (reservaV2 != null) reservaV2.lock();
        Map<String, Object> resultado = new HashMap<>();
        int ordensVinculadas = 0;
        int materiaisCriados = 0;
        int materiaisAtualizados = 0;
        int ordensSemProjeto = 0;
        int ordensSemComarca = 0;
        int conflitos = 0;
        List<ReparoComarca> reparos = new ArrayList<>();
        Set<Long> novosVinculos = new HashSet<>();

        for (OrdemServico ordem : ordemServicoRepository.findAll()) {
            if (ordem.getProjeto() == null || ordem.getProjeto().getId() == null) {
                ordensSemProjeto++;
                continue;
            }

            Optional<Comarca> comarcaOpt = comarcaRepository.findByProjetoId(ordem.getProjeto().getId());
            if (comarcaOpt.isEmpty()) {
                ordensSemComarca++;
                continue;
            }

            Comarca comarca = comarcaOpt.get();
            if (comarca.getOrdemServico() != null && !comarca.getOrdemServico().getId().equals(ordem.getId())) {
                conflitos++;
                continue;
            }

            boolean vincular = comarca.getOrdemServico() == null;
            if (vincular && !novosVinculos.add(comarca.getId())) {
                conflitos++;
                continue;
            }

            try {
                if (vincular && reservaV2 != null) {
                    reservaV2.validarVinculoComarca(comarca.getId(), ordem.getId());
                }
                reparos.add(new ReparoComarca(comarca, ordem, vincular,
                        planejarSincronizacao(comarca, ordem.getId())));
            } catch (ResponseStatusException e) {
                throw new IllegalStateException("Reparo não aplicado à OS " + ordem.getId() + ": " + e.getReason(), e);
            }
        }

        // Valida o lote antes de alterar entidades ou inserir previsões com IDENTITY.
        for (ReparoComarca reparo : reparos) {
            if (reparo.vincular()) {
                reparo.comarca().setOrdemServico(reparo.ordem());
                ordensVinculadas++;
            }
            for (ReparoPrevisao previsao : reparo.previsoes()) {
                aplicarPrevisao(reparo.comarca(), previsao);
                if (previsao.existente() == null) {
                    materiaisCriados++;
                } else {
                    materiaisAtualizados++;
                }
            }
            comarcaRepository.save(reparo.comarca());
        }

        resultado.put("ordensVinculadas", ordensVinculadas);
        resultado.put("materiaisCriados", materiaisCriados);
        resultado.put("materiaisAtualizados", materiaisAtualizados);
        resultado.put("ordensSemProjeto", ordensSemProjeto);
        resultado.put("ordensSemComarca", ordensSemComarca);
        resultado.put("conflitos", conflitos);
        return resultado;
    }

    private void vincularComarcaEPrepararAuditoria(OrdemServico ordemServico,
            List<CriarOrdemServicoRequest.MaterialPrevistoRequest> materiaisPrevistos) {
        Comarca comarca = comarcaRepository.findByProjetoId(ordemServico.getProjeto().getId())
                .orElseThrow(() -> new IllegalArgumentException("Nenhuma comarca foi encontrada para o projeto informado."));

        if (comarca.getOrdemServico() != null
                && !comarca.getOrdemServico().getId().equals(ordemServico.getId())) {
            throw new IllegalArgumentException("Esta comarca já possui uma OS vinculada.");
        }

        comarca.setOrdemServico(ordemServico);
        comarcaRepository.save(comarca);
        cadastrarMateriaisDaOs(comarca, materiaisPrevistos);
        comarca.setMateriais(materialItemRepository.findByComarcaIdOrderByIdAsc(comarca.getId()));
        boolean novaOrV2 = reservaV2 != null && reservaV2.osHabilitada(ordemServico.getId())
                && comarca.getMateriais().stream().allMatch(i -> i.getMaterial() != null
                    && i.getMaterial().getTipoControle() != com.poprc.demo.model.TipoControleEstoque.BOBINA
                    && i.getMaterial().getTipoControle() != com.poprc.demo.model.TipoControleEstoque.ROLO);
        if (!novaOrV2) ordemRetiradaPort.criarParaOrdemServico(ordemServico, comarca, "Sistema");
    }

    private void cadastrarMateriaisDaOs(Comarca comarca,
            List<CriarOrdemServicoRequest.MaterialPrevistoRequest> materiaisPrevistos) {
        List<CriarOrdemServicoRequest.MaterialPrevistoRequest> materiaisOrdenados = materiaisPrevistos.stream()
                .sorted(Comparator.comparing(CriarOrdemServicoRequest.MaterialPrevistoRequest::getMaterialId))
                .toList();
        for (CriarOrdemServicoRequest.MaterialPrevistoRequest materialPrevisto : materiaisOrdenados) {
            if (materialPrevisto.getMaterialId() == null) {
                throw new IllegalArgumentException("Todos os materiais previstos precisam estar vinculados ao estoque.");
            }
            if (materialPrevisto.getQuantidadePrevista() == null
                    || materialPrevisto.getQuantidadePrevista().signum() <= 0) {
                throw new IllegalArgumentException("A quantidade prevista dos materiais deve ser maior que zero.");
            }

            Material material = materialRepository.findById(materialPrevisto.getMaterialId())
                    .orElseThrow(() -> new IllegalArgumentException("Material do estoque não encontrado."));
            comarcaService.adicionarMaterialPrevisto(
                    comarca.getId(),
                    material.getId(),
                    material.getNome(),
                    materialPrevisto.getQuantidadePrevista());
        }
    }

    private void validarDatasObrigatorias(LocalDateTime inicio, LocalDateTime fim, LocalDateTime deadline) {
        if (inicio == null) {
            throw new IllegalArgumentException("Data e Hora de Início é obrigatória.");
        }
        if (fim == null) {
            throw new IllegalArgumentException("Data e Hora de Fim é obrigatória.");
        }
        if (deadline == null) {
            throw new IllegalArgumentException("Prazo limite é obrigatório.");
        }
        if (fim.isBefore(inicio)) {
            throw new IllegalArgumentException("Data e Hora de Fim não pode ser anterior ao início.");
        }
        if (deadline.isBefore(fim)) {
            throw new IllegalArgumentException("Prazo limite não pode ser anterior ao fim planejado da OS.");
        }
    }

    private void validarMateriaisSemDuplicidade(
            List<CriarOrdemServicoRequest.MaterialPrevistoRequest> materiaisPrevistos) {
        Set<Long> materiaisInformados = new java.util.HashSet<>();
        for (CriarOrdemServicoRequest.MaterialPrevistoRequest material : materiaisPrevistos) {
            if (material.getMaterialId() != null && !materiaisInformados.add(material.getMaterialId())) {
                throw new IllegalArgumentException(
                        "O mesmo material não pode ser informado mais de uma vez na OS. Some as quantidades em um único item.");
            }
        }
    }

    private String gerarNumeroOs(Contrato contrato) {
        String numeroContrato = contrato.getContrato() != null && !contrato.getContrato().isBlank()
                ? contrato.getContrato().trim()
                : "Contrato " + contrato.getId();
        long sequencial = ordemServicoRepository.countByContratoId(contrato.getId()) + 1;
        String numeroOs;
        do {
            numeroOs = numeroContrato + " - OS " + String.format("%02d", sequencial);
            sequencial++;
        } while (ordemServicoRepository.existsByNumeroOs(numeroOs));
        return numeroOs;
    }

    private List<ReparoPrevisao> planejarSincronizacao(Comarca comarca, Long osId) {
        List<MaterialProjeto> materiaisProjeto = materialProjetoRepository.findByProjetoId(comarca.getProjeto().getId());
        Set<String> nomesNovos = new HashSet<>();
        List<ReparoPrevisao> previsoes = new ArrayList<>();

        for (MaterialProjeto materialProjeto : materiaisProjeto) {
            if (materialProjeto.getMaterial() == null) {
                continue;
            }

            String nomeMaterial = materialProjeto.getMaterial().getNome();
            if (nomeMaterial == null || nomeMaterial.isBlank()) {
                continue;
            }
            Optional<MaterialItem> materialExistente = comarca.getMateriais() == null
                    ? Optional.empty()
                    : comarca.getMateriais().stream()
                            .filter(item -> item.getNomeMaterial() != null
                                    && item.getNomeMaterial().equalsIgnoreCase(nomeMaterial))
                            .findFirst();

            MaterialItem existente = materialExistente.orElse(null);
            if (existente == null && !nomesNovos.add(nomeMaterial.toLowerCase())) {
                continue;
            }

            Material material = materialProjeto.getMaterial();
            BigDecimal quantidade = valorOuZero(materialProjeto.getQuantidadePrevista());
            validarReparoPrevisao(osId, existente, material, quantidade);
            previsoes.add(new ReparoPrevisao(existente, material, nomeMaterial, quantidade,
                    valorOuZero(materialProjeto.getQuantidadeUtilizada())));
        }
        return previsoes;
    }

    private void validarReparoPrevisao(Long osId, MaterialItem existente, Material material, BigDecimal quantidade) {
        if (existente != null) {
            boolean mesmoMaterial = existente.getMaterial() != null
                    && Objects.equals(existente.getMaterial().getId(), material.getId());
            if (mesmoMaterial && existente.getQuantidadePrevista().compareTo(quantidade) == 0) {
                return;
            }
            if (reservaV2 != null) {
                reservaV2.semReservaItem(existente.getId());
            }
            if (Boolean.TRUE.equals(existente.getEstoqueReservado())
                    || Boolean.TRUE.equals(existente.getEstoqueBaixado())
                    || existente.getQuantidadeRetiradaV2().signum() > 0) {
                throw new IllegalStateException("Reparo não aplicado à OS " + osId + ": a previsão "
                        + existente.getId() + " tem reserva ou retirada. Use o fluxo de alteração de previsão da OS.");
            }
        }
        if (reservaV2 != null) {
            reservaV2.validarPrevisaoNoRegime(osId, material);
        }
    }

    private void aplicarPrevisao(Comarca comarca, ReparoPrevisao previsao) {
        MaterialItem item = previsao.existente();
        if (item == null) {
            item = new MaterialItem();
            item.setComarca(comarca);
            item.setNomeMaterial(previsao.nome());
        }
        item.setMaterial(previsao.material());
        item.setQuantidadePrevista(previsao.quantidade());
        if (item.getQuantidadeAuditada().signum() == 0) {
            item.setQuantidadeAuditada(previsao.auditada());
        }
        materialItemRepository.save(item);
    }

    private BigDecimal valorOuZero(Integer valor) {
        return BigDecimal.valueOf(valor != null ? valor : 0);
    }

    private record ReparoComarca(Comarca comarca, OrdemServico ordem, boolean vincular,
                                 List<ReparoPrevisao> previsoes) {
    }

    private record ReparoPrevisao(MaterialItem existente, Material material, String nome,
                                  BigDecimal quantidade, BigDecimal auditada) {
    }
}

interface OrdemRetiradaPort {
    OrdemRetirada criarParaOrdemServico(OrdemServico ordemServico, Comarca comarca, String geradoPor);
}
