package com.poprc.demo.service;

import com.poprc.demo.dto.ImportacaoEstoquePlanilhaRequest;
import com.poprc.demo.dto.ImportacaoEstoquePlanilhaDetalheDTO;
import com.poprc.demo.dto.ImportacaoEstoquePlanilhaResultadoDTO;
import com.poprc.demo.dto.AtualizacaoCustosPlanilhaRequest;
import com.poprc.demo.dto.SincronizacaoSaldosPlanilhaRequest;
import com.poprc.demo.model.Comarca;
import com.poprc.demo.model.Contrato;
import com.poprc.demo.model.Funcionario;
import com.poprc.demo.model.ImportacaoEstoqueItemPlanilha;
import com.poprc.demo.model.ImportacaoEstoquePlanilha;
import com.poprc.demo.model.ImportacaoEntradaPlanilha;
import com.poprc.demo.model.ImportacaoRetornoPlanilha;
import com.poprc.demo.model.ImportacaoRetiradaPlanilha;
import com.poprc.demo.model.LocalEstoque;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.MaterialItem;
import com.poprc.demo.model.OrdemRetirada;
import com.poprc.demo.model.OrdemRetiradaItem;
import com.poprc.demo.model.OrdemServico;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.model.ProjetoStatus;
import com.poprc.demo.model.StatusOS;
import com.poprc.demo.model.TipoControleEstoque;
import com.poprc.demo.model.UnidadeMedida;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.ContratoRepository;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.repository.ImportacaoEstoqueItemPlanilhaRepository;
import com.poprc.demo.repository.ImportacaoEstoquePlanilhaRepository;
import com.poprc.demo.repository.ImportacaoEntradaPlanilhaRepository;
import com.poprc.demo.repository.ImportacaoRetornoPlanilhaRepository;
import com.poprc.demo.repository.ImportacaoRetiradaPlanilhaRepository;
import com.poprc.demo.repository.LocalEstoqueRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.MaterialItemRepository;
import com.poprc.demo.repository.OrdemRetiradaItemRepository;
import com.poprc.demo.repository.OrdemRetiradaRepository;
import com.poprc.demo.repository.OrdemServicoRepository;
import com.poprc.demo.repository.ProjetoRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ImportacaoEstoquePlanilhaService {
    @org.springframework.beans.factory.annotation.Autowired
    private ReservaRetiradaV2Guard reservaV2;


    private static final BigDecimal METROS_POR_BOBINA_CABO = new BigDecimal("305");

    private final ImportacaoEstoquePlanilhaRepository importacaoRepository;
    private final ImportacaoEstoqueItemPlanilhaRepository itemImportacaoRepository;
    private final ImportacaoEntradaPlanilhaRepository entradaImportacaoRepository;
    private final ImportacaoRetiradaPlanilhaRepository retiradaImportacaoRepository;
    private final ImportacaoRetornoPlanilhaRepository retornoImportacaoRepository;
    private final MaterialRepository materialRepository;
    private final LocalEstoqueRepository localEstoqueRepository;
    private final ComarcaRepository comarcaRepository;
    private final ContratoRepository contratoRepository;
    private final FuncionarioRepository funcionarioRepository;
    private final ProjetoRepository projetoRepository;
    private final OrdemServicoRepository ordemServicoRepository;
    private final OrdemRetiradaRepository ordemRetiradaRepository;
    private final OrdemRetiradaItemRepository ordemRetiradaItemRepository;
    private final MaterialItemRepository materialItemRepository;
    private final EstoqueService estoqueService;

    @Transactional
    public ImportacaoEstoquePlanilhaResultadoDTO importar(
            ImportacaoEstoquePlanilhaRequest request, String usuario) {
        if (reservaV2 != null) reservaV2.lock();
        validarRequest(request);
        validarConteudoCompleto(request);
        String hash = request.hashSha256().trim().toLowerCase(Locale.ROOT);
        String responsavel = usuario != null && !usuario.isBlank() ? usuario : "Usuário autenticado";
        ImportacaoEstoquePlanilha importacaoExistente =
                importacaoRepository.findByHashSha256(hash).orElse(null);
        boolean complementacao = importacaoExistente != null;
        LocalEstoque local;
        ImportacaoEstoquePlanilha importacao;
        if (importacaoExistente != null) {
            if (request.retiradas() == null || request.retiradas().isEmpty()) {
                throw new IllegalArgumentException(
                        "Esta planilha já foi importada. Nenhum saldo foi alterado.");
            }
            if (retiradaImportacaoRepository.existsByImportacaoId(importacaoExistente.getId())) {
                throw new IllegalArgumentException(
                        "O estoque e as retiradas desta planilha já foram importados.");
            }
            local = importacaoExistente.getLocalEstoque();
            if (local == null) {
                throw new IllegalStateException(
                        "A importação existente não possui um depósito de destino válido.");
            }
            if (!local.getId().equals(request.localEstoqueId())) {
                throw new IllegalArgumentException(
                        "A complementação deve usar o mesmo depósito da importação original: "
                                + local.getNome() + ".");
            }
            validarInventarioBaseDaComplementacao(importacaoExistente, request.itens());
            importacao = importacaoExistente;
        } else {
            local = localEstoqueRepository.findById(request.localEstoqueId())
                    .filter(item -> !Boolean.FALSE.equals(item.getAtivo()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Depósito de destino não encontrado ou inativo."));
            importacao = novaImportacao(request, hash, responsavel, local);
        }

        Contrato contrato = null;
        Funcionario funcionarioResponsavel = null;
        if (Boolean.TRUE.equals(request.autoCriarOperacoes())) {
            contrato = contratoRepository.findById(request.contratoId())
                    .filter(item -> !Boolean.TRUE.equals(item.getArquivado()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Contrato para as operações importadas não encontrado ou arquivado."));
            funcionarioResponsavel = funcionarioRepository.findById(request.responsavelId())
                    .filter(item -> Boolean.TRUE.equals(item.getAtivo()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Responsável pelas operações importadas não encontrado ou inativo."));
            importacao.setContrato(contrato);
            importacao.setResponsavel(funcionarioResponsavel);
        }
        importacao.setSaldoConsolidado(Boolean.TRUE.equals(request.saldoConsolidado()));

        Map<String, List<Material>> materiaisPorNome = materialRepository.findAll().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        material -> normalizar(material.getNome())));
        Set<String> nomesRecebidos = new HashSet<>();

        int criados = 0;
        int atualizados = 0;
        int positivos = 0;
        int negativos = 0;
        BigDecimal valorTotal = BigDecimal.ZERO;
        Map<String, Integer> sequenciaisPartNumber = new HashMap<>();
        boolean registrarItensHistorico =
                !itemImportacaoRepository.existsByImportacaoId(importacao.getId());

        for (ImportacaoEstoquePlanilhaRequest.ItemImportacao item : request.itens()) {
            validarItem(item);
            String nomeNormalizado = normalizar(item.nome());
            if (!nomesRecebidos.add(nomeNormalizado)) {
                throw new IllegalArgumentException(
                        "A planilha contém o material duplicado: " + item.nome().trim() + ".");
            }

            List<Material> correspondencias = materiaisPorNome.getOrDefault(nomeNormalizado, List.of());
            if (correspondencias.size() > 1) {
                throw new IllegalArgumentException(
                        "Existem materiais duplicados no sistema com o nome " + item.nome().trim()
                                + ". Corrija o cadastro antes de importar.");
            }

            Material material;
            String acao;
            if (correspondencias.isEmpty()) {
                if (complementacao) {
                    throw new IllegalArgumentException(
                            "O material " + item.nome().trim()
                                    + " não foi encontrado para complementar a importação.");
                }
                material = novoMaterial(item, local, sequenciaisPartNumber);
                material.setEstoqueMinimo(estoqueMinimoImportado(
                        material, request.estoqueMinimoPadrao()));
                material = estoqueService.cadastrarMaterial(material);
                materiaisPorNome.put(nomeNormalizado, List.of(material));
                criados++;
                acao = "CRIADO";
            } else {
                material = correspondencias.getFirst();
                normalizarCaboEmBobina(material);
                if (TipoControleEstoque.BOBINA.equals(material.getTipoControle())
                        || TipoControleEstoque.ROLO.equals(material.getTipoControle())) {
                    throw new IllegalArgumentException(
                            "O material " + material.getNome()
                                    + " usa controle por metragem/bobina e não pode receber quantidade unitária da planilha.");
                }
                if (!complementacao) {
                    atualizados++;
                }
                acao = complementacao ? "REGISTRO_COMPLEMENTAR" : "ATUALIZADO";
                if (!complementacao && request.estoqueMinimoPadrao() != null) {
                    material.setEstoqueMinimo(estoqueMinimoImportado(
                            material, request.estoqueMinimoPadrao()));
                    materialRepository.save(material);
                }
            }

            BigDecimal saldoAnterior = saldoMaterial(material);
            BigDecimal custoAnterior = valorNaoNegativo(material.getCustoMedio());
            BigDecimal saldoDesejado = item.saldoEfetivo();
            if (!complementacao) {
                reconciliarSaldoImportado(
                        material,
                        local,
                        saldoDesejado,
                        item.custoUnitario(),
                        "Inventário importado de " + importacao.getNomeArquivo(),
                        responsavel);
                if (saldoDesejado.compareTo(saldoAnterior) > 0) {
                    positivos++;
                } else if (saldoDesejado.compareTo(saldoAnterior) < 0) {
                    negativos++;
                } else {
                    acao = "SEM_ALTERACAO";
                }
            }
            if (registrarItensHistorico) {
                registrarItemImportado(
                        importacao, material, item, saldoAnterior, saldoDesejado, custoAnterior, acao);
            }
            valorTotal = valorTotal.add(
                    item.custoUnitario().multiply(saldoDesejado));
        }

        if (!complementacao) {
            importacao.setItensProcessados(request.itens().size());
            importacao.setMateriaisCriados(criados);
            importacao.setMateriaisAtualizados(atualizados);
            importacao.setAjustesPositivos(positivos);
            importacao.setAjustesNegativos(negativos);
            importacao.setValorTotalImportado(valorTotal.setScale(2, RoundingMode.HALF_UP));
        } else {
            importacao.setDataComplementacao(LocalDateTime.now());
            importacao.setComplementadoPor(limitar(responsavel, 255));
        }

        int entradasImportadas = registrarEntradasHistoricas(
                importacao, request.entradas(), materiaisPorNome);
        importacao.setEntradasImportadas(entradasImportadas);

        ResultadoOperacoes resultadoOperacoes = registrarOperacoesHistoricas(
                        importacao,
                        request.retiradas(),
                        request.retornos(),
                        materiaisPorNome,
                        local,
                        contrato,
                        funcionarioResponsavel,
                        responsavel,
                        Boolean.TRUE.equals(request.autoCriarOperacoes()),
                        Boolean.TRUE.equals(request.saldoConsolidado()));
        importacao.setAbasRetiradaProcessadas(resultadoOperacoes.abas());
        importacao.setRetiradasImportadas(resultadoOperacoes.retiradas());
        importacao.setFaltasIdentificadas(resultadoOperacoes.faltas());
        importacao.setProjetosCriados(resultadoOperacoes.projetos());
        importacao.setOrdensServicoCriadas(resultadoOperacoes.ordensServico());
        importacao.setOrdensRetiradaCriadas(resultadoOperacoes.ordensRetirada());
        importacao.setRetornosImportados(resultadoOperacoes.retornos());
        importacao.setSimulacaoFaltas(contarFaltasSimulacao(request.simulacao()));
        importacaoRepository.save(importacao);

        return new ImportacaoEstoquePlanilhaResultadoDTO(
                importacao.getId(),
                importacao.getNomeArquivo(),
                importacao.getDataImportacao(),
                importacao.getImportadoPor(),
                local.getNome(),
                importacao.getItensProcessados(),
                importacao.getMateriaisCriados(),
                importacao.getMateriaisAtualizados(),
                importacao.getAjustesPositivos(),
                importacao.getAjustesNegativos(),
                importacao.getValorTotalImportado(),
                importacao.getEntradasImportadas(),
                importacao.getAbasRetiradaProcessadas(),
                importacao.getRetiradasImportadas(),
                importacao.getFaltasIdentificadas(),
                importacao.getProjetosCriados(),
                importacao.getOrdensServicoCriadas(),
                importacao.getOrdensRetiradaCriadas(),
                importacao.getRetornosImportados(),
                importacao.getSimulacaoFaltas(),
                complementacao ? "COMPLEMENTADA" : "IMPORTADA");
    }

    @Transactional
    public ImportacaoEstoquePlanilhaResultadoDTO atualizarCustos(
            AtualizacaoCustosPlanilhaRequest request, String usuario) {
        if (reservaV2 != null) reservaV2.lock();
        validarAtualizacaoCustos(request);
        String hash = request.hashSha256().trim().toLowerCase(Locale.ROOT);
        if (importacaoRepository.existsByHashSha256(hash)) {
            throw new IllegalArgumentException(
                    "Esta planilha já foi importada. Nenhum custo foi alterado.");
        }

        String responsavel = usuario != null && !usuario.isBlank()
                ? usuario
                : "Usuário autenticado";
        ImportacaoEstoquePlanilha importacao = new ImportacaoEstoquePlanilha();
        importacao.setNomeArquivo(limitar(request.nomeArquivo().trim(), 255));
        importacao.setHashSha256(hash);
        importacao.setDataImportacao(LocalDateTime.now());
        importacao.setImportadoPor(limitar(responsavel, 255));
        importacao.setTipoImportacao("ATUALIZACAO_CUSTOS");
        importacao.setSaldoConsolidado(false);
        importacao = importacaoRepository.saveAndFlush(importacao);

        Set<Long> materiaisRecebidos = new HashSet<>();
        int atualizados = 0;
        for (AtualizacaoCustosPlanilhaRequest.ItemCusto item : request.itens()) {
            if (item == null || item.materialId() == null) {
                throw new IllegalArgumentException("Todo custo precisa estar vinculado a um material.");
            }
            if (!materiaisRecebidos.add(item.materialId())) {
                throw new IllegalArgumentException(
                        "O mesmo material aparece mais de uma vez na atualização de custos.");
            }
            if (item.nomePlanilha() == null || item.nomePlanilha().isBlank()) {
                throw new IllegalArgumentException("O nome do material na planilha é obrigatório.");
            }
            if (item.custoUnitario() == null || item.custoUnitario().signum() < 0) {
                throw new IllegalArgumentException(
                        "O custo de " + item.nomePlanilha().trim() + " é inválido.");
            }

            Material material = materialRepository.findByIdForUpdate(item.materialId())
                    .filter(registro -> !Boolean.FALSE.equals(registro.getAtivo()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Material não encontrado ou removido: " + item.nomePlanilha().trim() + "."));
            BigDecimal custoAnterior = valorNaoNegativo(material.getCustoMedio())
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal custoNovo = item.custoUnitario().setScale(4, RoundingMode.HALF_UP);
            BigDecimal saldoPreservado = saldoMaterial(material);
            String acao = custoAnterior.compareTo(custoNovo) == 0
                    ? "CUSTO_MANTIDO"
                    : "CUSTO_ATUALIZADO";

            material.setCustoMedio(custoNovo);
            materialRepository.save(material);

            ImportacaoEstoqueItemPlanilha registro = new ImportacaoEstoqueItemPlanilha();
            registro.setImportacao(importacao);
            registro.setMaterial(material);
            registro.setNomePlanilha(limitar(item.nomePlanilha().trim(), 255));
            registro.setSaldoAnterior(saldoPreservado);
            registro.setSaldoImportado(saldoPreservado);
            registro.setCustoAnterior(custoAnterior);
            registro.setCustoUnitario(custoNovo);
            registro.setLinhaOrigem(item.linhaOrigem());
            registro.setAcao(acao);
            itemImportacaoRepository.save(registro);
            if ("CUSTO_ATUALIZADO".equals(acao)) {
                atualizados++;
            }
        }

        BigDecimal valorTotalEstoque = materialRepository.findByAtivoTrueOrderByNomeAsc().stream()
                .map(Material::getValorTotalEstoque)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        importacao.setItensProcessados(request.itens().size());
        importacao.setMateriaisAtualizados(atualizados);
        importacao.setValorTotalImportado(valorTotalEstoque);
        importacaoRepository.save(importacao);

        return new ImportacaoEstoquePlanilhaResultadoDTO(
                importacao.getId(),
                importacao.getNomeArquivo(),
                importacao.getDataImportacao(),
                importacao.getImportadoPor(),
                "Custos, sem alteração de saldo",
                importacao.getItensProcessados(),
                0,
                importacao.getMateriaisAtualizados(),
                0,
                0,
                importacao.getValorTotalImportado(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "CUSTOS_ATUALIZADOS");
    }

    @Transactional
    public ImportacaoEstoquePlanilhaResultadoDTO sincronizarSaldos(
            SincronizacaoSaldosPlanilhaRequest request, String usuario) {
        if (reservaV2 != null) reservaV2.lock();
        validarSincronizacaoSaldos(request);
        String hash = hashModo("SALDOS", request.hashSha256());
        if (importacaoRepository.existsByHashSha256(hash)) {
            throw new IllegalArgumentException(
                    "Os saldos desta planilha já foram sincronizados. Nenhuma quantidade foi alterada.");
        }

        LocalEstoque local = localEstoqueRepository.findById(request.localEstoqueId())
                .filter(item -> !Boolean.FALSE.equals(item.getAtivo()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Depósito de destino não encontrado ou inativo."));
        String responsavel = usuario != null && !usuario.isBlank()
                ? usuario
                : "Usuário autenticado";

        List<Material> materiaisAtivos = materialRepository.findByAtivoTrueOrderByNomeAsc();
        Set<Long> idsEsperados = materiaisAtivos.stream()
                .filter(material -> !TipoControleEstoque.BOBINA.equals(material.getTipoControle())
                        && !TipoControleEstoque.ROLO.equals(material.getTipoControle()))
                .map(Material::getId)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> idsRecebidos = request.itens().stream()
                .map(SincronizacaoSaldosPlanilhaRequest.ItemSaldo::materialId)
                .collect(java.util.stream.Collectors.toSet());
        if (!idsRecebidos.equals(idsEsperados)) {
            throw new IllegalArgumentException(
                    "A aba ESTOQUE ATUAL deve conter todos os materiais ativos controlados por saldo.");
        }

        ImportacaoEstoquePlanilha importacao = new ImportacaoEstoquePlanilha();
        importacao.setNomeArquivo(limitar(request.nomeArquivo().trim(), 255));
        importacao.setHashSha256(hash);
        importacao.setDataImportacao(LocalDateTime.now());
        importacao.setImportadoPor(limitar(responsavel, 255));
        importacao.setLocalEstoque(local);
        importacao.setTipoImportacao("SINCRONIZACAO_SALDOS");
        importacao.setSaldoConsolidado(true);
        importacao = importacaoRepository.saveAndFlush(importacao);

        int atualizados = 0;
        int positivos = 0;
        int negativos = 0;
        Set<Long> materiaisRecebidos = new HashSet<>();
        for (SincronizacaoSaldosPlanilhaRequest.ItemSaldo item : request.itens()) {
            validarItemSincronizacao(item, materiaisRecebidos);
            Material material = materialRepository.findByIdForUpdate(item.materialId())
                    .filter(registro -> !Boolean.FALSE.equals(registro.getAtivo()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Material não encontrado ou removido: " + item.nomePlanilha().trim() + "."));
            if (!normalizar(material.getNome()).equals(normalizar(item.nomePlanilha()))) {
                throw new IllegalArgumentException(
                        "O material da linha " + item.linhaOrigem()
                                + " não corresponde mais ao cadastro do estoque.");
            }
            if (TipoControleEstoque.BOBINA.equals(material.getTipoControle())
                    || TipoControleEstoque.ROLO.equals(material.getTipoControle())) {
                throw new IllegalArgumentException(
                        "Bobinas e rolos rastreáveis devem ser reconciliados por unidade física.");
            }
            BigDecimal saldoDesejado = item.saldo().setScale(3, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            if (!controlaMetragem(material) && !numeroInteiro(saldoDesejado)) {
                throw new IllegalArgumentException(
                        "O saldo de " + material.getNome() + " precisa ser inteiro.");
            }
            BigDecimal reservado = saldoReservado(material);
            if (saldoDesejado.compareTo(reservado) < 0) {
                throw new IllegalArgumentException(
                        "O saldo de " + material.getNome() + " não pode ficar abaixo das "
                                + reservado.stripTrailingZeros().toPlainString() + " unidades reservadas.");
            }

            BigDecimal saldoAnterior = saldoMaterial(material);
            BigDecimal custoPreservado = valorNaoNegativo(material.getCustoMedio())
                    .setScale(4, RoundingMode.HALF_UP);
            int comparacao = saldoDesejado.compareTo(saldoAnterior);
            String acao = comparacao > 0
                    ? "SALDO_AUMENTADO"
                    : comparacao < 0 ? "SALDO_REDUZIDO" : "SALDO_MANTIDO";
            if (comparacao != 0) {
                reconciliarSaldoImportado(
                        material,
                        local,
                        saldoDesejado,
                        custoPreservado,
                        "Sincronização da aba ESTOQUE ATUAL de " + importacao.getNomeArquivo(),
                        responsavel);
                atualizados++;
                if (comparacao > 0) {
                    positivos++;
                } else {
                    negativos++;
                }
            }

            ImportacaoEstoqueItemPlanilha registro = new ImportacaoEstoqueItemPlanilha();
            registro.setImportacao(importacao);
            registro.setMaterial(material);
            registro.setNomePlanilha(limitar(item.nomePlanilha().trim(), 255));
            registro.setSaldoAnterior(saldoAnterior);
            registro.setSaldoImportado(saldoDesejado);
            registro.setCustoAnterior(custoPreservado);
            registro.setCustoUnitario(custoPreservado);
            registro.setLinhaOrigem(item.linhaOrigem());
            registro.setAcao(acao);
            itemImportacaoRepository.save(registro);
        }

        BigDecimal valorTotalEstoque = materialRepository.findByAtivoTrueOrderByNomeAsc().stream()
                .map(Material::getValorTotalEstoque)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        importacao.setItensProcessados(request.itens().size());
        importacao.setMateriaisAtualizados(atualizados);
        importacao.setAjustesPositivos(positivos);
        importacao.setAjustesNegativos(negativos);
        importacao.setValorTotalImportado(valorTotalEstoque);
        importacaoRepository.save(importacao);

        return new ImportacaoEstoquePlanilhaResultadoDTO(
                importacao.getId(),
                importacao.getNomeArquivo(),
                importacao.getDataImportacao(),
                importacao.getImportadoPor(),
                local.getNome(),
                importacao.getItensProcessados(),
                0,
                importacao.getMateriaisAtualizados(),
                importacao.getAjustesPositivos(),
                importacao.getAjustesNegativos(),
                importacao.getValorTotalImportado(),
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                "SALDOS_SINCRONIZADOS");
    }

    @Transactional(readOnly = true)
    public List<ImportacaoEstoquePlanilhaDetalheDTO> listarHistorico() {
        return importacaoRepository.findAllByOrderByDataImportacaoDesc().stream()
                .map(importacao -> mapearDetalhe(importacao, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public ImportacaoEstoquePlanilhaDetalheDTO detalhar(Long id) {
        ImportacaoEstoquePlanilha importacao = importacaoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Importação de estoque não encontrada."));
        return mapearDetalhe(importacao, true);
    }

    @Transactional(readOnly = true)
    public List<ImportacaoEstoquePlanilhaDetalheDTO.Retirada> listarRetiradasImportadas() {
        return retiradaImportacaoRepository.findAllByOrderByImportacaoDataImportacaoDescAbaOrigemAsc()
                .stream()
                .map(this::mapearRetirada)
                .toList();
    }

    private ImportacaoEstoquePlanilha novaImportacao(
            ImportacaoEstoquePlanilhaRequest request,
            String hash,
            String responsavel,
            LocalEstoque local) {
        ImportacaoEstoquePlanilha importacao = new ImportacaoEstoquePlanilha();
        importacao.setNomeArquivo(limitar(request.nomeArquivo().trim(), 255));
        importacao.setHashSha256(hash);
        importacao.setDataImportacao(LocalDateTime.now());
        importacao.setImportadoPor(limitar(responsavel, 255));
        importacao.setLocalEstoque(local);
        importacao.setTipoImportacao("INVENTARIO_COMPLETO");
        return importacaoRepository.saveAndFlush(importacao);
    }

    private void registrarItemImportado(
            ImportacaoEstoquePlanilha importacao,
            Material material,
            ImportacaoEstoquePlanilhaRequest.ItemImportacao item,
            BigDecimal saldoAnterior,
            BigDecimal saldoDesejado,
            BigDecimal custoAnterior,
            String acao) {
        ImportacaoEstoqueItemPlanilha registro = new ImportacaoEstoqueItemPlanilha();
        registro.setImportacao(importacao);
        registro.setMaterial(material);
        registro.setNomePlanilha(limitar(item.nome().trim(), 255));
        registro.setSaldoAnterior(saldoAnterior);
        registro.setSaldoImportado(saldoDesejado);
        registro.setCustoAnterior(custoAnterior);
        registro.setCustoUnitario(item.custoUnitario());
        registro.setLinhaOrigem(item.linhaOrigem());
        registro.setAcao(acao);
        itemImportacaoRepository.save(registro);
    }

    private int registrarEntradasHistoricas(
            ImportacaoEstoquePlanilha importacao,
            List<ImportacaoEstoquePlanilhaRequest.EntradaImportacao> entradas,
            Map<String, List<Material>> materiaisPorNome) {
        int processadas = 0;
        for (ImportacaoEstoquePlanilhaRequest.EntradaImportacao entrada
                : entradas != null ? entradas : List.<ImportacaoEstoquePlanilhaRequest.EntradaImportacao>of()) {
            validarEntrada(entrada);
            Material material = materialUnico(
                    materiaisPorNome, entrada.nomeMaterial(), entrada.cabecalhoOrigem());
            String identidade = normalizar(entrada.tipo()) + "|"
                    + normalizar(entrada.nomeMaterial()) + "|"
                    + normalizar(entrada.cabecalhoOrigem());
            String chaveEvento = UUID.nameUUIDFromBytes(
                    identidade.getBytes(StandardCharsets.UTF_8)).toString();
            if (entradaImportacaoRepository.existsByChaveEvento(chaveEvento)) {
                continue;
            }

            ImportacaoEntradaPlanilha registro = new ImportacaoEntradaPlanilha();
            registro.setImportacao(importacao);
            registro.setMaterial(material);
            registro.setChaveEvento(chaveEvento);
            registro.setTipoEntrada(entrada.tipo().trim().toUpperCase(Locale.ROOT));
            registro.setCabecalhoOrigem(limitar(entrada.cabecalhoOrigem().trim(), 255));
            registro.setFornecedor(limitar(entrada.fornecedor(), 255));
            registro.setDataEntrada(entrada.dataEntrada());
            registro.setQuantidade(entrada.quantidade());
            registro.setCustoUnitario(entrada.custoUnitario());
            registro.setLinhaOrigem(entrada.linhaOrigem());
            registro.setColunaOrigem(entrada.colunaOrigem());
            entradaImportacaoRepository.save(registro);
            processadas++;
        }
        return processadas;
    }

    private ResultadoOperacoes registrarOperacoesHistoricas(
            ImportacaoEstoquePlanilha importacao,
            List<ImportacaoEstoquePlanilhaRequest.RetiradaImportacao> retiradas,
            List<ImportacaoEstoquePlanilhaRequest.RetornoImportacao> retornos,
            Map<String, List<Material>> materiaisPorNome,
            LocalEstoque local,
            Contrato contrato,
            Funcionario responsavelProjeto,
            String responsavelAuditoria,
            boolean autoCriarOperacoes,
            boolean saldoConsolidado) {
        List<ImportacaoEstoquePlanilhaRequest.RetiradaImportacao> listaRetiradas =
                retiradas != null ? retiradas : List.of();
        List<ImportacaoEstoquePlanilhaRequest.RetornoImportacao> listaRetornos =
                retornos != null ? retornos : List.of();
        if (listaRetiradas.isEmpty() && listaRetornos.isEmpty()) {
            return ResultadoOperacoes.vazio();
        }

        Map<String, List<ImportacaoEstoquePlanilhaRequest.RetiradaImportacao>> porAba =
                listaRetiradas.stream().collect(java.util.stream.Collectors.groupingBy(
                        retirada -> retirada.aba().trim(), LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<String, Comarca> comarcaPorCidade = new HashMap<>();
        Map<String, OrdemServico> osPorCidade = new HashMap<>();
        Map<String, OrdemRetirada> orPorAba = new HashMap<>();
        int projetosCriados = 0;
        int osCriadas = 0;
        int orCriadas = 0;

        if (autoCriarOperacoes) {
            for (Map.Entry<String, List<ImportacaoEstoquePlanilhaRequest.RetiradaImportacao>> entry
                    : porAba.entrySet()) {
                String aba = entry.getKey();
                ImportacaoEstoquePlanilhaRequest.RetiradaImportacao primeira = entry.getValue().getFirst();
                String cidade = validarCidade(primeira.cidade(), aba);
                if (retiradaImportacaoRepository
                        .existsByAbaOrigemIgnoreCaseAndComarcaProjetoContratoId(aba, contrato.getId())) {
                    continue;
                }
                String chaveCidade = normalizar(cidade);
                Comarca comarca = comarcaPorCidade.get(chaveCidade);
                OrdemServico ordemServico = osPorCidade.get(chaveCidade);
                if (comarca == null) {
                    OperacaoHistorica operacao = criarOperacaoHistorica(
                            contrato,
                            responsavelProjeto,
                            cidade,
                            listaRetiradas.stream()
                                    .filter(item -> normalizar(item.cidade()).equals(chaveCidade))
                                    .toList(),
                            materiaisPorNome,
                            responsavelAuditoria);
                    comarca = operacao.comarca();
                    ordemServico = operacao.ordemServico();
                    comarcaPorCidade.put(chaveCidade, comarca);
                    osPorCidade.put(chaveCidade, ordemServico);
                    projetosCriados++;
                    osCriadas++;
                }
                OrdemRetirada ordemRetirada = criarOrHistorica(
                        ordemServico, comarca, aba, entry.getValue(), materiaisPorNome, responsavelAuditoria);
                orPorAba.put(normalizar(aba), ordemRetirada);
                orCriadas++;
            }
        }

        int processadas = 0;
        int faltas = 0;
        Set<String> abasProcessadas = new HashSet<>();
        for (ImportacaoEstoquePlanilhaRequest.RetiradaImportacao retirada : listaRetiradas) {
            String aba = retirada.aba().trim();
            OrdemRetirada ordemRetirada = orPorAba.get(normalizar(aba));
            Comarca comarca = ordemRetirada != null
                    ? ordemRetirada.getComarca()
                    : buscarComarcaInformada(retirada);
            if (ordemRetirada == null && autoCriarOperacoes
                    && retiradaImportacaoRepository
                            .existsByAbaOrigemIgnoreCaseAndComarcaProjetoContratoId(aba, contrato.getId())) {
                continue;
            }
            Material material = materialUnico(materiaisPorNome, retirada.nomeMaterial(), aba);
            validarRetirada(retirada, autoCriarOperacoes, saldoConsolidado, material);
            BigDecimal faltante = retirada.saldoFinal().signum() < 0
                    ? retirada.saldoFinal().abs()
                    : BigDecimal.ZERO;
            ImportacaoRetiradaPlanilha registro = new ImportacaoRetiradaPlanilha();
            registro.setImportacao(importacao);
            registro.setComarca(comarca);
            registro.setMaterial(material);
            registro.setOrdemRetirada(ordemRetirada);
            registro.setAbaOrigem(limitar(aba, 255));
            registro.setSaldoInicial(retirada.saldoInicial());
            registro.setQuantidadeRetirada(retirada.quantidadeRetirada());
            registro.setSaldoFinal(retirada.saldoFinal());
            registro.setQuantidadeFaltante(faltante);
            registro.setCustoUnitario(retirada.custoUnitario());
            registro.setDataRetirada(retirada.dataRetirada());
            retiradaImportacaoRepository.save(registro);
            abasProcessadas.add(normalizar(aba));
            processadas++;
            if (faltante.signum() > 0) {
                faltas++;
            }
        }

        int retornosImportados = registrarRetornos(
                importacao,
                listaRetornos,
                materiaisPorNome,
                comarcaPorCidade,
                contrato);
        if (!saldoConsolidado) {
            reconciliarSaldosLegados(
                    importacao, listaRetiradas, materiaisPorNome, local, responsavelAuditoria);
        }
        return new ResultadoOperacoes(
                abasProcessadas.size(),
                processadas,
                faltas,
                projetosCriados,
                osCriadas,
                orCriadas,
                retornosImportados);
    }

    private OperacaoHistorica criarOperacaoHistorica(
            Contrato contrato,
            Funcionario responsavel,
            String cidade,
            List<ImportacaoEstoquePlanilhaRequest.RetiradaImportacao> retiradasCidade,
            Map<String, List<Material>> materiaisPorNome,
            String usuario) {
        LocalDate primeiraData = retiradasCidade.stream()
                .map(ImportacaoEstoquePlanilhaRequest.RetiradaImportacao::dataRetirada)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(LocalDate.now());
        LocalDate ultimaData = retiradasCidade.stream()
                .map(ImportacaoEstoquePlanilhaRequest.RetiradaImportacao::dataRetirada)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(primeiraData);

        Projeto projeto = new Projeto();
        projeto.setContrato(contrato);
        projeto.setResponsavel(responsavel);
        projeto.setDataInicio(primeiraData);
        projeto.setDataFim(ultimaData);
        projeto.setStatus(ProjetoStatus.CONCLUIDO);
        projeto.setAsBuiltStatus("HISTORICO_IMPORTADO");
        projeto.setArquivado(false);
        projeto = projetoRepository.save(projeto);

        Comarca comarca = new Comarca();
        comarca.setNomeComarca(cidade);
        comarca.setProjeto(projeto);
        comarca.setSituacao("HISTORICO_IMPORTADO");
        comarca.setPercentualConcluido(new BigDecimal("100.00"));
        comarca.setEtapaAtual(3);
        comarca.setViradaRedeConcluida(true);
        comarca.setDataConclusao(ultimaData.atTime(23, 59));
        comarca.setConcluidaPor(usuario);
        comarca.setAsBuiltStatus("HISTORICO_IMPORTADO");
        comarca.setArquivado(false);
        comarca = comarcaRepository.save(comarca);

        OrdemServico ordemServico = new OrdemServico();
        ordemServico.setNumeroOs(gerarNumeroOs(contrato));
        ordemServico.setTitulo(limitar("Retirada histórica - " + cidade, 255));
        ordemServico.setDescricao(
                "Operação histórica importada da planilha. A fonte informa a data, mas não o horário exato.");
        ordemServico.setContrato(contrato);
        ordemServico.setProjeto(projeto);
        ordemServico.setDataExecucao(primeiraData);
        ordemServico.setDataHoraInicio(primeiraData.atStartOfDay());
        ordemServico.setDataHoraFim(ultimaData.atTime(23, 59));
        ordemServico.setDeadline(ultimaData.atTime(23, 59));
        ordemServico.setStatus(StatusOS.CONCLUIDA);
        ordemServico.setArquivado(false);
        ordemServico = ordemServicoRepository.save(ordemServico);
        comarca.setOrdemServico(ordemServico);
        comarcaRepository.save(comarca);

        Map<String, BigDecimal> totaisPorMaterial = new LinkedHashMap<>();
        Map<String, BigDecimal> totaisEfetivosPorMaterial = new LinkedHashMap<>();
        retiradasCidade.forEach(retirada -> totaisPorMaterial.merge(
                normalizar(retirada.nomeMaterial()), retirada.quantidadeRetirada(), BigDecimal::add));
        retiradasCidade.forEach(retirada -> totaisEfetivosPorMaterial.merge(
                normalizar(retirada.nomeMaterial()), quantidadeEfetivamenteRetirada(retirada), BigDecimal::add));
        for (Map.Entry<String, BigDecimal> total : totaisPorMaterial.entrySet()) {
            Material material = materiaisPorNome.get(total.getKey()).getFirst();
            MaterialItem item = new MaterialItem();
            item.setComarca(comarca);
            item.setMaterial(material);
            item.setNomeMaterial(material.getNome());
            item.setQuantidadePrevista(total.getValue());
            item.setQuantidadeAuditada(totaisEfetivosPorMaterial.get(total.getKey()));
            item.setEstoqueReservado(false);
            item.setEstoqueBaixado(true);
            item.setDataHoraRetirada(primeiraData.atStartOfDay());
            materialItemRepository.save(item);
        }
        comarca.setMateriais(materialItemRepository.findByComarcaIdOrderByIdAsc(comarca.getId()));
        return new OperacaoHistorica(comarca, ordemServico);
    }

    private OrdemRetirada criarOrHistorica(
            OrdemServico ordemServico,
            Comarca comarca,
            String aba,
            List<ImportacaoEstoquePlanilhaRequest.RetiradaImportacao> retiradas,
            Map<String, List<Material>> materiaisPorNome,
            String usuario) {
        long sequencial = ordemRetiradaRepository.countByOrdemServicoId(ordemServico.getId()) + 1;
        String numeroOr;
        do {
            numeroOr = ordemServico.getNumeroOs() + " - OR " + String.format("%02d", sequencial++);
        } while (ordemRetiradaRepository.existsByNumeroOr(numeroOr));
        LocalDate data = retiradas.stream()
                .map(ImportacaoEstoquePlanilhaRequest.RetiradaImportacao::dataRetirada)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(ordemServico.getDataExecucao());

        OrdemRetirada ordemRetirada = new OrdemRetirada();
        ordemRetirada.setNumeroOr(numeroOr);
        ordemRetirada.setStatus("HISTORICA_IMPORTADA");
        ordemRetirada.setOrdemServico(ordemServico);
        ordemRetirada.setComarca(comarca);
        ordemRetirada.setGeradoPor(usuario);
        ordemRetirada.setDataGeracao(data.atStartOfDay());
        ordemRetirada.setDataRetirada(data.atStartOfDay());
        ordemRetirada = ordemRetiradaRepository.save(ordemRetirada);

        Map<String, MaterialItem> itensComarca = materialItemRepository
                .findByComarcaIdOrderByIdAsc(comarca.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        item -> normalizar(item.getNomeMaterial()), item -> item));
        for (ImportacaoEstoquePlanilhaRequest.RetiradaImportacao retirada : retiradas) {
            Material material = materialUnico(materiaisPorNome, retirada.nomeMaterial(), aba);
            OrdemRetiradaItem item = new OrdemRetiradaItem();
            item.setOrdemRetirada(ordemRetirada);
            item.setMaterial(material);
            item.setMaterialItem(itensComarca.get(normalizar(retirada.nomeMaterial())));
            item.setNomeMaterial(material.getNome());
            item.setCategoria(material.getCategoria());
            item.setQuantidadeSolicitada(retirada.quantidadeRetirada());
            item.setQuantidadeRetirada(quantidadeEfetivamenteRetirada(retirada));
            item.setQuantidadeDevolvida(BigDecimal.ZERO);
            ordemRetiradaItemRepository.save(item);
        }
        return ordemRetirada;
    }

    private BigDecimal quantidadeEfetivamenteRetirada(
            ImportacaoEstoquePlanilhaRequest.RetiradaImportacao retirada) {
        BigDecimal saldoDisponivel = retirada.saldoInicial().max(BigDecimal.ZERO);
        return retirada.quantidadeRetirada().min(saldoDisponivel);
    }

    private void reconciliarSaldosLegados(
            ImportacaoEstoquePlanilha importacao,
            List<ImportacaoEstoquePlanilhaRequest.RetiradaImportacao> retiradas,
            Map<String, List<Material>> materiaisPorNome,
            LocalEstoque local,
            String responsavel) {
        Map<String, ImportacaoEstoquePlanilhaRequest.RetiradaImportacao> ultimaPorMaterial =
                new LinkedHashMap<>();
        retiradas.forEach(retirada -> ultimaPorMaterial.put(
                normalizar(retirada.nomeMaterial()), retirada));
        for (Map.Entry<String, ImportacaoEstoquePlanilhaRequest.RetiradaImportacao> entry
                : ultimaPorMaterial.entrySet()) {
            Material material = materiaisPorNome.get(entry.getKey()).getFirst();
            ImportacaoEstoquePlanilhaRequest.RetiradaImportacao retirada = entry.getValue();
            BigDecimal saldoDesejado = retirada.saldoFinal().max(BigDecimal.ZERO);
            if (saldoMaterial(material).compareTo(saldoDesejado) == 0) {
                continue;
            }
            reconciliarSaldoImportado(
                    material,
                    local,
                    saldoDesejado,
                    retirada.custoUnitario(),
                    "Saldo final após retiradas importadas de " + importacao.getNomeArquivo(),
                    responsavel);
        }
    }

    private void reconciliarSaldoImportado(
            Material material,
            LocalEstoque local,
            BigDecimal saldo,
            BigDecimal custo,
            String motivo,
            String responsavel) {
        boolean saldoInteiro = numeroInteiro(saldo);
        boolean controleUnitario = TipoControleEstoque.UNIDADE.equals(material.getTipoControle())
                || TipoControleEstoque.PECA_COM_COMPRIMENTO.equals(material.getTipoControle());
        if (saldoInteiro && controleUnitario) {
            estoqueService.reconciliarSaldoPlanilha(
                    material.getId(), local.getId(), saldo.intValueExact(), custo, motivo, responsavel);
            return;
        }
        estoqueService.reconciliarSaldoPlanilha(
                material.getId(), local.getId(), saldo, custo, motivo, responsavel);
    }

    private int registrarRetornos(
            ImportacaoEstoquePlanilha importacao,
            List<ImportacaoEstoquePlanilhaRequest.RetornoImportacao> retornos,
            Map<String, List<Material>> materiaisPorNome,
            Map<String, Comarca> comarcaPorCidade,
            Contrato contrato) {
        int processados = 0;
        for (ImportacaoEstoquePlanilhaRequest.RetornoImportacao retorno : retornos) {
            if (retorno.quantidadeRetornada() == null || retorno.quantidadeRetornada().signum() <= 0) {
                continue;
            }
            Comarca comarca = comarcaPorCidade.get(normalizar(retorno.cidade()));
            if (comarca == null && contrato != null) {
                comarca = comarcaRepository
                        .findByNomeComarcaIgnoreCaseAndProjetoContratoId(retorno.cidade(), contrato.getId())
                        .orElse(null);
            }
            if (comarca == null) {
                throw new IllegalArgumentException(
                        "Não foi encontrada uma obra para o retorno de " + retorno.cidade() + ".");
            }
            Material material = materialUnico(
                    materiaisPorNome, retorno.nomeMaterial(), retorno.aba());
            if (contrato != null && retornoImportacaoRepository
                    .existsByAbaOrigemIgnoreCaseAndComarcaProjetoContratoIdAndMaterialIdAndQuantidadeRetornada(
                            retorno.aba(),
                            contrato.getId(),
                            material.getId(),
                            retorno.quantidadeRetornada())) {
                continue;
            }
            ImportacaoRetornoPlanilha registro = new ImportacaoRetornoPlanilha();
            registro.setImportacao(importacao);
            registro.setComarca(comarca);
            registro.setMaterial(material);
            registro.setAbaOrigem(limitar(retorno.aba(), 255));
            registro.setQuantidadeRetornada(retorno.quantidadeRetornada());
            retornoImportacaoRepository.save(registro);
            processados++;
        }
        return processados;
    }

    private Comarca buscarComarcaInformada(
            ImportacaoEstoquePlanilhaRequest.RetiradaImportacao retirada) {
        if (retirada.comarcaId() == null) {
            throw new IllegalArgumentException(
                    "Vincule a aba " + retirada.aba() + " a uma obra antes de importar.");
        }
        return comarcaRepository.findById(retirada.comarcaId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Obra vinculada à aba " + retirada.aba() + " não foi encontrada."));
    }

    private Material materialUnico(
            Map<String, List<Material>> materiaisPorNome, String nome, String aba) {
        List<Material> correspondencias = materiaisPorNome.getOrDefault(normalizar(nome), List.of());
        if (correspondencias.size() != 1) {
            throw new IllegalArgumentException(
                    "Não foi possível identificar unicamente o material " + nome + " da aba " + aba + ".");
        }
        return correspondencias.getFirst();
    }

    private String gerarNumeroOs(Contrato contrato) {
        String numeroContrato = contrato.getContrato() != null && !contrato.getContrato().isBlank()
                ? contrato.getContrato().trim()
                : "Contrato " + contrato.getId();
        long sequencial = ordemServicoRepository.countByContratoId(contrato.getId()) + 1;
        String numeroOs;
        do {
            numeroOs = numeroContrato + " - OS " + String.format("%02d", sequencial++);
        } while (ordemServicoRepository.existsByNumeroOs(numeroOs));
        return numeroOs;
    }

    private int contarFaltasSimulacao(
            List<ImportacaoEstoquePlanilhaRequest.SimulacaoImportacao> simulacao) {
        return (int) (simulacao != null ? simulacao : List.<ImportacaoEstoquePlanilhaRequest.SimulacaoImportacao>of())
                .stream()
                .filter(item -> item.quantidadeFaltante() != null
                        && item.quantidadeFaltante().signum() > 0)
                .count();
    }

    private record OperacaoHistorica(Comarca comarca, OrdemServico ordemServico) {
    }

    private record ResultadoOperacoes(
            int abas,
            int retiradas,
            int faltas,
            int projetos,
            int ordensServico,
            int ordensRetirada,
            int retornos) {
        private static ResultadoOperacoes vazio() {
            return new ResultadoOperacoes(0, 0, 0, 0, 0, 0, 0);
        }
    }

    private ImportacaoEstoquePlanilhaDetalheDTO mapearDetalhe(
            ImportacaoEstoquePlanilha importacao, boolean incluirItens) {
        List<ImportacaoEstoquePlanilhaDetalheDTO.ItemEstoque> itens = incluirItens
                ? itemImportacaoRepository.findByImportacaoIdOrderByNomePlanilhaAsc(importacao.getId())
                        .stream()
                        .map(item -> new ImportacaoEstoquePlanilhaDetalheDTO.ItemEstoque(
                                item.getMaterial().getId(),
                                item.getMaterial().getNome(),
                                item.getSaldoAnterior(),
                                item.getSaldoImportado(),
                                item.getCustoAnterior(),
                                item.getCustoUnitario(),
                                item.getLinhaOrigem(),
                                item.getAcao()))
                        .toList()
                : List.of();
        List<ImportacaoEstoquePlanilhaDetalheDTO.Retirada> retiradas = incluirItens
                ? retiradaImportacaoRepository
                        .findByImportacaoIdOrderByAbaOrigemAscMaterialNomeAsc(importacao.getId())
                        .stream()
                        .map(this::mapearRetirada)
                        .toList()
                : List.of();
        return new ImportacaoEstoquePlanilhaDetalheDTO(
                importacao.getId(),
                importacao.getNomeArquivo(),
                importacao.getDataImportacao(),
                importacao.getDataComplementacao(),
                importacao.getImportadoPor(),
                importacao.getComplementadoPor(),
                importacao.getLocalEstoque() != null
                        ? importacao.getLocalEstoque().getNome()
                        : "Custos, sem alteração de saldo",
                importacao.getTipoImportacao(),
                importacao.getItensProcessados(),
                importacao.getMateriaisCriados(),
                importacao.getMateriaisAtualizados(),
                importacao.getAjustesPositivos(),
                importacao.getAjustesNegativos(),
                importacao.getValorTotalImportado(),
                importacao.getEntradasImportadas(),
                importacao.getAbasRetiradaProcessadas(),
                importacao.getRetiradasImportadas(),
                importacao.getFaltasIdentificadas(),
                itens,
                retiradas);
    }

    private ImportacaoEstoquePlanilhaDetalheDTO.Retirada mapearRetirada(
            ImportacaoRetiradaPlanilha retirada) {
        return new ImportacaoEstoquePlanilhaDetalheDTO.Retirada(
                retirada.getId(),
                retirada.getAbaOrigem(),
                retirada.getOrdemRetirada() != null ? retirada.getOrdemRetirada().getId() : null,
                retirada.getOrdemRetirada() != null ? retirada.getOrdemRetirada().getNumeroOr() : null,
                retirada.getComarca().getId(),
                retirada.getComarca().getNomeComarca(),
                retirada.getComarca().getOrdemServico() != null
                        ? retirada.getComarca().getOrdemServico().getNumeroOs()
                        : null,
                retirada.getMaterial().getId(),
                retirada.getMaterial().getNome(),
                retirada.getSaldoInicial(),
                retirada.getQuantidadeRetirada(),
                retirada.getSaldoFinal(),
                retirada.getQuantidadeFaltante(),
                retirada.getCustoUnitario(),
                retirada.getDataRetirada());
    }

    private void validarRetirada(
            ImportacaoEstoquePlanilhaRequest.RetiradaImportacao retirada,
            boolean autoCriarOperacoes,
            boolean saldoConsolidado,
            Material material) {
        if (retirada == null || retirada.aba() == null || retirada.aba().isBlank()) {
            throw new IllegalArgumentException("Toda retirada importada precisa informar a aba de origem.");
        }
        if (!autoCriarOperacoes && retirada.comarcaId() == null) {
            throw new IllegalArgumentException(
                    "Vincule a aba " + retirada.aba() + " a uma obra antes de importar.");
        }
        if (autoCriarOperacoes) {
            validarCidade(retirada.cidade(), retirada.aba());
        }
        if (retirada.nomeMaterial() == null || retirada.nomeMaterial().isBlank()) {
            throw new IllegalArgumentException("Toda retirada importada precisa informar o material.");
        }
        if (retirada.saldoInicial() == null
                || retirada.quantidadeRetirada() == null
                || retirada.quantidadeRetirada().signum() < 0
                || retirada.saldoFinal() == null
                || retirada.custoUnitario() == null
                || retirada.custoUnitario().signum() < 0) {
            throw new IllegalArgumentException(
                    "Valores inválidos para " + retirada.nomeMaterial()
                            + " na aba " + retirada.aba()
                            + referenciaLinha(retirada.linhaOrigem()) + ".");
        }
        if (!saldoConsolidado && material != null && !controlaMetragem(material)
                && (!numeroInteiro(retirada.saldoInicial())
                || !numeroInteiro(retirada.quantidadeRetirada())
                || !numeroInteiro(retirada.saldoFinal()))) {
            throw new IllegalArgumentException(
                    "Os saldos e a quantidade retirada de " + retirada.nomeMaterial()
                            + " na aba " + retirada.aba()
                            + referenciaLinha(retirada.linhaOrigem())
                            + " precisam ser números inteiros.");
        }
    }

    private Material novoMaterial(
            ImportacaoEstoquePlanilhaRequest.ItemImportacao item,
            LocalEstoque local,
            Map<String, Integer> sequenciais) {
        Material material = new Material();
        material.setNome(item.nome().trim());
        material.setPartNumber(gerarPartNumber(item.nome(), sequenciais));
        material.setCategoria("MATERIAL_CONSUMO");
        material.setDescricao("Material importado da planilha de estoque.");
        boolean caboEmBobina = ehCaboEmBobina(item.nome());
        boolean fracionado = item.saldoEfetivo().stripTrailingZeros().scale() > 0;
        material.setTipoControle(caboEmBobina
                ? TipoControleEstoque.METRAGEM
                : fracionado ? TipoControleEstoque.FRACIONADO : TipoControleEstoque.UNIDADE);
        material.setUnidadeMedida(caboEmBobina ? UnidadeMedida.METRO : UnidadeMedida.UNIDADE);
        if (caboEmBobina) {
            material.setDimensao("Bobina de 305 m");
            material.setComprimentoPorPeca(METROS_POR_BOBINA_CABO);
        }
        material.setQuantidadeDisponivel(0);
        material.setQuantidadeReservada(0);
        material.setMetragemDisponivel(BigDecimal.ZERO);
        material.setMetragemReservada(BigDecimal.ZERO);
        material.setEstoqueMinimo(BigDecimal.ZERO);
        material.setCustoMedio(item.custoUnitario());
        material.setLocalizacao(local.getNome());
        return material;
    }

    private void normalizarCaboEmBobina(Material material) {
        if (!ehCaboEmBobina(material.getNome())
                || !TipoControleEstoque.FRACIONADO.equals(material.getTipoControle())) {
            return;
        }
        material.setTipoControle(TipoControleEstoque.METRAGEM);
        material.setUnidadeMedida(UnidadeMedida.METRO);
        material.setDimensao("Bobina de 305 m");
        material.setComprimentoPorPeca(METROS_POR_BOBINA_CABO);
        material.setEstoqueMinimo(
                valorNaoNegativo(material.getEstoqueMinimo()).multiply(METROS_POR_BOBINA_CABO));
        materialRepository.save(material);
    }

    private boolean ehCaboEmBobina(String nome) {
        String normalizado = normalizar(nome);
        return (normalizado.contains("caixa") && normalizado.contains("cabo")
                && normalizado.contains("cat6"))
                || (normalizado.contains("bobina") && normalizado.contains("cabo"));
    }

    private boolean controlaMetragem(Material material) {
        return material != null && (TipoControleEstoque.FRACIONADO.equals(material.getTipoControle())
                || TipoControleEstoque.METRAGEM.equals(material.getTipoControle())
                || TipoControleEstoque.BOBINA.equals(material.getTipoControle())
                || TipoControleEstoque.ROLO.equals(material.getTipoControle()));
    }

    private BigDecimal estoqueMinimoImportado(Material material, BigDecimal estoqueMinimo) {
        BigDecimal minimo = valorNaoNegativo(estoqueMinimo);
        return ehCaboEmBobina(material.getNome())
                ? minimo.multiply(METROS_POR_BOBINA_CABO)
                : minimo;
    }

    private String gerarPartNumber(String nome, Map<String, Integer> sequenciais) {
        String base = normalizar(nome).replace(" ", "-").toUpperCase(Locale.ROOT);
        base = base.length() > 24 ? base.substring(0, 24) : base;
        if (base.isBlank()) {
            base = "MATERIAL";
        }
        int sequencial = sequenciais.merge(base, 1, Integer::sum);
        String candidato;
        do {
            candidato = "IMP-" + base + (sequencial > 1 ? "-" + sequencial : "");
            sequencial++;
        } while (materialRepository.existsByPartNumberIgnoreCase(candidato));
        sequenciais.put(base, sequencial - 1);
        return candidato;
    }

    private void validarRequest(ImportacaoEstoquePlanilhaRequest request) {
        if (request == null
                || request.nomeArquivo() == null
                || request.nomeArquivo().isBlank()) {
            throw new IllegalArgumentException("Nome do arquivo é obrigatório.");
        }
        if (request.hashSha256() == null
                || !request.hashSha256().trim().matches("(?i)^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("Identificador da planilha é inválido.");
        }
        if (request.localEstoqueId() == null) {
            throw new IllegalArgumentException("Selecione o depósito que receberá os saldos importados.");
        }
        if (request.itens() == null || request.itens().isEmpty()) {
            throw new IllegalArgumentException("A planilha não possui materiais válidos.");
        }
        if (request.itens().size() > 1000) {
            throw new IllegalArgumentException("A planilha excede o limite de 1.000 materiais.");
        }
        if (request.retiradas() != null && request.retiradas().size() > 5000) {
            throw new IllegalArgumentException("A planilha excede o limite de 5.000 retiradas.");
        }
        if (Boolean.TRUE.equals(request.autoCriarOperacoes())
                && (request.contratoId() == null || request.responsavelId() == null)) {
            throw new IllegalArgumentException(
                    "Selecione o contrato e o responsável para criar as OS e OR importadas.");
        }
        if (request.estoqueMinimoPadrao() != null
                && request.estoqueMinimoPadrao().signum() < 0) {
            throw new IllegalArgumentException("O estoque mínimo da planilha não pode ser negativo.");
        }
        if (request.avisos() != null && request.avisos().stream().anyMatch(this::possuiTexto)) {
            String primeiroAviso = request.avisos().stream()
                    .filter(this::possuiTexto)
                    .findFirst()
                    .orElse("Há linhas inválidas na planilha.");
            throw new IllegalArgumentException(
                    "A importação foi bloqueada porque há linhas inválidas. " + primeiroAviso);
        }
    }

    private void validarConteudoCompleto(ImportacaoEstoquePlanilhaRequest request) {
        Map<String, ImportacaoEstoquePlanilhaRequest.ItemImportacao> itensPorNome =
                new LinkedHashMap<>();
        for (ImportacaoEstoquePlanilhaRequest.ItemImportacao item : request.itens()) {
            validarItem(item);
            String chave = normalizar(item.nome());
            if (itensPorNome.putIfAbsent(chave, item) != null) {
                throw new IllegalArgumentException(
                        "Material duplicado na planilha"
                                + referenciaLinha(item.linhaOrigem()) + ": " + item.nome().trim() + ".");
            }
        }

        Map<String, BigDecimal> saldoEsperadoPorMaterial = new HashMap<>();
        Set<String> materiaisPorAba = new HashSet<>();
        for (ImportacaoEstoquePlanilhaRequest.RetiradaImportacao retirada
                : request.retiradas() != null ? request.retiradas() : List.<ImportacaoEstoquePlanilhaRequest.RetiradaImportacao>of()) {
            validarRetirada(
                    retirada,
                    Boolean.TRUE.equals(request.autoCriarOperacoes()),
                    Boolean.TRUE.equals(request.saldoConsolidado()),
                    null);
            String chaveMaterial = normalizar(retirada.nomeMaterial());
            ImportacaoEstoquePlanilhaRequest.ItemImportacao itemBase = itensPorNome.get(chaveMaterial);
            if (itemBase == null) {
                throw new IllegalArgumentException(
                        "O material " + retirada.nomeMaterial() + " da aba " + retirada.aba()
                                + referenciaLinha(retirada.linhaOrigem())
                                + " não existe no inventário-base da planilha.");
            }
            String chaveAbaMaterial = normalizar(retirada.aba()) + "|" + chaveMaterial;
            if (!materiaisPorAba.add(chaveAbaMaterial)) {
                throw new IllegalArgumentException(
                        "O material " + retirada.nomeMaterial() + " aparece mais de uma vez na aba "
                                + retirada.aba() + ".");
            }

            if (Boolean.TRUE.equals(request.saldoConsolidado())) {
                BigDecimal saldoCalculado = retirada.saldoInicial().subtract(retirada.quantidadeRetirada());
                if (retirada.saldoFinal().compareTo(saldoCalculado) != 0) {
                    throw new IllegalArgumentException(
                            "Saldo final inválido para " + retirada.nomeMaterial() + " na aba "
                                    + retirada.aba() + referenciaLinha(retirada.linhaOrigem())
                                    + ": deveria ser " + saldoCalculado.toPlainString() + ".");
                }
                continue;
            }

            BigDecimal saldoEsperado = saldoEsperadoPorMaterial.getOrDefault(
                    chaveMaterial, itemBase.saldoEfetivo());
            if (retirada.saldoInicial().compareTo(saldoEsperado) != 0) {
                throw new IllegalArgumentException(
                        "Sequência de saldo inválida para " + retirada.nomeMaterial() + " na aba "
                                + retirada.aba() + referenciaLinha(retirada.linhaOrigem())
                                + ": esperado " + saldoEsperado.toPlainString() + ", recebido "
                                + retirada.saldoInicial().toPlainString() + ".");
            }
            BigDecimal saldoCalculado = retirada.saldoInicial().subtract(retirada.quantidadeRetirada());
            if (retirada.saldoFinal().compareTo(saldoCalculado) != 0) {
                throw new IllegalArgumentException(
                        "Saldo final inválido para " + retirada.nomeMaterial() + " na aba "
                                + retirada.aba() + referenciaLinha(retirada.linhaOrigem())
                                + ": deveria ser " + saldoCalculado.toPlainString() + ".");
            }
            saldoEsperadoPorMaterial.put(chaveMaterial, retirada.saldoFinal());
        }
    }

    private void validarInventarioBaseDaComplementacao(
            ImportacaoEstoquePlanilha importacao,
            List<ImportacaoEstoquePlanilhaRequest.ItemImportacao> itensRecebidos) {
        List<ImportacaoEstoqueItemPlanilha> itensOriginais =
                itemImportacaoRepository.findByImportacaoIdOrderByNomePlanilhaAsc(importacao.getId());
        if (itensOriginais.isEmpty()) {
            throw new IllegalStateException(
                    "Não foi possível validar o inventário-base da importação original.");
        }
        Map<String, ImportacaoEstoqueItemPlanilha> originaisPorNome = new HashMap<>();
        for (ImportacaoEstoqueItemPlanilha item : itensOriginais) {
            originaisPorNome.put(normalizar(item.getNomePlanilha()), item);
        }
        if (originaisPorNome.size() != itensRecebidos.size()) {
            throw new IllegalArgumentException(
                    "O inventário-base foi alterado desde a importação original. Importe o arquivo original.");
        }
        for (ImportacaoEstoquePlanilhaRequest.ItemImportacao recebido : itensRecebidos) {
            ImportacaoEstoqueItemPlanilha original = originaisPorNome.get(normalizar(recebido.nome()));
            if (original == null
                    || recebido.saldoEfetivo().compareTo(original.getSaldoImportado()) != 0
                    || recebido.custoUnitario().compareTo(original.getCustoUnitario()) != 0) {
                throw new IllegalArgumentException(
                        "O inventário-base foi alterado para " + recebido.nome()
                                + referenciaLinha(recebido.linhaOrigem())
                                + ". Use os mesmos dados da importação original.");
            }
        }
    }

    private void validarItem(ImportacaoEstoquePlanilhaRequest.ItemImportacao item) {
        if (item == null || item.nome() == null || item.nome().isBlank()) {
            throw new IllegalArgumentException("Todo material precisa ter um nome.");
        }
        if (item.saldoEfetivo().signum() < 0) {
            throw new IllegalArgumentException(
                    "Quantidade inválida para " + item.nome()
                            + referenciaLinha(item.linhaOrigem()) + ".");
        }
        if (item.custoUnitario() == null || item.custoUnitario().signum() < 0) {
            throw new IllegalArgumentException(
                    "Custo unitário inválido para " + item.nome()
                            + referenciaLinha(item.linhaOrigem()) + ".");
        }
    }

    private boolean possuiTexto(String valor) {
        return valor != null && !valor.isBlank();
    }

    private void validarEntrada(ImportacaoEstoquePlanilhaRequest.EntradaImportacao entrada) {
        if (entrada == null
                || entrada.tipo() == null
                || !("ESTOQUE_INICIAL".equalsIgnoreCase(entrada.tipo())
                        || "ADICAO".equalsIgnoreCase(entrada.tipo()))) {
            throw new IllegalArgumentException("O tipo da entrada histórica é inválido.");
        }
        if (entrada.cabecalhoOrigem() == null || entrada.cabecalhoOrigem().isBlank()
                || entrada.nomeMaterial() == null || entrada.nomeMaterial().isBlank()) {
            throw new IllegalArgumentException("Toda entrada histórica precisa informar origem e material.");
        }
        if (entrada.quantidade() == null || entrada.quantidade().signum() <= 0
                || entrada.custoUnitario() == null || entrada.custoUnitario().signum() < 0) {
            throw new IllegalArgumentException(
                    "Valores inválidos na entrada histórica de " + entrada.nomeMaterial()
                            + referenciaLinha(entrada.linhaOrigem()) + ".");
        }
    }

    private String validarCidade(String cidade, String aba) {
        if (cidade == null || cidade.isBlank()) {
            throw new IllegalArgumentException(
                    "Não foi possível identificar a cidade da aba " + aba + ".");
        }
        return cidade.trim();
    }

    private BigDecimal saldoMaterial(Material material) {
        if (material == null) {
            return BigDecimal.ZERO;
        }
        if (TipoControleEstoque.FRACIONADO.equals(material.getTipoControle())
                || TipoControleEstoque.METRAGEM.equals(material.getTipoControle())
                || TipoControleEstoque.BOBINA.equals(material.getTipoControle())
                || TipoControleEstoque.ROLO.equals(material.getTipoControle())) {
            return material.getMetragemDisponivel() != null
                    ? material.getMetragemDisponivel()
                    : BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(
                material.getQuantidadeDisponivel() != null ? material.getQuantidadeDisponivel() : 0);
    }

    private void validarAtualizacaoCustos(AtualizacaoCustosPlanilhaRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Informe a planilha de atualização de custos.");
        }
        if (request.nomeArquivo() == null || request.nomeArquivo().isBlank()) {
            throw new IllegalArgumentException("O nome do arquivo é obrigatório.");
        }
        if (request.hashSha256() == null
                || !request.hashSha256().trim().matches("(?i)^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("O identificador da planilha é inválido.");
        }
        if (request.itens() == null || request.itens().isEmpty()) {
            throw new IllegalArgumentException("A planilha não possui custos vinculados a materiais.");
        }
    }

    private void validarSincronizacaoSaldos(SincronizacaoSaldosPlanilhaRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Informe a planilha de sincronização de saldos.");
        }
        if (request.nomeArquivo() == null || request.nomeArquivo().isBlank()) {
            throw new IllegalArgumentException("O nome do arquivo é obrigatório.");
        }
        if (request.hashSha256() == null
                || !request.hashSha256().trim().matches("(?i)^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("O identificador da planilha é inválido.");
        }
        if (request.localEstoqueId() == null) {
            throw new IllegalArgumentException("Selecione o depósito que receberá os ajustes.");
        }
        if (request.itens() == null || request.itens().isEmpty()) {
            throw new IllegalArgumentException("A aba ESTOQUE ATUAL não possui saldos válidos.");
        }
        if (request.itens().size() > 1000) {
            throw new IllegalArgumentException("A sincronização aceita no máximo 1.000 materiais.");
        }
        for (SincronizacaoSaldosPlanilhaRequest.ItemSaldo item : request.itens()) {
            if (item == null || item.materialId() == null
                    || item.nomePlanilha() == null || item.nomePlanilha().isBlank()
                    || item.saldo() == null || item.saldo().signum() < 0) {
                throw new IllegalArgumentException(
                        "A sincronização contém uma linha sem material ou com saldo inválido.");
            }
        }
    }

    private void validarItemSincronizacao(
            SincronizacaoSaldosPlanilhaRequest.ItemSaldo item,
            Set<Long> materiaisRecebidos) {
        if (!materiaisRecebidos.add(item.materialId())) {
            throw new IllegalArgumentException(
                    "O mesmo material aparece mais de uma vez na sincronização de saldos.");
        }
    }

    private BigDecimal saldoReservado(Material material) {
        if (controlaMetragem(material)) {
            return valorNaoNegativo(material.getMetragemReservada());
        }
        return BigDecimal.valueOf(
                Math.max(0, material.getQuantidadeReservada() != null
                        ? material.getQuantidadeReservada()
                        : 0));
    }

    private String hashModo(String modo, String hashOriginal) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    (modo + ":" + hashOriginal.trim().toLowerCase(Locale.ROOT))
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder hexadecimal = new StringBuilder(bytes.length * 2);
            for (byte valor : bytes) {
                hexadecimal.append(String.format("%02x", valor & 0xff));
            }
            return hexadecimal.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 não está disponível no servidor.", exception);
        }
    }

    private BigDecimal valorNaoNegativo(BigDecimal valor) {
        return valor != null ? valor.max(BigDecimal.ZERO) : BigDecimal.ZERO;
    }

    private boolean numeroInteiro(BigDecimal valor) {
        return valor.stripTrailingZeros().scale() <= 0;
    }

    private String referenciaLinha(Integer linha) {
        return linha != null ? ", linha " + linha : "";
    }

    private String normalizar(String valor) {
        if (valor == null) {
            return "";
        }
        return Normalizer.normalize(valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{Alnum}]+", " ")
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String limitar(String valor, int limite) {
        if (valor == null) {
            return null;
        }
        return valor.length() <= limite ? valor : valor.substring(0, limite);
    }
}
