package com.poprc.demo.service;

import com.poprc.demo.exception.ArquivoInvalidoException;
import com.poprc.demo.exception.SaldoInsuficienteException;
import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.HistoricoHomologacaoAsBuilt;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.MaterialItem;
import com.poprc.demo.model.MovimentacaoEstoque;
import com.poprc.demo.model.DocumentoInterno;
import com.poprc.demo.model.OrdemRetirada;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.model.ProjetoStatus;
import com.poprc.demo.model.StatusOS;
import com.poprc.demo.model.TipoMovimentacao;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.HistoricoHomologacaoAsBuiltRepository;
import com.poprc.demo.repository.MaterialItemRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.MovimentacaoEstoqueRepository;
import com.poprc.demo.repository.DocumentoInternoRepository;
import com.poprc.demo.repository.OrdemRetiradaRepository;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.storage.UploadStorage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ComarcaService {

    private static final Set<String> EXTENSOES_FOTO_VISTORIA = Set.of("jpg", "jpeg", "png");
    private static final Set<String> MIME_FOTO_VISTORIA = Set.of("image/jpeg", "image/jpg", "image/png");
    private static final String DIRETORIO_FOTO_VISTORIA = "comarcas/vistoria";
    private static final String DIRETORIO_PROVA_VIRADA_REDE = "comarcas/virada-rede";
    private static final String URL_PROVA_VIRADA_REDE = "/uploads/comarcas/virada-rede/";
    private static final String AS_BUILT_PENDENTE = "PENDENTE";
    private static final String AS_BUILT_DIVERGENTE = "DIVERGENTE";
    private static final String AS_BUILT_HOMOLOGADO = "HOMOLOGADO";
    private static final String AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA = "HOMOLOGADO_COM_DIVERGENCIA";
    private static final String AS_BUILT_REABERTO = "REABERTO_PARA_AJUSTE";
    private static final String DOCUMENTO_ENCERRAMENTO = "ENCERRAMENTO_OS";
    private static final String DOCUMENTO_REGISTRADO = "REGISTRADO";
    private static final String OBRA_CONCLUIDA = "CONCLUIDA";

    private final ComarcaRepository comarcaRepository;
    private final HistoricoHomologacaoAsBuiltRepository historicoHomologacaoAsBuiltRepository;
    private final MaterialItemRepository materialItemRepository;
    private final MaterialRepository materialRepository;
    private final MovimentacaoEstoqueRepository movimentacaoEstoqueRepository;
    private final OrdemRetiradaRepository ordemRetiradaRepository;
    private final DocumentoInternoRepository documentoInternoRepository;
    private final ProjetoRepository projetoRepository;
    private final FluxoOrdemServicoService fluxoOrdemServicoService;
    private final SaldoLocalService saldoLocalService;

    @PersistenceContext
    private EntityManager entityManager;

    public Optional<Comarca> obterPorId(Long id) {
        return comarcaRepository.findById(id);
    }

    private Optional<Comarca> buscarComarcaTravada(Long id) {
        Optional<Comarca> comarca = comarcaRepository.findByIdForUpdate(id);
        // A leitura de autorização pode ter carregado a entidade no mesmo EntityManager.
        comarca.ifPresent(entityManager::refresh);
        return comarca;
    }

    @Transactional(readOnly = true)
    public ArquivoObra carregarFotoVistoria(Long id) {
        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        return carregarArquivoObra(
                comarca.getFotoVistoriaUrl(),
                DIRETORIO_FOTO_VISTORIA,
                "/uploads/comarcas/vistoria/");
    }

    @Transactional(readOnly = true)
    public ArquivoObra carregarProvaViradaRede(Long id) {
        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        return carregarArquivoObra(
                comarca.getViradaRedeProvasFuncionamento(),
                DIRETORIO_PROVA_VIRADA_REDE,
                "/uploads/comarcas/virada-rede/");
    }

    @Transactional
    public Comarca atualizarProgresso(Long id, BigDecimal percentualConcluido, String situacao) {
        Optional<Comarca> comarcaOpt = buscarComarcaTravada(id);
        if (comarcaOpt.isPresent()) {
            Comarca comarca = comarcaOpt.get();
            if (situacao != null && ((OBRA_CONCLUIDA.equals(situacao)
                    && (!OBRA_CONCLUIDA.equals(comarca.getSituacao())
                            || comarca.getDataConclusao() == null))
                    || (OBRA_CONCLUIDA.equals(comarca.getSituacao())
                            && !OBRA_CONCLUIDA.equals(situacao)))) {
                throw new IllegalStateException(
                        "A situação de obra concluída só pode ser alterada pelo encerramento formal.");
            }
            if (percentualConcluido != null) {
                comarca.setPercentualConcluido(percentualConcluido);
            }
            if (situacao != null) {
                comarca.setSituacao(situacao);
            }
            return comarcaRepository.save(comarca);
        }
        return null;
    }

    public Comarca atualizarPendencias(Long id, String pendencias) {
        Optional<Comarca> comarcaOpt = comarcaRepository.findById(id);
        if (comarcaOpt.isPresent()) {
            Comarca comarca = comarcaOpt.get();
            if (pendencias != null) {
                comarca.setPendencias(pendencias);
            }
            return comarcaRepository.save(comarca);
        }
        return null;
    }

    @Transactional
    public Comarca atualizarComarca(Long id, BigDecimal percentualConcluido, String pendencias) {
        Optional<Comarca> comarcaOpt = comarcaRepository.findById(id);
        if (comarcaOpt.isPresent()) {
            Comarca comarca = comarcaOpt.get();
            if (percentualConcluido != null) {
                comarca.setPercentualConcluido(percentualConcluido);
            }
            // Permite limpar o campo de pendências (textarea vazia = resolvido)
            comarca.setPendencias(pendencias);
            return comarcaRepository.save(comarca);
        }
        return null;
    }

    @Transactional
    public Comarca salvarFotoVistoria(Long id, MultipartFile foto) {
        if (foto == null || foto.isEmpty()) {
            throw new ArquivoInvalidoException("A foto da vistoria é obrigatória.");
        }

        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarVistoriaEditavel(comarca);

        String fotoUrl = salvarArquivoFotoVistoria(foto);
        removerArquivoUpload(comarca.getFotoVistoriaUrl(), DIRETORIO_FOTO_VISTORIA,
                "/uploads/comarcas/vistoria/");
        comarca.setFotoVistoriaUrl(fotoUrl);
        sincronizarPercentualVistoria(comarca);
        return comarcaRepository.save(comarca);
    }

    @Transactional
    public Comarca salvarAssinaturaVistoria(Long id, String assinaturaBase64) {
        if (assinaturaBase64 == null || assinaturaBase64.isBlank()) {
            throw new IllegalArgumentException("A assinatura da vistoria é obrigatória.");
        }
        if (!assinaturaBase64.startsWith("data:image/png;base64,")) {
            throw new IllegalArgumentException("A assinatura deve ser enviada em Base64 PNG.");
        }

        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarVistoriaEditavel(comarca);

        comarca.setAssinaturaBase64(assinaturaBase64);
        sincronizarPercentualVistoria(comarca);
        return comarcaRepository.save(comarca);
    }

    @Transactional
    public Comarca salvarProvaViradaRede(Long id, MultipartFile foto) {
        if (foto == null || foto.isEmpty()) {
            throw new ArquivoInvalidoException("A prova de funcionamento da Virada de Rede é obrigatória.");
        }

        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarViradaRedeEditavel(comarca);

        removerArquivoUpload(comarca.getViradaRedeProvasFuncionamento(), DIRETORIO_PROVA_VIRADA_REDE,
                "/uploads/comarcas/virada-rede/");
        comarca.setViradaRedeProvasFuncionamento(salvarArquivoImagem(foto, DIRETORIO_PROVA_VIRADA_REDE,
                "/uploads/comarcas/virada-rede/"));
        return comarcaRepository.save(comarca);
    }

    @Transactional
    public Comarca removerFotoVistoria(Long id) {
        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarVistoriaEditavel(comarca);
        removerArquivoUpload(comarca.getFotoVistoriaUrl(), DIRETORIO_FOTO_VISTORIA,
                "/uploads/comarcas/vistoria/");
        comarca.setFotoVistoriaUrl(null);
        sincronizarPercentualVistoria(comarca);
        return comarcaRepository.save(comarca);
    }

    @Transactional
    public Comarca removerAssinaturaVistoria(Long id) {
        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarVistoriaEditavel(comarca);
        comarca.setAssinaturaBase64(null);
        sincronizarPercentualVistoria(comarca);
        return comarcaRepository.save(comarca);
    }

    @Transactional
    public Comarca removerProvaViradaRede(Long id) {
        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarViradaRedeEditavel(comarca);
        removerArquivoUpload(comarca.getViradaRedeProvasFuncionamento(), DIRETORIO_PROVA_VIRADA_REDE,
                "/uploads/comarcas/virada-rede/");
        comarca.setViradaRedeProvasFuncionamento(null);
        return comarcaRepository.save(comarca);
    }

    @Transactional
    public Comarca avancarParaInfraestrutura(Long id) {
        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));

        Integer etapaAtual = comarca.getEtapaAtual() != null ? comarca.getEtapaAtual() : 1;
        if (etapaAtual <= 1) {
            if (comarca.getFotoVistoriaUrl() == null || comarca.getFotoVistoriaUrl().isBlank()) {
                throw new IllegalArgumentException("Upload da foto da vistoria é obrigatório para liberar a infraestrutura.");
            }

            if (comarca.getAssinaturaBase64() == null || comarca.getAssinaturaBase64().isBlank()) {
                throw new IllegalArgumentException("Assinatura do responsável é obrigatória para liberar a infraestrutura.");
            }

            comarca.setEtapaAtual(2);
            comarca.setPercentualConcluido(BigDecimal.valueOf(70));
            comarca.setSituacao("INFRAESTRUTURA_LIBERADA");
            Comarca salva = comarcaRepository.save(comarca);
            if (salva.getOrdemServico() != null) {
                fluxoOrdemServicoService.registrarVistoriaLiberada(
                        salva.getOrdemServico().getId(), "Gestão de Obras");
            }
            return salva;
        }

        validarRetiradaExecutada(comarca);
        comarca.setEtapaAtual(3);
        comarca.setPercentualConcluido(BigDecimal.valueOf(Boolean.TRUE.equals(comarca.getViradaRedeConcluida()) ? 90 : 85));
        comarca.setSituacao("VIRADA_DE_REDE");
        return comarcaRepository.save(comarca);
    }

    @Transactional
    public Comarca salvarViradaRede(Long id, String provasFuncionamento, String checklist, Boolean concluida) {
        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));

        if (provasFuncionamento != null && !provasFuncionamento.isBlank()
                && !provasFuncionamento.startsWith(URL_PROVA_VIRADA_REDE)) {
            throw new IllegalArgumentException(
                    "A prova de funcionamento deve ser uma foto enviada pela Virada de Rede.");
        }
        String provaAtual = provasFuncionamento != null && !provasFuncionamento.isBlank()
                ? provasFuncionamento
                : comarca.getViradaRedeProvasFuncionamento();
        if (Boolean.TRUE.equals(concluida)) {
            validarRetiradaExecutada(comarca);
        }
        if (Boolean.TRUE.equals(concluida)
                && (provaAtual == null || provaAtual.isBlank()
                        || checklist == null || checklist.isBlank())) {
            throw new IllegalArgumentException(
                    "Informe as provas de funcionamento e o checklist antes de concluir a Virada de Rede.");
        }

        comarca.setViradaRedeProvasFuncionamento(provaAtual);
        comarca.setViradaRedeChecklist(checklist);
        comarca.setViradaRedeConcluida(Boolean.TRUE.equals(concluida));
        if (Boolean.TRUE.equals(concluida)) {
            comarca.setEtapaAtual(3);
            comarca.setPercentualConcluido(BigDecimal.valueOf(90));
            comarca.setSituacao("VIRADA_DE_REDE_CONCLUIDA");
        } else if (comarca.getEtapaAtual() != null && comarca.getEtapaAtual() >= 3) {
            comarca.setPercentualConcluido(BigDecimal.valueOf(85));
            comarca.setSituacao("VIRADA_DE_REDE_EM_ANDAMENTO");
        }
        return comarcaRepository.save(comarca);
    }

    private void validarRetiradaExecutada(Comarca comarca) {
        if (comarca.getOrdemServico() == null) {
            throw new IllegalArgumentException(
                    "Vincule uma Ordem de Serviço antes de iniciar a Virada de Rede.");
        }

        StatusOS status = comarca.getOrdemServico().getStatus();
        if (status == StatusOS.ABERTA
                || status == StatusOS.AGUARDANDO_VISTORIA
                || status == StatusOS.AGUARDANDO_RETIRADA) {
            throw new IllegalArgumentException(
                    "Execute a retirada da Ordem de Retirada antes de iniciar a Virada de Rede.");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buscarAuditoriaPorComarca(Long id) {
        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        return montarAuditoriaComarca(comarca);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buscarAuditoriaPorNumeroOs(String numeroOs) {
        if (numeroOs == null || numeroOs.isBlank()) {
            throw new IllegalArgumentException("Número da OS é obrigatório.");
        }

        Comarca comarca = comarcaRepository.findByOrdemServicoNumeroOs(numeroOs)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada para a OS informada."));
        return montarAuditoriaComarca(comarca);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buscarRastreabilidadePorComarca(Long id) {
        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        return montarRastreabilidadeComarca(comarca);
    }

    @Transactional
    public Map<String, Object> atualizarQuantidadeAuditada(Long materialId, BigDecimal quantidadeAuditada) {
        if (quantidadeAuditada == null || quantidadeAuditada.signum() < 0) {
            throw new IllegalArgumentException("A quantidade auditada deve ser maior ou igual a zero.");
        }

        MaterialItem material = materialItemRepository.findById(materialId)
                .orElseThrow(() -> new IllegalArgumentException("Material da comarca não encontrado."));
        Comarca comarca = buscarComarcaTravada(material.getComarca().getId())
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarAsBuiltAbertoParaMateriais(comarca);
        entityManager.refresh(material);
        validarEscalaQuantidade(material.getMaterial(), quantidadeAuditada);

        material.setQuantidadeAuditada(quantidadeAuditada);
        MaterialItem materialSalvo = materialItemRepository.save(material);
        sincronizarStatusAsBuilt(comarca);
        entityManager.flush();
        return montarMaterialAuditoria(materialSalvo);
    }

    @Transactional(readOnly = true)
    public List<MaterialItem> listarMateriaisPrevistos(Long comarcaId) {
        Comarca comarca = comarcaRepository.findById(comarcaId)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        return materiaisDaComarca(comarca);
    }

    @Transactional
    public Comarca adicionarMaterialPrevisto(Long comarcaId, Long materialId, String nomeMaterial, BigDecimal quantidadePrevista) {
        validarMaterialPrevisto(nomeMaterial, quantidadePrevista);

        Comarca comarca = buscarComarcaTravada(comarcaId)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarAsBuiltAbertoParaMateriais(comarca);
        Material materialEstoque = obterMaterialEstoque(materialId);
        validarEscalaQuantidade(materialEstoque, quantidadePrevista);
        validarSaldoReserva(materialEstoque, quantidadePrevista, BigDecimal.ZERO);

        MaterialItem material = new MaterialItem();
        material.setComarca(comarca);
        material.setMaterial(materialEstoque);
        material.setNomeMaterial(materialEstoque != null ? materialEstoque.getNome() : nomeMaterial.trim());
        material.setQuantidadePrevista(quantidadePrevista);
        material.setQuantidadeAuditada(BigDecimal.ZERO);
        material.setEstoqueReservado(false);
        material.setEstoqueBaixado(false);
        material.setDataHoraSolicitacao(LocalDateTime.now());
        MaterialItem materialSalvo = materialItemRepository.save(material);
        reservarEstoque(comarca, materialSalvo, quantidadePrevista);

        comarca.setAsBuiltStatus(AS_BUILT_PENDENTE);
        sincronizarStatusAsBuilt(comarca);
        return recarregarComarca(comarcaId);
    }

    @Transactional
    public Comarca atualizarMaterialPrevisto(Long materialItemId, Long materialId, String nomeMaterial, BigDecimal quantidadePrevista) {
        validarMaterialPrevisto(nomeMaterial, quantidadePrevista);

        MaterialItem material = materialItemRepository.findById(materialItemId)
                .orElseThrow(() -> new IllegalArgumentException("Material previsto não encontrado."));
        Comarca comarca = buscarComarcaTravada(material.getComarca().getId())
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarAsBuiltAbertoParaMateriais(comarca);
        entityManager.refresh(material);
        if (Boolean.TRUE.equals(material.getEstoqueBaixado())) {
            throw new IllegalArgumentException("Material já baixado no estoque não pode ser alterado.");
        }
        liberarReservaEstoque(comarca, material, material.getQuantidadePrevista());

        Material materialEstoque = obterMaterialEstoque(materialId);
        validarEscalaQuantidade(materialEstoque, quantidadePrevista);
        validarSaldoReserva(materialEstoque, quantidadePrevista, BigDecimal.ZERO);
        material.setMaterial(materialEstoque);
        material.setNomeMaterial(materialEstoque != null ? materialEstoque.getNome() : nomeMaterial.trim());
        material.setQuantidadePrevista(quantidadePrevista);
        material.setEstoqueReservado(false);
        MaterialItem materialSalvo = materialItemRepository.save(material);
        reservarEstoque(comarca, materialSalvo, quantidadePrevista);

        comarca.setAsBuiltStatus(AS_BUILT_PENDENTE);
        sincronizarStatusAsBuilt(comarca);
        return recarregarComarca(comarca.getId());
    }

    @Transactional
    public Comarca atualizarMateriaisFaltantes(Long comarcaId, Boolean faltouMaterial, List<Long> materialItemIds,
            String descricao) {
        Comarca comarca = buscarComarcaTravada(comarcaId)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarAsBuiltAbertoParaMateriais(comarca);

        boolean marcouFalta = Boolean.TRUE.equals(faltouMaterial);
        if (marcouFalta && (descricao == null || descricao.isBlank())) {
            throw new IllegalArgumentException("Descreva o que está faltando para registrar a pendência de material.");
        }

        List<MaterialItem> materiais = materiaisDaComarca(comarca);
        materiais.forEach(entityManager::refresh);
        Set<Long> idsSelecionados = materialItemIds == null
                ? Set.of()
                : materialItemIds.stream().collect(Collectors.toSet());
        if (marcouFalta && idsSelecionados.isEmpty()) {
            throw new IllegalArgumentException("Selecione ao menos um material que está faltando.");
        }
        Set<Long> idsDaComarca = materiais.stream().map(MaterialItem::getId).collect(Collectors.toSet());
        if (marcouFalta && !idsDaComarca.containsAll(idsSelecionados)) {
            throw new IllegalArgumentException("Um dos materiais selecionados não pertence a esta obra.");
        }

        materiais.forEach(material -> {
            boolean selecionado = idsSelecionados.contains(material.getId());
            material.setMaterialFaltante(marcouFalta && selecionado);
            material.setDescricaoFaltante(marcouFalta && selecionado ? descricao : null);
            materialItemRepository.save(material);
        });

        String descricaoAnterior = comarca.getDescricaoMaterialFaltante();
        comarca.setFaltouMaterial(marcouFalta);
        comarca.setDescricaoMaterialFaltante(marcouFalta ? descricao : null);
        if (marcouFalta) {
            comarca.setPendencias(descricao.trim());
        } else if (descricaoAnterior != null && descricaoAnterior.equals(comarca.getPendencias())) {
            comarca.setPendencias(null);
        }
        Comarca salva = comarcaRepository.save(comarca);
        entityManager.flush();
        return salva;
    }

    @Transactional
    public Comarca adicionarItemAdicional(Long comarcaId, Long materialId, String nomeMaterial, BigDecimal quantidadePrevista) {
        validarMaterialPrevisto(nomeMaterial, quantidadePrevista);

        Comarca comarca = buscarComarcaTravada(comarcaId)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarAsBuiltAbertoParaMateriais(comarca);
        Material materialEstoque = obterMaterialEstoque(materialId);
        validarEscalaQuantidade(materialEstoque, quantidadePrevista);
        validarSaldoReserva(materialEstoque, quantidadePrevista, BigDecimal.ZERO);

        MaterialItem material = new MaterialItem();
        material.setComarca(comarca);
        material.setMaterial(materialEstoque);
        material.setNomeMaterial(materialEstoque != null ? materialEstoque.getNome() : nomeMaterial.trim());
        material.setQuantidadePrevista(quantidadePrevista);
        material.setQuantidadeAuditada(BigDecimal.ZERO);
        material.setEstoqueReservado(false);
        material.setEstoqueBaixado(false);
        material.setItemAdicional(true);
        material.setDataHoraSolicitacao(LocalDateTime.now());
        MaterialItem materialSalvo = materialItemRepository.save(material);
        reservarEstoque(comarca, materialSalvo, quantidadePrevista);
        return recarregarComarca(comarcaId);
    }

    @Transactional
    public Comarca atualizarTimelineMaterial(Long materialItemId, LocalDateTime dataHoraSolicitacao,
            LocalDateTime dataHoraRetirada, LocalDateTime dataHoraUso) {
        validarOrdemTimeline(dataHoraSolicitacao, dataHoraRetirada, dataHoraUso);

        MaterialItem material = materialItemRepository.findById(materialItemId)
                .orElseThrow(() -> new IllegalArgumentException("Material previsto não encontrado."));
        Comarca comarca = buscarComarcaTravada(material.getComarca().getId())
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarAsBuiltAbertoParaMateriais(comarca);
        entityManager.refresh(material);
        material.setDataHoraSolicitacao(dataHoraSolicitacao);
        material.setDataHoraRetirada(dataHoraRetirada);
        material.setDataHoraUso(dataHoraUso);
        materialItemRepository.save(material);
        entityManager.flush();
        return recarregarComarca(comarca.getId());
    }

    private void validarOrdemTimeline(LocalDateTime dataHoraSolicitacao, LocalDateTime dataHoraRetirada,
            LocalDateTime dataHoraUso) {
        if (dataHoraSolicitacao != null && dataHoraRetirada != null
                && dataHoraRetirada.isBefore(dataHoraSolicitacao)) {
            throw new IllegalArgumentException("A retirada não pode acontecer antes da solicitação.");
        }
        if (dataHoraRetirada != null && dataHoraUso != null && dataHoraUso.isBefore(dataHoraRetirada)) {
            throw new IllegalArgumentException("O uso não pode acontecer antes da retirada.");
        }
        if (dataHoraSolicitacao != null && dataHoraUso != null && dataHoraUso.isBefore(dataHoraSolicitacao)) {
            throw new IllegalArgumentException("O uso não pode acontecer antes da solicitação.");
        }
    }

    @Transactional
    public Comarca removerMaterialPrevisto(Long materialId) {
        MaterialItem material = materialItemRepository.findById(materialId)
                .orElseThrow(() -> new IllegalArgumentException("Material previsto não encontrado."));
        Comarca comarca = buscarComarcaTravada(material.getComarca().getId())
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        validarAsBuiltAbertoParaMateriais(comarca);
        entityManager.refresh(material);
        if (Boolean.TRUE.equals(material.getEstoqueBaixado())) {
            throw new IllegalArgumentException("Material já baixado no estoque não pode ser removido.");
        }
        Long comarcaId = comarca.getId();
        liberarReservaEstoque(comarca, material, material.getQuantidadePrevista());

        if (comarca.getMateriais() != null) {
            comarca.getMateriais().removeIf(item -> item.getId().equals(materialId));
        }
        materialItemRepository.delete(material);

        comarca.setAsBuiltStatus(AS_BUILT_PENDENTE);
        sincronizarStatusAsBuilt(comarca);
        return recarregarComarca(comarcaId);
    }

    @Transactional
    public Map<String, Object> homologarAsBuilt(Long id, String justificativa) {
        return homologarAsBuilt(id, justificativa, "Auditoria de Retirada/Devolução");
    }

    @Transactional
    public Map<String, Object> homologarAsBuilt(Long id, String justificativa, String responsavel) {
        // Preserva alterações pendentes na transação antes de atualizar a entidade bloqueada.
        entityManager.flush();
        Comarca comarca = buscarComarcaTravada(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
        if (AS_BUILT_HOMOLOGADO.equals(comarca.getAsBuiltStatus())
                || AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA.equals(comarca.getAsBuiltStatus())) {
            throw new IllegalStateException("Reabra o As-Built antes de registrar outra homologação.");
        }
        List<MaterialItem> materiais = materiaisDaComarca(comarca);
        materiais.forEach(entityManager::refresh);

        if (materiais.isEmpty()) {
            throw new IllegalArgumentException("Não há materiais previstos para homologar o As-Built desta comarca.");
        }

        boolean conciliado = materiaisConciliados(materiais);
        if (!conciliado && (justificativa == null || justificativa.isBlank())) {
            throw new IllegalArgumentException("Informe a justificativa para homologar o As-Built com divergência.");
        }
        validarMateriaisVinculadosAoEstoque(materiais);
        validarConsolidacaoFisicaDaOr(comarca, materiais);

        comarca.setAsBuiltStatus(conciliado ? AS_BUILT_HOMOLOGADO : AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA);
        comarca.setSituacao(conciliado ? "AS_BUILT_HOMOLOGADO" : "AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA");
        Comarca comarcaSalva = comarcaRepository.save(comarca);
        HistoricoHomologacaoAsBuilt homologacao = new HistoricoHomologacaoAsBuilt();
        homologacao.setComarca(comarcaSalva);
        homologacao.setStatus(comarcaSalva.getAsBuiltStatus());
        homologacao.setJustificativa(conciliado ? null : justificativa.trim());
        homologacao.setResponsavel(responsavel);
        homologacao.setRegistradoEm(LocalDateTime.now());
        historicoHomologacaoAsBuiltRepository.save(homologacao);
        sincronizarAsBuiltDoProjeto(comarcaSalva);
        if (comarcaSalva.getOrdemServico() != null) {
            fluxoOrdemServicoService.registrarAsBuiltHomologado(
                    comarcaSalva.getOrdemServico().getId(), "Auditoria de Retirada/Devolução");
        }
        return montarAuditoriaComarca(comarcaSalva);
    }

    @Transactional
    public EncerramentoObraResultado concluirObra(Long id, String concluidaPor) {
        Comarca comarca = buscarComarcaTravada(id)
                .orElseThrow(() -> new IllegalArgumentException("Obra não encontrada."));

        if (OBRA_CONCLUIDA.equals(comarca.getSituacao()) && comarca.getDataConclusao() != null) {
            return montarEncerramento(comarca);
        }
        if (concluidaPor == null || concluidaPor.isBlank()) {
            throw new IllegalArgumentException("Informe quem está concluindo a obra.");
        }
        if (!Boolean.TRUE.equals(comarca.getViradaRedeConcluida())) {
            throw new IllegalArgumentException("Conclua a Virada de Rede antes de encerrar a obra.");
        }
        if (!AS_BUILT_HOMOLOGADO.equals(comarca.getAsBuiltStatus())
                && !AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA.equals(comarca.getAsBuiltStatus())) {
            throw new IllegalArgumentException("Homologue o As-Built antes de encerrar a obra.");
        }
        if (comarca.getOrdemServico() == null
                || (comarca.getOrdemServico().getStatus() != StatusOS.AGUARDANDO_ENCERRAMENTO
                        && comarca.getOrdemServico().getStatus() != StatusOS.CONCLUIDA
                        && comarca.getOrdemServico().getStatus() != StatusOS.FATURADA)) {
            throw new IllegalArgumentException(
                    "Conclua a validação técnica, a devolução e a auditoria antes de encerrar a obra.");
        }

        List<OrdemRetirada> ordensRetirada = ordemRetiradaRepository
                .findByComarcaIdOrderByDataGeracaoDesc(comarca.getId());
        if (ordensRetirada.isEmpty()) {
            throw new IllegalArgumentException("A obra não possui Ordem de Retirada vinculada.");
        }
        if (ordensRetirada.stream().anyMatch(ordem -> !"DEVOLVIDA".equals(ordem.getStatus()))) {
            throw new IllegalArgumentException("Todas as Ordens de Retirada precisam estar devolvidas.");
        }

        List<DocumentoInterno> documentosFinais = documentoInternoRepository
                .findByComarcaIdAndTipoAndStatusOrderByDataGeracaoDesc(
                        comarca.getId(), DOCUMENTO_ENCERRAMENTO, DOCUMENTO_REGISTRADO);
        if (documentosFinais.isEmpty()) {
            throw new IllegalArgumentException(
                    "Salve e conclua as três assinaturas do documento final antes de encerrar a obra.");
        }

        comarca.setSituacao(OBRA_CONCLUIDA);
        comarca.setPercentualConcluido(BigDecimal.valueOf(100));
        comarca.setDataConclusao(LocalDateTime.now());
        comarca.setConcluidaPor(concluidaPor.trim());

        Projeto projeto = comarca.getProjeto();
        if (projeto != null) {
            projeto.setStatus(ProjetoStatus.CONCLUIDO);
            projeto.setAsBuiltStatus(comarca.getAsBuiltStatus());
            projetoRepository.save(projeto);
        }

        Comarca salva = comarcaRepository.save(comarca);
        fluxoOrdemServicoService.registrarEncerramento(
                salva.getOrdemServico().getId(), concluidaPor.trim());
        return montarEncerramento(salva);
    }

    @Transactional(readOnly = true)
    public EncerramentoObraResultado buscarEncerramento(Long id) {
        Comarca comarca = comarcaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Obra não encontrada."));
        if (!OBRA_CONCLUIDA.equals(comarca.getSituacao()) || comarca.getDataConclusao() == null) {
            throw new IllegalStateException("A obra ainda não possui encerramento registrado.");
        }
        return montarEncerramento(comarca);
    }

    @Transactional
    public Map<String, Object> reabrirAsBuilt(Long id) {
        Comarca comarca = buscarComarcaTravada(id)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));

        if (OBRA_CONCLUIDA.equals(comarca.getSituacao())) {
            throw new IllegalArgumentException("Uma obra concluída não pode ter o As-Built reaberto.");
        }

        if (!AS_BUILT_HOMOLOGADO.equals(comarca.getAsBuiltStatus())
                && !AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA.equals(comarca.getAsBuiltStatus())) {
            throw new IllegalArgumentException("Somente As-Built homologado pode ser reaberto para ajuste.");
        }

        if (AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA.equals(comarca.getAsBuiltStatus())
                && historicoHomologacaoAsBuiltRepository.findByComarcaIdOrderByIdDesc(id).isEmpty()) {
            comarca.setAsBuiltHomologacaoLegadaSemJustificativa(true);
        }

        comarca.setAsBuiltStatus(AS_BUILT_REABERTO);
        comarca.setSituacao("AS_BUILT_REABERTO_PARA_AJUSTE");
        Comarca comarcaSalva = comarcaRepository.save(comarca);
        sincronizarAsBuiltDoProjeto(comarcaSalva);
        return montarAuditoriaComarca(comarcaSalva);
    }

    private void sincronizarAsBuiltDoProjeto(Comarca comarca) {
        Projeto projeto = comarca.getProjeto();
        if (projeto == null) {
            return;
        }
        projeto.setAsBuiltStatus(comarca.getAsBuiltStatus());
        projetoRepository.save(projeto);
    }

    private EncerramentoObraResultado montarEncerramento(Comarca comarca) {
        List<OrdemRetirada> ordensRetirada = ordemRetiradaRepository
                .findByComarcaIdOrderByDataGeracaoDesc(comarca.getId());
        DocumentoInterno documentoFinal = documentoInternoRepository
                .findByComarcaIdAndTipoAndStatusOrderByDataGeracaoDesc(
                        comarca.getId(), DOCUMENTO_ENCERRAMENTO, DOCUMENTO_REGISTRADO)
                .stream().findFirst().orElse(null);

        return new EncerramentoObraResultado(
                comarca.getId(),
                comarca.getNomeComarca(),
                comarca.getSituacao(),
                comarca.getDataConclusao(),
                comarca.getConcluidaPor(),
                comarca.getOrdemServico() != null ? comarca.getOrdemServico().getNumeroOs() : null,
                comarca.getOrdemServico() != null ? comarca.getOrdemServico().getStatus().name() : null,
                comarca.getAsBuiltStatus(),
                ordensRetirada.size(),
                documentoFinal != null ? documentoFinal.getId() : null,
                documentoFinal != null ? documentoFinal.getPdfPath() : null);
    }

    public record EncerramentoObraResultado(
            Long comarcaId,
            String nomeObra,
            String situacao,
            LocalDateTime concluidaEm,
            String concluidaPor,
            String numeroOs,
            String statusOs,
            String statusAsBuilt,
            int ordensRetiradaDevolvidas,
            Long documentoFinalId,
            String documentoFinalPdfPath) {
    }

    @Transactional
    public Comarca criarOuVincularComarcaParaProjeto(Projeto projeto) {
        return comarcaRepository.findByProjetoId(projeto.getId())
                .orElseGet(() -> {
                    Comarca nova = new Comarca();
                    nova.setNomeComarca(determinarNomeComarca(projeto));
                    nova.setProjeto(projeto);
                    nova.setQuantidadePontos(0);
                    nova.setPercentualConcluido(BigDecimal.ZERO);
                    nova.setSituacao("EM_ANDAMENTO");
                    return comarcaRepository.save(nova);
                });
    }

    private String determinarNomeComarca(Projeto projeto) {
        if (projeto.getNomeComarcaVinculada() != null && !projeto.getNomeComarcaVinculada().isBlank()) {
            return projeto.getNomeComarcaVinculada();
        }
        if (projeto.getContrato() != null && projeto.getContrato().getCliente() != null) {
            return projeto.getContrato().getCliente();
        }
        return "Comarca do Projeto #" + projeto.getId();
    }

    private Map<String, Object> montarAuditoriaComarca(Comarca comarca) {
        Map<String, Object> response = new HashMap<>();
        List<MaterialItem> materiais = materiaisDaComarca(comarca);
        boolean conciliado = materiaisConciliados(materiais);

        response.put("comarcaId", comarca.getId());
        response.put("nomeComarca", comarca.getNomeComarca());
        response.put("numeroOs", comarca.getOrdemServico() != null ? comarca.getOrdemServico().getNumeroOs() : null);
        response.put("etapaAtual", comarca.getEtapaAtual());
        response.put("conciliado", conciliado);
        response.put("asBuiltStatus", resolverStatusAsBuilt(comarca, conciliado, !materiais.isEmpty()));
        List<HistoricoHomologacaoAsBuilt> homologacoes = historicoHomologacaoAsBuiltRepository
                .findByComarcaIdOrderByIdDesc(comarca.getId());
        response.put("homologacoes", homologacoes.stream().map(item -> Map.of(
                "id", item.getId(),
                "status", item.getStatus(),
                "justificativa", item.getJustificativa() == null ? "" : item.getJustificativa(),
                "responsavel", item.getResponsavel(),
                "registradoEm", item.getRegistradoEm())).toList());
        response.put("homologacaoLegadaSemJustificativa",
                Boolean.TRUE.equals(comarca.getAsBuiltHomologacaoLegadaSemJustificativa())
                        || (AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA.equals(comarca.getAsBuiltStatus())
                                && homologacoes.isEmpty()));
        response.put("materiais", materiais.stream().map(this::montarMaterialAuditoria).toList());

        return response;
    }

    private Map<String, Object> montarMaterialAuditoria(MaterialItem material) {
        Map<String, Object> materialMap = new HashMap<>();
        materialMap.put("id", material.getId());
        materialMap.put("nome", material.getNomeMaterial());
        materialMap.put("previsto", material.getQuantidadePrevista());
        materialMap.put("utilizado", material.getQuantidadeAuditada());
        materialMap.put("estoqueReservado", Boolean.TRUE.equals(material.getEstoqueReservado()));
        materialMap.put("estoqueBaixado", Boolean.TRUE.equals(material.getEstoqueBaixado()));
        materialMap.put("materialFaltante", Boolean.TRUE.equals(material.getMaterialFaltante()));
        materialMap.put("itemAdicional", Boolean.TRUE.equals(material.getItemAdicional()));
        materialMap.put("descricaoFaltante", material.getDescricaoFaltante());
        materialMap.put("dataHoraSolicitacao", material.getDataHoraSolicitacao());
        materialMap.put("dataHoraRetirada", material.getDataHoraRetirada());
        materialMap.put("dataHoraUso", material.getDataHoraUso());
        if (material.getMaterial() != null) {
            materialMap.put("materialId", material.getMaterial().getId());
            materialMap.put("partNumber", material.getMaterial().getPartNumber());
            materialMap.put("categoria", material.getMaterial().getCategoria());
            materialMap.put("tipoControle", material.getMaterial().getTipoControle());
            materialMap.put("unidadeMedida", material.getMaterial().getUnidadeMedida());
            materialMap.put("descricaoProduto", material.getMaterial().getDescricao());
            materialMap.put("fotoProdutoUrl", material.getMaterial().getFotoProdutoUrl());
            materialMap.put("estoqueDisponivel", controlaMetragem(material.getMaterial())
                    ? material.getMaterial().getMetragemDisponivel()
                    : material.getMaterial().getQuantidadeDisponivel());
            materialMap.put("estoqueReservadoTotal", controlaMetragem(material.getMaterial())
                    ? metragemReservada(material.getMaterial())
                    : BigDecimal.valueOf(quantidadeReservada(material.getMaterial())));
            materialMap.put("estoqueLivre", saldoLivreParaPlanejamento(material.getMaterial()));
        }
        return materialMap;
    }

    private Map<String, Object> montarRastreabilidadeComarca(Comarca comarca) {
        Map<String, Object> response = new HashMap<>();
        List<MaterialItem> materiais = materiaisDaComarca(comarca);
        List<MovimentacaoEstoque> movimentacoes = movimentacaoEstoqueRepository
                .findByComarcaIdOrderByDataMovimentacaoDesc(comarca.getId());

        List<Map<String, Object>> itens = materiais.stream()
                .map(item -> montarItemRastreabilidade(item, movimentacoes))
                .toList();

        BigDecimal totalPrevisto = somarCampo(itens, "previsto");
        BigDecimal totalReservado = somarCampo(itens, "reservado");
        BigDecimal totalAuditado = somarCampo(itens, "auditado");
        BigDecimal totalBaixado = somarCampo(itens, "baixado");

        response.put("comarcaId", comarca.getId());
        response.put("nomeComarca", comarca.getNomeComarca());
        response.put("numeroOs", comarca.getOrdemServico() != null ? comarca.getOrdemServico().getNumeroOs() : null);
        response.put("projetoId", resolverProjetoDaComarca(comarca) != null ? resolverProjetoDaComarca(comarca).getId() : null);
        response.put("asBuiltStatus", comarca.getAsBuiltStatus());
        response.put("totais", Map.of(
                "previsto", totalPrevisto,
                "reservado", totalReservado,
                "auditado", totalAuditado,
                "baixado", totalBaixado,
                "saldoPrevistoBaixado", totalPrevisto.subtract(totalBaixado),
                "saldoAuditadoBaixado", totalAuditado.subtract(totalBaixado)));
        response.put("itens", itens);
        response.put("movimentacoes", movimentacoes.stream().map(this::montarMovimentacaoRastreabilidade).toList());
        return response;
    }

    private Map<String, Object> montarItemRastreabilidade(MaterialItem item, List<MovimentacaoEstoque> movimentacoes) {
        List<MovimentacaoEstoque> movimentacoesDoMaterial = movimentacoes.stream()
                .filter(movimentacao -> movimentacao.getMaterial() != null
                        && item.getMaterial() != null
                        && movimentacao.getMaterial().getId().equals(item.getMaterial().getId()))
                .toList();

        BigDecimal baixado = movimentacoesDoMaterial.stream()
                .filter(movimentacao -> TipoMovimentacao.BAIXA.equals(movimentacao.getTipo())
                        || TipoMovimentacao.SAIDA.equals(movimentacao.getTipo())
                        || TipoMovimentacao.ESTORNO_BAIXA.equals(movimentacao.getTipo())
                        || TipoMovimentacao.RETIRADA_OR.equals(movimentacao.getTipo())
                        || TipoMovimentacao.DEVOLUCAO_OR.equals(movimentacao.getTipo()))
                .map(movimentacao -> TipoMovimentacao.ESTORNO_BAIXA.equals(movimentacao.getTipo())
                                || TipoMovimentacao.DEVOLUCAO_OR.equals(movimentacao.getTipo())
                        ? valorMovimentacao(movimentacao).negate()
                        : valorMovimentacao(movimentacao))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reservado = Boolean.TRUE.equals(item.getEstoqueReservado()) && !Boolean.TRUE.equals(item.getEstoqueBaixado())
                ? item.getQuantidadePrevista()
                : BigDecimal.ZERO;
        BigDecimal previsto = item.getQuantidadePrevista();
        BigDecimal auditado = item.getQuantidadeAuditada();

        Map<String, Object> itemMap = new HashMap<>();
        itemMap.put("materialItemId", item.getId());
        itemMap.put("materialId", item.getMaterial() != null ? item.getMaterial().getId() : null);
        itemMap.put("nome", item.getNomeMaterial());
        itemMap.put("partNumber", item.getMaterial() != null ? item.getMaterial().getPartNumber() : null);
        itemMap.put("categoria", item.getMaterial() != null ? item.getMaterial().getCategoria() : null);
        itemMap.put("descricaoProduto", item.getMaterial() != null ? item.getMaterial().getDescricao() : null);
        itemMap.put("fotoProdutoUrl", item.getMaterial() != null ? item.getMaterial().getFotoProdutoUrl() : null);
        itemMap.put("previsto", previsto);
        itemMap.put("reservado", reservado);
        itemMap.put("auditado", auditado);
        itemMap.put("baixado", baixado);
        itemMap.put("saldoPrevistoBaixado", previsto.subtract(baixado));
        itemMap.put("saldoAuditadoBaixado", auditado.subtract(baixado));
        itemMap.put("estoqueBaixado", Boolean.TRUE.equals(item.getEstoqueBaixado()));
        itemMap.put("materialFaltante", Boolean.TRUE.equals(item.getMaterialFaltante()));
        itemMap.put("itemAdicional", Boolean.TRUE.equals(item.getItemAdicional()));
        itemMap.put("descricaoFaltante", item.getDescricaoFaltante());
        itemMap.put("dataHoraSolicitacao", item.getDataHoraSolicitacao());
        itemMap.put("dataHoraRetirada", item.getDataHoraRetirada());
        itemMap.put("dataHoraUso", item.getDataHoraUso());
        itemMap.put("movimentacoes", movimentacoesDoMaterial.stream()
                .map(this::montarMovimentacaoRastreabilidade)
                .collect(Collectors.toList()));
        return itemMap;
    }

    private Map<String, Object> montarMovimentacaoRastreabilidade(MovimentacaoEstoque movimentacao) {
        Map<String, Object> movimentacaoMap = new HashMap<>();
        movimentacaoMap.put("id", movimentacao.getId());
        movimentacaoMap.put("tipo", movimentacao.getTipo());
        movimentacaoMap.put("quantidade", movimentacao.getQuantidade());
        movimentacaoMap.put("dataMovimentacao", movimentacao.getDataMovimentacao());
        movimentacaoMap.put("observacao", movimentacao.getObservacao());
        movimentacaoMap.put("materialId", movimentacao.getMaterial() != null ? movimentacao.getMaterial().getId() : null);
        movimentacaoMap.put("materialNome", movimentacao.getMaterial() != null ? movimentacao.getMaterial().getNome() : null);
        movimentacaoMap.put("numeroOr",
                movimentacao.getOrdemRetirada() != null ? movimentacao.getOrdemRetirada().getNumeroOr() : null);
        return movimentacaoMap;
    }

    private BigDecimal somarCampo(List<Map<String, Object>> itens, String campo) {
        return itens.stream()
                .map(item -> item.get(campo))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(numero -> new BigDecimal(numero.toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void validarMaterialPrevisto(String nomeMaterial, BigDecimal quantidadePrevista) {
        if (nomeMaterial == null || nomeMaterial.isBlank()) {
            throw new IllegalArgumentException("Nome do material é obrigatório.");
        }
        if (quantidadePrevista == null || quantidadePrevista.signum() <= 0) {
            throw new IllegalArgumentException("A quantidade prevista deve ser maior que zero.");
        }
    }

    private Material obterMaterialEstoque(Long materialId) {
        if (materialId == null) {
            throw new IllegalArgumentException("Selecione um material cadastrado no estoque.");
        }

        return materialRepository.findByIdForUpdate(materialId)
                .orElseThrow(() -> new IllegalArgumentException("Material do estoque não encontrado."));
    }

    private void validarSaldoReserva(Material material, BigDecimal quantidadePrevista, BigDecimal creditoReservaAtual) {
        BigDecimal saldoLivre = saldoLivreParaPlanejamento(material).add(creditoReservaAtual);
        if (quantidadePrevista.compareTo(saldoLivre) > 0) {
            throw new SaldoInsuficienteException(
                    "Estoque insuficiente para " + material.getNome() + ". Disponível: "
                            + saldoLivre + ", previsto: " + quantidadePrevista + ".");
        }
    }

    private void reservarEstoque(Comarca comarca, MaterialItem item, BigDecimal quantidade) {
        Material material = bloquearMaterial(item.getMaterial());
        item.setMaterial(material);
        if (controlaMetragem(material)) {
            material.setMetragemReservada(metragemReservada(material).add(quantidade));
        } else {
            material.setQuantidadeReservada(quantidadeReservada(material) + quantidade.intValueExact());
        }
        materialRepository.save(material);

        saldoLocalService.sincronizarReservas(material);
        registrarMovimentacaoEstoque(comarca, material, quantidade, TipoMovimentacao.RESERVA,
                montarObservacaoReserva(comarca, item));

        item.setEstoqueReservado(true);
        materialItemRepository.save(item);
    }

    private void liberarReservaEstoque(Comarca comarca, MaterialItem item, BigDecimal quantidade) {
        if (!Boolean.TRUE.equals(item.getEstoqueReservado()) || item.getMaterial() == null) {
            return;
        }

        Material material = bloquearMaterial(item.getMaterial());
        item.setMaterial(material);
        if (controlaMetragem(material)) {
            material.setMetragemReservada(metragemReservada(material).subtract(quantidade).max(BigDecimal.ZERO));
        } else {
            material.setQuantidadeReservada(Math.max(0,
                    quantidadeReservada(material) - quantidade.intValueExact()));
        }
        materialRepository.save(material);

        saldoLocalService.sincronizarReservas(material);
        registrarMovimentacaoEstoque(comarca, material, quantidade, TipoMovimentacao.ESTORNO_RESERVA,
                montarObservacaoReserva(comarca, item));

        item.setEstoqueReservado(false);
        materialItemRepository.save(item);
    }

    private Material bloquearMaterial(Material material) {
        if (material == null || material.getId() == null) {
            throw new IllegalArgumentException("Material do estoque não encontrado.");
        }
        return materialRepository.findByIdForUpdate(material.getId())
                .orElseThrow(() -> new IllegalArgumentException("Material do estoque não encontrado."));
    }

    private void registrarMovimentacaoEstoque(Comarca comarca, Material material, BigDecimal quantidade, TipoMovimentacao tipo,
            String observacao) {
        MovimentacaoEstoque movimentacao = new MovimentacaoEstoque();
        movimentacao.setMaterial(material);
        movimentacao.setUnidadeMedida(material.getUnidadeMedida());
        if (controlaMetragem(material)) {
            movimentacao.setMetragem(quantidade);
        } else {
            movimentacao.setQuantidade(quantidade.intValueExact());
        }
        if (!controlaMetragem(material) && material.getComprimentoPorPeca() != null) {
            movimentacao.setMetragem(material.getComprimentoPorPeca()
                    .multiply(quantidade));
        }
        movimentacao.setTipo(tipo);
        movimentacao.setDataMovimentacao(LocalDateTime.now());
        movimentacao.setObservacao(observacao);
        movimentacao.setComarca(comarca);
        movimentacao.setOrdemServico(comarca.getOrdemServico());
        movimentacao.setProjeto(resolverProjetoDaComarca(comarca));
        BigDecimal saldoPosterior = saldoLivreParaPlanejamento(material);
        movimentacao.setSaldoPosterior(saldoPosterior);
        if (TipoMovimentacao.RESERVA.equals(tipo)) {
            movimentacao.setSaldoAnterior(saldoPosterior.add(quantidade));
            movimentacao.setMotivo("Reserva para OS");
        } else if (TipoMovimentacao.ESTORNO_RESERVA.equals(tipo)) {
            movimentacao.setSaldoAnterior(saldoPosterior.subtract(quantidade));
            movimentacao.setMotivo("Liberação de reserva da OS");
        } else {
            movimentacao.setSaldoAnterior(saldoPosterior);
            movimentacao.setMotivo(observacao);
        }
        movimentacao.setLancadoPor("Sistema");
        movimentacao.setEstoqueOrigem(material.getLocalizacao());
        movimentacao.setEstoqueDestino(comarca.getNomeComarca());
        movimentacaoEstoqueRepository.save(movimentacao);
    }

    private void validarMateriaisVinculadosAoEstoque(List<MaterialItem> materiais) {
        materiais.stream()
                .filter(material -> material.getMaterial() == null)
                .findFirst()
                .ifPresent(material -> {
                    throw new IllegalArgumentException(
                            "O material previsto \"" + material.getNomeMaterial()
                                    + "\" precisa estar vinculado ao estoque antes da homologação.");
                });
    }

    private void validarConsolidacaoFisicaDaOr(Comarca comarca, List<MaterialItem> materiais) {
        Map<Long, BigDecimal> auditadoPorMaterial = new HashMap<>();
        Map<Long, Material> materiaisPorId = new HashMap<>();

        for (MaterialItem item : materiais) {
            if (!Boolean.TRUE.equals(item.getEstoqueBaixado())) {
                throw new IllegalArgumentException(
                        "Execute a Ordem de Retirada antes de homologar o material \""
                                + item.getNomeMaterial() + "\".");
            }
            Long materialId = item.getMaterial().getId();
            materiaisPorId.put(materialId, item.getMaterial());
            auditadoPorMaterial.merge(materialId, item.getQuantidadeAuditada(), BigDecimal::add);
        }

        List<MovimentacaoEstoque> movimentacoes = movimentacaoEstoqueRepository
                .findByComarcaIdOrderByDataMovimentacaoDesc(comarca.getId());
        Map<Long, BigDecimal> retiradoPorMaterial = somarMovimentacoesPorMaterial(movimentacoes,
                TipoMovimentacao.RETIRADA_OR);
        Map<Long, BigDecimal> devolvidoPorMaterial = somarMovimentacoesPorMaterial(movimentacoes,
                TipoMovimentacao.DEVOLUCAO_OR);

        for (Map.Entry<Long, BigDecimal> entrada : auditadoPorMaterial.entrySet()) {
            Long materialId = entrada.getKey();
            Material material = materiaisPorId.get(materialId);
            BigDecimal auditado = entrada.getValue();
            BigDecimal retirado = retiradoPorMaterial.getOrDefault(materialId, BigDecimal.ZERO);
            BigDecimal devolvido = devolvidoPorMaterial.getOrDefault(materialId, BigDecimal.ZERO);
            BigDecimal consumoLiquido = retirado.subtract(devolvido);

            if ("FERRAMENTA".equals(material.getCategoria())) {
                if (retirado.signum() <= 0 || consumoLiquido.signum() != 0) {
                    throw new IllegalArgumentException(
                            "A ferramenta \"" + material.getNome()
                                    + "\" precisa ser retirada por OR e devolvida integralmente antes da homologação.");
                }
                continue;
            }

            if (consumoLiquido.compareTo(auditado) != 0) {
                throw new IllegalArgumentException(
                        "Conciliação pendente para \"" + material.getNome() + "\": retirado " + retirado
                                + ", devolvido " + devolvido + ", consumo líquido " + consumoLiquido
                                + " e auditado " + auditado + ". Ajuste a auditoria ou conclua a devolução da OR.");
            }
        }
    }

    private Map<Long, BigDecimal> somarMovimentacoesPorMaterial(List<MovimentacaoEstoque> movimentacoes,
            TipoMovimentacao tipo) {
        return movimentacoes.stream()
                .filter(movimentacao -> tipo.equals(movimentacao.getTipo()))
                .filter(movimentacao -> movimentacao.getMaterial() != null)
                .collect(Collectors.groupingBy(
                        movimentacao -> movimentacao.getMaterial().getId(),
                        Collectors.reducing(BigDecimal.ZERO, this::valorMovimentacao, BigDecimal::add)));
    }

    private String montarObservacaoReserva(Comarca comarca, MaterialItem item) {
        String numeroOs = comarca.getOrdemServico() != null ? comarca.getOrdemServico().getNumeroOs() : "OS não vinculada";
        return "Reserva de material previsto | " + numeroOs + " | " + comarca.getNomeComarca()
                + " | Material previsto #" + item.getId();
    }

    private Projeto resolverProjetoDaComarca(Comarca comarca) {
        if (comarca.getProjeto() != null) {
            return comarca.getProjeto();
        }
        if (comarca.getOrdemServico() != null) {
            return comarca.getOrdemServico().getProjeto();
        }
        return null;
    }

    private int quantidadeReservada(Material material) {
        return material.getQuantidadeReservada() != null ? material.getQuantidadeReservada() : 0;
    }

    private BigDecimal metragemReservada(Material material) {
        return material.getMetragemReservada() != null ? material.getMetragemReservada() : BigDecimal.ZERO;
    }

    private BigDecimal saldoLivreParaPlanejamento(Material material) {
        if (controlaMetragem(material)) {
            BigDecimal disponivel = material.getMetragemDisponivel() != null
                    ? material.getMetragemDisponivel()
                    : BigDecimal.ZERO;
            return disponivel.subtract(metragemReservada(material)).max(BigDecimal.ZERO);
        }
        int disponivel = material.getQuantidadeDisponivel() != null ? material.getQuantidadeDisponivel() : 0;
        return BigDecimal.valueOf(Math.max(0, disponivel - quantidadeReservada(material)));
    }

    private BigDecimal valorMovimentacao(MovimentacaoEstoque movimentacao) {
        if (movimentacao.getMaterial() != null && controlaMetragem(movimentacao.getMaterial())) {
            return movimentacao.getMetragem() != null ? movimentacao.getMetragem() : BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(movimentacao.getQuantidade() != null ? movimentacao.getQuantidade() : 0);
    }

    private boolean controlaMetragem(Material material) {
        return material != null && (com.poprc.demo.model.TipoControleEstoque.FRACIONADO.equals(material.getTipoControle())
                || com.poprc.demo.model.TipoControleEstoque.METRAGEM.equals(material.getTipoControle())
                || com.poprc.demo.model.TipoControleEstoque.BOBINA.equals(material.getTipoControle())
                || com.poprc.demo.model.TipoControleEstoque.ROLO.equals(material.getTipoControle()));
    }

    private void validarEscalaQuantidade(Material material, BigDecimal quantidade) {
        if (!controlaMetragem(material) && quantidade != null
                && quantidade.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("O material \"" + material.getNome()
                    + "\" deve ser informado em quantidade inteira.");
        }
    }

    private Comarca recarregarComarca(Long comarcaId) {
        return comarcaRepository.findById(comarcaId)
                .orElseThrow(() -> new IllegalArgumentException("Comarca não encontrada."));
    }

    private void sincronizarStatusAsBuilt(Comarca comarca) {
        if (comarca == null) {
            return;
        }

        List<MaterialItem> materiais = materiaisDaComarca(comarca);
        boolean conciliado = materiaisConciliados(materiais);
        if (materiais.isEmpty()) {
            comarca.setAsBuiltStatus(AS_BUILT_PENDENTE);
        } else if (!conciliado) {
            comarca.setAsBuiltStatus(AS_BUILT_DIVERGENTE);
        } else if (!AS_BUILT_HOMOLOGADO.equals(comarca.getAsBuiltStatus())) {
            comarca.setAsBuiltStatus(AS_BUILT_PENDENTE);
        }
        comarcaRepository.save(comarca);
    }

    private void validarAsBuiltAbertoParaMateriais(Comarca comarca) {
        if (AS_BUILT_HOMOLOGADO.equals(comarca.getAsBuiltStatus())
                || AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA.equals(comarca.getAsBuiltStatus())) {
            throw new IllegalArgumentException("Reabra o As-Built antes de alterar materiais da obra.");
        }
    }

    private String resolverStatusAsBuilt(Comarca comarca, boolean conciliado, boolean temMateriais) {
        if (!temMateriais) {
            return AS_BUILT_PENDENTE;
        }
        if (AS_BUILT_REABERTO.equals(comarca.getAsBuiltStatus())) {
            return AS_BUILT_REABERTO;
        }
        if (AS_BUILT_HOMOLOGADO.equals(comarca.getAsBuiltStatus()) && conciliado) {
            return AS_BUILT_HOMOLOGADO;
        }
        if (AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA.equals(comarca.getAsBuiltStatus())) {
            return AS_BUILT_HOMOLOGADO_COM_DIVERGENCIA;
        }
        if (!conciliado) {
            return AS_BUILT_DIVERGENTE;
        }
        return AS_BUILT_PENDENTE;
    }

    private boolean materiaisConciliados(List<MaterialItem> materiais) {
        return !materiais.isEmpty()
                && materiais.stream().allMatch(mat -> mat.getQuantidadePrevista()
                        .compareTo(mat.getQuantidadeAuditada()) == 0);
    }

    private List<MaterialItem> materiaisDaComarca(Comarca comarca) {
        return comarca.getMateriais() != null ? comarca.getMateriais() : List.of();
    }

    private String salvarArquivoFotoVistoria(MultipartFile foto) {
        return salvarArquivoImagem(foto, DIRETORIO_FOTO_VISTORIA, "/uploads/comarcas/vistoria/");
    }

    private void validarVistoriaEditavel(Comarca comarca) {
        int etapaAtual = comarca.getEtapaAtual() != null ? comarca.getEtapaAtual() : 1;
        if (etapaAtual > 1 || OBRA_CONCLUIDA.equals(comarca.getSituacao())) {
            throw new IllegalStateException(
                    "A foto e a assinatura da vistoria só podem ser alteradas antes do avanço para Infraestrutura.");
        }
    }

    private void validarViradaRedeEditavel(Comarca comarca) {
        if (Boolean.TRUE.equals(comarca.getViradaRedeConcluida())
                || OBRA_CONCLUIDA.equals(comarca.getSituacao())) {
            throw new IllegalStateException(
                    "A prova de funcionamento não pode ser alterada após a conclusão da Virada de Rede.");
        }
    }

    private ArquivoObra carregarArquivoObra(String arquivoUrl, String diretorio, String urlBase) {
        if (arquivoUrl == null || arquivoUrl.isBlank() || !arquivoUrl.startsWith(urlBase)) {
            throw new IllegalArgumentException("Arquivo da obra não encontrado.");
        }
        Path pastaPermitida = UploadStorage.directory(diretorio)
                .toAbsolutePath().normalize();
        Path arquivo = pastaPermitida.resolve(Paths.get(arquivoUrl).getFileName().toString())
                .toAbsolutePath().normalize();
        if (!arquivo.startsWith(pastaPermitida)) {
            throw new IllegalStateException("Caminho de evidência inválido.");
        }
        try {
            if (!Files.isRegularFile(arquivo)) {
                throw new IllegalArgumentException("Arquivo da obra não encontrado.");
            }
            String contentType = Files.probeContentType(arquivo);
            if (contentType == null || !contentType.startsWith("image/")) {
                contentType = arquivo.getFileName().toString().toLowerCase().endsWith(".png")
                        ? "image/png"
                        : "image/jpeg";
            }
            return new ArquivoObra(
                    arquivo.getFileName().toString(),
                    contentType,
                    Files.readAllBytes(arquivo));
        } catch (IOException ex) {
            throw new IllegalStateException("Não foi possível ler o arquivo da obra.", ex);
        }
    }

    private void removerArquivoUpload(String arquivoUrl, String diretorio, String urlBase) {
        if (arquivoUrl == null || arquivoUrl.isBlank() || !arquivoUrl.startsWith(urlBase)) {
            return;
        }

        Path pastaPermitida = UploadStorage.directory(diretorio)
                .toAbsolutePath().normalize();
        Path arquivo = pastaPermitida.resolve(Paths.get(arquivoUrl).getFileName().toString())
                .toAbsolutePath().normalize();
        if (!arquivo.startsWith(pastaPermitida)) {
            throw new IllegalStateException("Caminho de evidência inválido.");
        }

        try {
            Files.deleteIfExists(arquivo);
        } catch (IOException e) {
            throw new RuntimeException("Não foi possível remover a evidência do servidor.", e);
        }
    }

    private String salvarArquivoImagem(MultipartFile foto, String diretorio, String urlBase) {
        String extensao = extrairExtensao(foto.getOriginalFilename());

        if (!EXTENSOES_FOTO_VISTORIA.contains(extensao.toLowerCase())
                || !MIME_FOTO_VISTORIA.contains(foto.getContentType())) {
            throw new ArquivoInvalidoException("Formato de arquivo não permitido. Envie apenas imagens .jpg, .jpeg ou .png.");
        }

        Path pastaDestino = UploadStorage.directory(diretorio);
        try {
            Files.createDirectories(pastaDestino);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao preparar diretório de upload da vistoria.", e);
        }

        String nomeUnico = UUID.randomUUID() + "." + extensao.toLowerCase();
        Path caminhoAbsoluto = pastaDestino.resolve(nomeUnico);

        try {
            foto.transferTo(caminhoAbsoluto.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Erro ao salvar foto da vistoria no servidor.", e);
        }

        return urlBase + nomeUnico;
    }

    private void sincronizarPercentualVistoria(Comarca comarca) {
        int percentual = 0;

        if (comarca.getFotoVistoriaUrl() != null && !comarca.getFotoVistoriaUrl().isBlank()) {
            percentual += 50;
        }

        if (comarca.getAssinaturaBase64() != null && !comarca.getAssinaturaBase64().isBlank()) {
            percentual += 50;
        }

        comarca.setPercentualConcluido(BigDecimal.valueOf(percentual));

        if (percentual == 100 && (comarca.getEtapaAtual() == null || comarca.getEtapaAtual() == 1)) {
            comarca.setSituacao("VISTORIA_CONCLUIDA");
        } else if (percentual > 0 && (comarca.getEtapaAtual() == null || comarca.getEtapaAtual() == 1)) {
            comarca.setSituacao("VISTORIA_EM_ANDAMENTO");
        }
    }

    public record ArquivoObra(String nomeArquivo, String contentType, byte[] conteudo) {
    }

    private String extrairExtensao(String nomeArquivo) {
        if (nomeArquivo == null || !nomeArquivo.contains(".")) {
            throw new ArquivoInvalidoException("Arquivo enviado sem extensão válida.");
        }
        return nomeArquivo.substring(nomeArquivo.lastIndexOf(".") + 1);
    }
}
