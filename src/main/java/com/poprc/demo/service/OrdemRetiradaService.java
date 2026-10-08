package com.poprc.demo.service;

import com.poprc.demo.dto.DevolverOrdemRetiradaRequest;
import com.poprc.demo.dto.ExecutarOrdemRetiradaRequest;
import com.poprc.demo.exception.SaldoInsuficienteException;
import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.MaterialItem;
import com.poprc.demo.model.MovimentacaoEstoque;
import com.poprc.demo.model.OrdemRetirada;
import com.poprc.demo.model.OrdemRetiradaItem;
import com.poprc.demo.model.OrdemRetiradaAlocacao;
import com.poprc.demo.model.OrdemServico;
import com.poprc.demo.model.StatusUnidadeRastreavel;
import com.poprc.demo.model.UnidadeEstoqueRastreavel;
import com.poprc.demo.model.TipoMovimentacao;
import com.poprc.demo.model.TipoControleEstoque;
import com.poprc.demo.repository.MaterialItemRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.MovimentacaoEstoqueRepository;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.OrdemRetiradaRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.repository.OrdemRetiradaAlocacaoRepository;
import com.poprc.demo.repository.UnidadeEstoqueRastreavelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrdemRetiradaService implements OrdemRetiradaPort {

    private static final String STATUS_GERADA = "GERADA";
    private static final String STATUS_RETIRADA = "RETIRADA";
    private static final String STATUS_DEVOLVIDA = "DEVOLVIDA";
    private static final String CATEGORIA_FERRAMENTA = "FERRAMENTA";

    private final OrdemRetiradaRepository ordemRetiradaRepository;
    private final OrdemServicoRepository ordemServicoRepository;
    private final ComarcaRepository comarcaRepository;
    private final MaterialRepository materialRepository;
    private final MaterialItemRepository materialItemRepository;
    private final MovimentacaoEstoqueRepository movimentacaoEstoqueRepository;
    private final UnidadeEstoqueRastreavelRepository unidadeRastreavelRepository;
    private final OrdemRetiradaAlocacaoRepository alocacaoRepository;
    private final SaldoLocalService saldoLocalService;
    private final FluxoOrdemServicoService fluxoOrdemServicoService;
    private final OrdemRetiradaPdfService ordemRetiradaPdfService;
    private final EvidenciaMetragemOrService evidenciaMetragemOrService;
    private final SimulacaoDocumentoService simulacaoDocumentoService;

    @Transactional
    public OrdemRetirada criarParaOrdemServico(OrdemServico ordemServico, Comarca comarca, String geradoPor) {
        if (ordemServico == null || ordemServico.getId() == null) {
            throw new IllegalArgumentException("OS é obrigatória para gerar a OR.");
        }
        if (comarca == null || comarca.getMateriais() == null || comarca.getMateriais().isEmpty()) {
            throw new IllegalArgumentException("A OS precisa ter materiais previstos para gerar uma OR.");
        }
        ordemServico = ordemServicoRepository.findByIdForUpdate(ordemServico.getId())
                .orElseThrow(() -> new IllegalArgumentException("OS não encontrada."));
        simulacaoDocumentoService.exigirHabilitada(ordemServico.isSimulacao());

        OrdemRetirada ordemRetirada = new OrdemRetirada();
        ordemRetirada.setNumeroOr(gerarNumeroOr(ordemServico));
        ordemRetirada.setStatus(STATUS_GERADA);
        ordemRetirada.setOrdemServico(ordemServico);
        ordemRetirada.setComarca(comarca);
        ordemRetirada.setGeradoPor(geradoPor != null && !geradoPor.isBlank() ? geradoPor : "Sistema");
        ordemRetirada.setDataGeracao(LocalDateTime.now());

        for (MaterialItem materialItem : comarca.getMateriais()) {
            if (materialItem.getMaterial() == null) {
                continue;
            }
            OrdemRetiradaItem item = new OrdemRetiradaItem();
            item.setOrdemRetirada(ordemRetirada);
            item.setMaterialItem(materialItem);
            item.setMaterial(materialItem.getMaterial());
            item.setNomeMaterial(materialItem.getNomeMaterial());
            item.setCategoria(materialItem.getMaterial().getCategoria());
            item.setQuantidadeSolicitada(materialItem.getQuantidadePrevista());
            ordemRetirada.getItens().add(item);
        }

        if (ordemRetirada.getItens().isEmpty()) {
            throw new IllegalArgumentException("A OR precisa ter ao menos um item vinculado ao estoque.");
        }

        OrdemRetirada salva = ordemRetiradaRepository.saveAndFlush(ordemRetirada);
        ordemRetiradaPdfService.arquivar(salva, OrdemRetiradaPdfService.FASE_GERACAO);
        return salva;
    }

    @Transactional
    public OrdemRetirada criarAdicionalParaOs(Long ordemServicoId, String geradoPor) {
        OrdemServico ordemServico = ordemServicoRepository.findByIdForUpdate(ordemServicoId)
                .orElseThrow(() -> new IllegalArgumentException("OS não encontrada."));
        if (Boolean.TRUE.equals(ordemServico.getArquivado())) {
            throw new IllegalStateException("Não é possível gerar Ordem de Retirada para uma OS arquivada.");
        }
        if (ordemRetiradaRepository.existsByOrdemServicoIdAndStatusIn(
                ordemServicoId, List.of(STATUS_GERADA, STATUS_RETIRADA))) {
            throw new IllegalStateException(
                    "Conclua a retirada e a devolução da OR ativa antes de gerar uma nova OR para esta OS.");
        }
        if (ordemServico.getProjeto() == null || ordemServico.getProjeto().getId() == null) {
            throw new IllegalArgumentException("OS sem projeto/comarca vinculada.");
        }
        Comarca comarca = comarcaRepository.findByProjetoId(ordemServico.getProjeto().getId())
                .orElseThrow(() -> new IllegalArgumentException("Comarca da OS não encontrada."));
        return criarParaOrdemServico(ordemServico, comarca, geradoPor);
    }

    @Transactional(readOnly = true)
    public List<OrdemRetirada> listarTodas() {
        return ordemRetiradaRepository.findAllByOrderByDataGeracaoDesc();
    }

    @Transactional(readOnly = true)
    public List<OrdemRetirada> listarPorComarca(Long comarcaId) {
        return ordemRetiradaRepository.findByComarcaIdOrderByDataGeracaoDesc(comarcaId);
    }

    @Transactional(readOnly = true)
    public List<OrdemRetirada> listarPorOs(Long ordemServicoId) {
        return ordemRetiradaRepository.findByOrdemServicoIdOrderByDataGeracaoDesc(ordemServicoId);
    }

    @Transactional
    public OrdemRetirada executarRetirada(Long id, ExecutarOrdemRetiradaRequest request) {
        OrdemRetirada ordemRetirada = buscar(id);
        simulacaoDocumentoService.exigirHabilitada(ordemRetirada.isSimulacao());
        if (!STATUS_GERADA.equals(ordemRetirada.getStatus())) {
            throw new IllegalArgumentException("Esta OR não está disponível para retirada.");
        }
        validarTexto(request.getConferidoPor(), "Informe quem conferiu os itens.");
        validarTexto(request.getLevadoPor(), "Informe quem levou os itens.");
        validarTexto(request.getAssinaturaConferenteBase64(), "Assinatura de quem conferiu é obrigatória.");
        validarTexto(request.getAssinaturaRetiranteBase64(), "Assinatura de quem levou é obrigatória.");
        if (ordemRetirada.isSimulacao()) {
            request.setConferidoPor("TESTE FICTÍCIO — CONFERENTE");
            request.setLevadoPor("TESTE FICTÍCIO — RETIRANTE");
        }
        fluxoOrdemServicoService.validarRetiradaPermitida(ordemRetirada.getOrdemServico().getId());

        Map<Long, List<ExecutarOrdemRetiradaRequest.AlocacaoRequest>> alocacoesPorItem = request.getAlocacoes() == null
                ? Map.of()
                : request.getAlocacoes().stream()
                        .filter(alocacao -> alocacao.getItemId() != null)
                        .collect(Collectors.groupingBy(ExecutarOrdemRetiradaRequest.AlocacaoRequest::getItemId));
        Map<ExecutarOrdemRetiradaRequest.AlocacaoRequest, EvidenciaMetragemOrService.EvidenciaPreparada>
                evidenciasRetirada = new IdentityHashMap<>();
        alocacoesPorItem.values().stream()
                .flatMap(List::stream)
                .forEach(alocacao -> evidenciasRetirada.put(
                        alocacao,
                        evidenciaMetragemOrService.preparar(
                                alocacao.getEvidenciaFotoBase64(),
                                alocacao.getEvidenciaFotoNome())));

        ordemRetirada.setConferidoPor(request.getConferidoPor().trim());
        ordemRetirada.setLevadoPor(request.getLevadoPor().trim());

        LocalDateTime agora = LocalDateTime.now();
        for (OrdemRetiradaItem item : itensOrdenados(ordemRetirada)) {
            Material material = bloquearMaterial(item.getMaterial());
            item.setMaterial(material);
            BigDecimal quantidade = valor(item.getQuantidadeSolicitada());
            String origemLocal = null;
            if (rastreavel(material)) {
                retirarDeUnidadesRastreaveis(ordemRetirada, item, quantidade,
                        alocacoesPorItem.getOrDefault(item.getId(), List.of()), request,
                        evidenciasRetirada);
            } else if (controlaMetragem(material)) {
                BigDecimal disponivel = valor(material.getMetragemDisponivel());
                if (quantidade.compareTo(disponivel) > 0) {
                    throw new SaldoInsuficienteException("Metragem insuficiente para retirar " + material.getNome()
                            + ". Em estoque: " + disponivel + " m, solicitado: " + quantidade + " m.");
                }
                material.setMetragemDisponivel(disponivel.subtract(quantidade));
                material.setMetragemReservada(valor(material.getMetragemReservada())
                        .subtract(quantidade).max(BigDecimal.ZERO));
            } else {
                int quantidadeInteira = quantidade.intValueExact();
                int disponivel = valor(material.getQuantidadeDisponivel());
                int reservado = valor(material.getQuantidadeReservada());
                if (quantidadeInteira > disponivel) {
                    throw new SaldoInsuficienteException("Estoque insuficiente para retirar " + material.getNome()
                            + ". Em estoque: " + disponivel + ", solicitado: " + quantidadeInteira + ".");
                }
                material.setQuantidadeDisponivel(disponivel - quantidadeInteira);
                material.setQuantidadeReservada(Math.max(0, reservado - quantidadeInteira));
            }
            if (!rastreavel(material)) {
                materialRepository.save(material);
                origemLocal = saldoLocalService.descreverMovimentos(
                        saldoLocalService.debitarDistribuido(material, quantidade));
            }

            saldoLocalService.sincronizarReservas(material);
            item.setQuantidadeRetirada(quantidade);
            MaterialItem materialItem = item.getMaterialItem();
            if (materialItem != null) {
                materialItem.setEstoqueReservado(false);
                materialItem.setEstoqueBaixado(true);
                materialItem.setDataHoraRetirada(agora);
                materialItemRepository.save(materialItem);
            }
            if (!rastreavel(material)) {
                registrarMovimentacao(ordemRetirada, item, quantidade, TipoMovimentacao.RETIRADA_OR,
                        "Retirada via " + ordemRetirada.getNumeroOr() + " | Conferente: "
                                + request.getConferidoPor() + " | Levou: " + request.getLevadoPor(), null,
                        origemLocal, null);
            }
        }

        ordemRetirada.setStatus(STATUS_RETIRADA);
        ordemRetirada.setDataRetirada(agora);
        ordemRetirada.setConferidoPor(request.getConferidoPor().trim());
        ordemRetirada.setLevadoPor(request.getLevadoPor().trim());
        ordemRetirada.setAssinaturaConferenteBase64(request.getAssinaturaConferenteBase64());
        ordemRetirada.setAssinaturaRetiranteBase64(request.getAssinaturaRetiranteBase64());
        OrdemRetirada salva = ordemRetiradaRepository.save(ordemRetirada);
        fluxoOrdemServicoService.registrarRetirada(
                salva.getOrdemServico().getId(), request.getLevadoPor());
        ordemRetiradaPdfService.arquivar(salva, OrdemRetiradaPdfService.FASE_RETIRADA);
        return salva;
    }

    @Transactional
    public OrdemRetirada devolver(Long id, DevolverOrdemRetiradaRequest request) {
        OrdemRetirada ordemRetirada = buscar(id);
        simulacaoDocumentoService.exigirHabilitada(ordemRetirada.isSimulacao());
        if (!STATUS_RETIRADA.equals(ordemRetirada.getStatus())) {
            throw new IllegalArgumentException("A OR precisa estar retirada para registrar devolução.");
        }
        validarTexto(request.getDevolvidoPor(), "Informe quem devolveu os itens.");
        validarTexto(request.getRecebidoPor(), "Informe quem conferiu/recebeu a devolução.");
        validarTexto(request.getAssinaturaRecebimentoBase64(), "Assinatura de recebimento é obrigatória.");
        if (ordemRetirada.isSimulacao()) {
            request.setDevolvidoPor("TESTE FICTÍCIO — DEVOLUÇÃO");
            request.setRecebidoPor("TESTE FICTÍCIO — RECEBIMENTO");
        }

        Map<Long, DevolverOrdemRetiradaRequest.ItemDevolucaoRequest> devolucoes = request.getItens() == null
                ? Map.of()
                : request.getItens().stream()
                        .filter(item -> item.getItemId() != null)
                        .collect(Collectors.toMap(
                                DevolverOrdemRetiradaRequest.ItemDevolucaoRequest::getItemId,
                                item -> item,
                                (a, b) -> b));
        Map<Long, DevolverOrdemRetiradaRequest.AlocacaoDevolucaoRequest> devolucoesAlocacao = request.getAlocacoes() == null
                ? Map.of()
                : request.getAlocacoes().stream()
                        .filter(alocacao -> alocacao.getAlocacaoId() != null)
                        .collect(Collectors.toMap(
                                DevolverOrdemRetiradaRequest.AlocacaoDevolucaoRequest::getAlocacaoId,
                                alocacao -> alocacao,
                                (a, b) -> b));
        Map<Long, EvidenciaMetragemOrService.EvidenciaPreparada> evidenciasDevolucao =
                prepararEvidenciasDevolucao(ordemRetirada, devolucoesAlocacao);
        ordemRetirada.setDevolvidoPor(request.getDevolvidoPor().trim());
        ordemRetirada.setRecebidoPor(request.getRecebidoPor().trim());

        for (OrdemRetiradaItem item : itensOrdenados(ordemRetirada)) {
            Material materialBloqueado = bloquearMaterial(item.getMaterial());
            item.setMaterial(materialBloqueado);
            BigDecimal retirada = valor(item.getQuantidadeRetirada());
            BigDecimal devolvida = rastreavel(item.getMaterial())
                    ? devolverUnidadesRastreaveis(
                            ordemRetirada, item, devolucoesAlocacao, request, evidenciasDevolucao)
                    : valor(devolucoes.getOrDefault(item.getId(),
                            new DevolverOrdemRetiradaRequest.ItemDevolucaoRequest()).getQuantidadeDevolvida());
            if (devolvida.signum() < 0 || devolvida.compareTo(retirada) > 0) {
                throw new IllegalArgumentException("Quantidade devolvida inválida para " + item.getNomeMaterial() + ".");
            }
            if (CATEGORIA_FERRAMENTA.equals(item.getCategoria()) && devolvida.compareTo(retirada) < 0) {
                throw new IllegalArgumentException("Ferramentas devem retornar obrigatoriamente: " + item.getNomeMaterial() + ".");
            }

            item.setQuantidadeDevolvida(devolvida);
            if (devolvida.signum() > 0 && !rastreavel(item.getMaterial())) {
                Material material = item.getMaterial();
                if (controlaMetragem(material)) {
                    material.setMetragemDisponivel(valor(material.getMetragemDisponivel()).add(devolvida));
                } else {
                    material.setQuantidadeDisponivel(valor(material.getQuantidadeDisponivel())
                            + devolvida.intValueExact());
                }
                materialRepository.save(material);
                String destinoLocal = saldoLocalService.creditarPadrao(material, devolvida).getNome();
                registrarMovimentacao(ordemRetirada, item, devolvida, TipoMovimentacao.DEVOLUCAO_OR,
                        "Devolução via " + ordemRetirada.getNumeroOr() + " | Devolveu: "
                                + request.getDevolvidoPor() + " | Recebeu: " + request.getRecebidoPor(), null,
                        null, destinoLocal);
            }

            // O item permanece marcado como baixado para impedir baixa duplicada no As-Built.
            // A volta física ao estoque é registrada pela movimentação DEVOLUCAO_OR.
        }

        ordemRetirada.setStatus(STATUS_DEVOLVIDA);
        ordemRetirada.setDataDevolucao(LocalDateTime.now());
        ordemRetirada.setDevolvidoPor(request.getDevolvidoPor().trim());
        ordemRetirada.setRecebidoPor(request.getRecebidoPor().trim());
        ordemRetirada.setAssinaturaRecebimentoBase64(request.getAssinaturaRecebimentoBase64());
        OrdemRetirada salva = ordemRetiradaRepository.save(ordemRetirada);
        fluxoOrdemServicoService.registrarDevolucao(
                salva.getOrdemServico().getId(), request.getRecebidoPor());
        ordemRetiradaPdfService.arquivar(salva, OrdemRetiradaPdfService.FASE_DEVOLUCAO);
        return salva;
    }

    private OrdemRetirada buscar(Long id) {
        return ordemRetiradaRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Ordem de Retirada não encontrada."));
    }

    private List<OrdemRetiradaItem> itensOrdenados(OrdemRetirada ordemRetirada) {
        return ordemRetirada.getItens().stream()
                .sorted(Comparator.comparing(item -> item.getMaterial().getId()))
                .toList();
    }

    private Material bloquearMaterial(Material material) {
        if (material == null || material.getId() == null) {
            throw new IllegalArgumentException("Material da OR não encontrado.");
        }
        return materialRepository.findByIdForUpdate(material.getId())
                .orElseThrow(() -> new IllegalArgumentException("Material da OR não encontrado."));
    }

    private String gerarNumeroOr(OrdemServico ordemServico) {
        long sequencial = ordemRetiradaRepository.countByOrdemServicoId(ordemServico.getId()) + 1;
        String numero;
        do {
            numero = ordemServico.getNumeroOs() + " - OR " + String.format("%02d", sequencial);
            sequencial++;
        } while (ordemRetiradaRepository.existsByNumeroOr(numero));
        return numero;
    }

    private void registrarMovimentacao(OrdemRetirada ordemRetirada, OrdemRetiradaItem item, BigDecimal quantidade,
            TipoMovimentacao tipo, String observacao, UnidadeEstoqueRastreavel unidadeRastreavel,
            String origemOverride, String destinoOverride) {
        MovimentacaoEstoque movimentacao = new MovimentacaoEstoque();
        movimentacao.setMaterial(item.getMaterial());
        movimentacao.setUnidadeRastreavel(unidadeRastreavel);
        movimentacao.setUnidadeMedida(item.getMaterial().getUnidadeMedida());
        if (controlaMetragem(item.getMaterial())) {
            movimentacao.setMetragem(quantidade);
        } else {
            movimentacao.setQuantidade(quantidade.intValueExact());
        }
        if (!controlaMetragem(item.getMaterial()) && item.getMaterial().getComprimentoPorPeca() != null) {
            movimentacao.setMetragem(item.getMaterial().getComprimentoPorPeca()
                    .multiply(quantidade));
        }
        movimentacao.setTipo(tipo);
        movimentacao.setDataMovimentacao(LocalDateTime.now());
        movimentacao.setObservacao(observacao);
        movimentacao.setComarca(ordemRetirada.getComarca());
        movimentacao.setProjeto(ordemRetirada.getComarca() != null ? ordemRetirada.getComarca().getProjeto() : null);
        movimentacao.setOrdemServico(ordemRetirada.getOrdemServico());
        movimentacao.setOrdemRetirada(ordemRetirada);
        BigDecimal saldoPosterior = saldoControle(item.getMaterial());
        movimentacao.setSaldoPosterior(saldoPosterior);
        movimentacao.setSaldoAnterior(TipoMovimentacao.RETIRADA_OR.equals(tipo)
                ? saldoPosterior.add(quantidade)
                : saldoPosterior.subtract(quantidade));
        movimentacao.setMotivo(TipoMovimentacao.RETIRADA_OR.equals(tipo)
                ? "Retirada vinculada à OR"
                : "Devolução vinculada à OR");
        movimentacao.setLancadoPor("Sistema");
        movimentacao.setAutorizadoPor(TipoMovimentacao.RETIRADA_OR.equals(tipo)
                ? ordemRetirada.getConferidoPor()
                : ordemRetirada.getRecebidoPor());
        movimentacao.setRetiradoPor(TipoMovimentacao.RETIRADA_OR.equals(tipo)
                ? ordemRetirada.getLevadoPor()
                : ordemRetirada.getDevolvidoPor());
        movimentacao.setEstoqueOrigem(origemOverride != null ? origemOverride : TipoMovimentacao.RETIRADA_OR.equals(tipo)
                ? item.getMaterial().getLocalizacao()
                : ordemRetirada.getComarca() != null ? ordemRetirada.getComarca().getNomeComarca() : null);
        movimentacao.setEstoqueDestino(destinoOverride != null ? destinoOverride : TipoMovimentacao.RETIRADA_OR.equals(tipo)
                ? ordemRetirada.getComarca() != null ? ordemRetirada.getComarca().getNomeComarca() : null
                : item.getMaterial().getLocalizacao());
        CustoMovimentacao custo = resolverCustoMovimentacao(ordemRetirada, item, tipo, unidadeRastreavel);
        movimentacao.setCustoUnitario(custo.unitario());
        movimentacao.setValorTotalMovimentacao(custo.unitario().multiply(quantidade)
                .setScale(4, RoundingMode.HALF_UP));
        movimentacao.setCustoEstimado(custo.estimado());
        movimentacaoEstoqueRepository.save(movimentacao);
    }

    private CustoMovimentacao resolverCustoMovimentacao(OrdemRetirada ordemRetirada, OrdemRetiradaItem item,
            TipoMovimentacao tipo, UnidadeEstoqueRastreavel unidadeRastreavel) {
        if (TipoMovimentacao.DEVOLUCAO_OR.equals(tipo)) {
            List<MovimentacaoEstoque> retiradas = movimentacaoEstoqueRepository
                    .findByOrdemRetiradaIdAndMaterialIdAndTipoOrderByDataMovimentacaoAsc(
                            ordemRetirada.getId(), item.getMaterial().getId(), TipoMovimentacao.RETIRADA_OR);
            return retiradas.stream()
                    .filter(movimentacao -> unidadeRastreavel == null
                            || movimentacao.getUnidadeRastreavel() != null
                                    && unidadeRastreavel.getId().equals(movimentacao.getUnidadeRastreavel().getId()))
                    .filter(movimentacao -> movimentacao.getCustoUnitario() != null
                            && movimentacao.getCustoUnitario().signum() >= 0)
                    .map(movimentacao -> new CustoMovimentacao(
                            movimentacao.getCustoUnitario(),
                            Boolean.TRUE.equals(movimentacao.getCustoEstimado())))
                    .findFirst()
                    .orElseGet(() -> new CustoMovimentacao(
                            valor(item.getMaterial().getCustoMedio()), true));
        }
        return new CustoMovimentacao(valor(item.getMaterial().getCustoMedio()), false);
    }

    private record CustoMovimentacao(BigDecimal unitario, boolean estimado) {
    }

    private void retirarDeUnidadesRastreaveis(OrdemRetirada ordemRetirada, OrdemRetiradaItem item,
            BigDecimal quantidadeSolicitada, List<ExecutarOrdemRetiradaRequest.AlocacaoRequest> requisicoes,
            ExecutarOrdemRetiradaRequest request,
            Map<ExecutarOrdemRetiradaRequest.AlocacaoRequest,
                    EvidenciaMetragemOrService.EvidenciaPreparada> evidencias) {
        if (requisicoes.isEmpty()) {
            throw new IllegalArgumentException("Selecione ao menos uma bobina/rolo para " + item.getNomeMaterial() + ".");
        }
        BigDecimal total = requisicoes.stream()
                .map(requisicao -> valor(requisicao.getMetragem()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(quantidadeSolicitada) != 0) {
            throw new IllegalArgumentException("A soma das bobinas/rolos de " + item.getNomeMaterial()
                    + " deve ser exatamente " + quantidadeSolicitada + " m.");
        }

        Set<Long> unidadesUsadas = new HashSet<>();
        for (ExecutarOrdemRetiradaRequest.AlocacaoRequest requisicao : requisicoes) {
            BigDecimal metragem = valor(requisicao.getMetragem());
            if (requisicao.getUnidadeRastreavelId() == null || metragem.signum() <= 0) {
                throw new IllegalArgumentException("Informe bobina/rolo e metragem válidos para " + item.getNomeMaterial() + ".");
            }
            if (!unidadesUsadas.add(requisicao.getUnidadeRastreavelId())) {
                throw new IllegalArgumentException("A mesma bobina/rolo não pode ser repetida no item.");
            }
            UnidadeEstoqueRastreavel unidade = unidadeRastreavelRepository
                    .findByIdForUpdate(requisicao.getUnidadeRastreavelId())
                    .orElseThrow(() -> new IllegalArgumentException("Bobina/rolo não encontrado."));
            if (!unidade.getMaterial().getId().equals(item.getMaterial().getId())) {
                throw new IllegalArgumentException("A bobina/rolo " + unidade.getCodigo() + " pertence a outro material.");
            }
            if (metragem.compareTo(unidade.getMetragemAtual()) > 0) {
                throw new SaldoInsuficienteException("A bobina/rolo " + unidade.getCodigo() + " possui apenas "
                        + unidade.getMetragemAtual() + " m.");
            }

            unidade.setMetragemAtual(unidade.getMetragemAtual().subtract(metragem));
            unidade.setStatus(unidade.getMetragemAtual().signum() == 0
                    ? StatusUnidadeRastreavel.ESGOTADA
                    : StatusUnidadeRastreavel.PARCIALMENTE_UTILIZADA);
            unidadeRastreavelRepository.save(unidade);

            Material material = item.getMaterial();
            material.setMetragemDisponivel(valor(material.getMetragemDisponivel()).subtract(metragem));
            materialRepository.save(material);
            String origemLocal = saldoLocalService.descreverMovimentos(
                    saldoLocalService.debitarDistribuido(material, metragem));

            OrdemRetiradaAlocacao alocacao = new OrdemRetiradaAlocacao();
            alocacao.setItem(item);
            alocacao.setUnidadeRastreavel(unidade);
            alocacao.setMetragemRetirada(metragem);
            alocacao.setMetragemDevolvida(BigDecimal.ZERO);
            EvidenciaMetragemOrService.EvidenciaSalva evidencia =
                    evidenciaMetragemOrService.salvar(evidencias.get(requisicao));
            alocacao.setEvidenciaRetiradaPath(evidencia.caminho());
            alocacao.setEvidenciaRetiradaNome(evidencia.nomeOriginal());
            alocacao.setEvidenciaRetiradaData(LocalDateTime.now());
            alocacao.setMetragemRestanteAposRetirada(unidade.getMetragemAtual());
            item.getAlocacoes().add(alocacaoRepository.save(alocacao));

            registrarMovimentacao(ordemRetirada, item, metragem, TipoMovimentacao.RETIRADA_OR,
                    "Retirada via " + ordemRetirada.getNumeroOr() + " | " + unidade.getCodigo()
                            + " | Conferente: " + request.getConferidoPor() + " | Levou: " + request.getLevadoPor(),
                    unidade, origemLocal, null);
        }

        Material material = item.getMaterial();
        material.setMetragemReservada(valor(material.getMetragemReservada()).subtract(total).max(BigDecimal.ZERO));
        materialRepository.save(material);
    }

    private BigDecimal devolverUnidadesRastreaveis(OrdemRetirada ordemRetirada, OrdemRetiradaItem item,
            Map<Long, DevolverOrdemRetiradaRequest.AlocacaoDevolucaoRequest> devolucoes,
            DevolverOrdemRetiradaRequest request,
            Map<Long, EvidenciaMetragemOrService.EvidenciaPreparada> evidencias) {
        BigDecimal totalDevolvido = BigDecimal.ZERO;
        for (OrdemRetiradaAlocacao alocacao : item.getAlocacoes()) {
            UnidadeEstoqueRastreavel unidade = unidadeRastreavelRepository
                    .findByIdForUpdate(alocacao.getUnidadeRastreavel().getId())
                    .orElseThrow(() -> new IllegalArgumentException("Bobina/rolo não encontrado."));
            alocacao.setUnidadeRastreavel(unidade);
            BigDecimal devolvida = valor(devolucoes.getOrDefault(alocacao.getId(),
                    new DevolverOrdemRetiradaRequest.AlocacaoDevolucaoRequest()).getMetragemDevolvida());
            BigDecimal saldoRetirado = valor(alocacao.getMetragemRetirada())
                    .subtract(valor(alocacao.getMetragemDevolvida()));
            if (devolvida.signum() < 0 || devolvida.compareTo(saldoRetirado) > 0) {
                throw new IllegalArgumentException("Metragem devolvida inválida para "
                        + unidade.getCodigo() + ".");
            }
            EvidenciaMetragemOrService.EvidenciaSalva evidencia =
                    evidenciaMetragemOrService.salvar(evidencias.get(alocacao.getId()));
            if (devolvida.signum() == 0) {
                alocacao.setEvidenciaDevolucaoPath(evidencia.caminho());
                alocacao.setEvidenciaDevolucaoNome(evidencia.nomeOriginal());
                alocacao.setEvidenciaDevolucaoData(LocalDateTime.now());
                alocacao.setMetragemRestanteAposDevolucao(unidade.getMetragemAtual());
                alocacaoRepository.save(alocacao);
                continue;
            }

            unidade.setMetragemAtual(unidade.getMetragemAtual().add(devolvida));
            unidade.setStatus(StatusUnidadeRastreavel.DEVOLVIDA_ESTOQUE);
            unidadeRastreavelRepository.save(unidade);
            alocacao.setMetragemDevolvida(valor(alocacao.getMetragemDevolvida()).add(devolvida));
            alocacao.setEvidenciaDevolucaoPath(evidencia.caminho());
            alocacao.setEvidenciaDevolucaoNome(evidencia.nomeOriginal());
            alocacao.setEvidenciaDevolucaoData(LocalDateTime.now());
            alocacao.setMetragemRestanteAposDevolucao(unidade.getMetragemAtual());
            alocacaoRepository.save(alocacao);

            item.getMaterial().setMetragemDisponivel(
                    valor(item.getMaterial().getMetragemDisponivel()).add(devolvida));
            String destinoLocal = saldoLocalService.creditarPadrao(item.getMaterial(), devolvida).getNome();
            totalDevolvido = totalDevolvido.add(devolvida);
            registrarMovimentacao(ordemRetirada, item, devolvida, TipoMovimentacao.DEVOLUCAO_OR,
                    "Devolução via " + ordemRetirada.getNumeroOr() + " | " + unidade.getCodigo()
                            + " | Devolveu: " + request.getDevolvidoPor() + " | Recebeu: " + request.getRecebidoPor(),
                    unidade, null, destinoLocal);
        }
        materialRepository.save(item.getMaterial());
        return totalDevolvido;
    }

    private Map<Long, EvidenciaMetragemOrService.EvidenciaPreparada> prepararEvidenciasDevolucao(
            OrdemRetirada ordemRetirada,
            Map<Long, DevolverOrdemRetiradaRequest.AlocacaoDevolucaoRequest> devolucoes) {
        Map<Long, EvidenciaMetragemOrService.EvidenciaPreparada> preparadas = new HashMap<>();
        ordemRetirada.getItens().stream()
                .flatMap(item -> item.getAlocacoes().stream())
                .forEach(alocacao -> {
                    DevolverOrdemRetiradaRequest.AlocacaoDevolucaoRequest requisicao =
                            devolucoes.get(alocacao.getId());
                    if (requisicao == null) {
                        throw new IllegalArgumentException(
                                "Informe a devolução e a foto da bobina/rolo "
                                        + alocacao.getUnidadeRastreavel().getCodigo() + ".");
                    }
                    preparadas.put(
                            alocacao.getId(),
                            evidenciaMetragemOrService.preparar(
                                    requisicao.getEvidenciaFotoBase64(),
                                    requisicao.getEvidenciaFotoNome()));
                });
        return preparadas;
    }

    @Transactional(readOnly = true)
    public EvidenciaMetragemOrService.ArquivoEvidencia carregarEvidenciaMetragem(
            Long ordemRetiradaId, Long alocacaoId, String fase) {
        OrdemRetiradaAlocacao alocacao = alocacaoRepository.findById(alocacaoId)
                .orElseThrow(() -> new IllegalArgumentException("Alocação de bobina/rolo não encontrada."));
        Long ordemDaAlocacao = alocacao.getItem().getOrdemRetirada().getId();
        if (!ordemRetiradaId.equals(ordemDaAlocacao)) {
            throw new IllegalArgumentException("A evidência não pertence à Ordem de Retirada informada.");
        }
        if ("retirada".equalsIgnoreCase(fase)) {
            return evidenciaMetragemOrService.carregar(
                    alocacao.getEvidenciaRetiradaPath(), alocacao.getEvidenciaRetiradaNome());
        }
        if ("devolucao".equalsIgnoreCase(fase)) {
            return evidenciaMetragemOrService.carregar(
                    alocacao.getEvidenciaDevolucaoPath(), alocacao.getEvidenciaDevolucaoNome());
        }
        throw new IllegalArgumentException("Fase de evidência inválida.");
    }

    private void validarTexto(String valor, String mensagem) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(mensagem);
        }
    }

    private int valor(Integer valor) {
        return valor != null ? valor : 0;
    }

    private BigDecimal valor(BigDecimal valor) {
        return valor != null ? valor : BigDecimal.ZERO;
    }

    private boolean controlaMetragem(Material material) {
        return material != null && (TipoControleEstoque.FRACIONADO.equals(material.getTipoControle())
                || TipoControleEstoque.METRAGEM.equals(material.getTipoControle())
                || TipoControleEstoque.BOBINA.equals(material.getTipoControle())
                || TipoControleEstoque.ROLO.equals(material.getTipoControle()));
    }

    private boolean rastreavel(Material material) {
        return material != null && (TipoControleEstoque.BOBINA.equals(material.getTipoControle())
                || TipoControleEstoque.ROLO.equals(material.getTipoControle()));
    }

    private BigDecimal saldoControle(Material material) {
        return controlaMetragem(material)
                ? valor(material.getMetragemDisponivel())
                : BigDecimal.valueOf(valor(material.getQuantidadeDisponivel()));
    }
}
