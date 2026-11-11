package com.poprc.demo.service;

import com.poprc.demo.model.Material;
import com.poprc.demo.model.MovimentacaoEstoque;
import com.poprc.demo.model.StatusUnidadeRastreavel;
import com.poprc.demo.model.TipoControleEstoque;
import com.poprc.demo.model.TipoMovimentacao;
import com.poprc.demo.model.UnidadeEstoqueRastreavel;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.MovimentacaoEstoqueRepository;
import com.poprc.demo.repository.UnidadeEstoqueRastreavelRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UnidadeEstoqueRastreavelService {
    @org.springframework.beans.factory.annotation.Autowired
    private ReservaRetiradaV2Guard reservaV2;


    private final UnidadeEstoqueRastreavelRepository unidadeRepository;
    private final MaterialRepository materialRepository;
    private final MovimentacaoEstoqueRepository movimentacaoRepository;
    private final SaldoLocalService saldoLocalService;

    @Transactional(readOnly = true)
    public List<UnidadeEstoqueRastreavel> listar(Long materialId) {
        return materialId != null
                ? unidadeRepository.findByMaterialIdOrderByDataEntradaDesc(materialId)
                : unidadeRepository.findAllByOrderByDataEntradaDesc();
    }

    @Transactional
    public UnidadeEstoqueRastreavel cadastrar(Long materialId, String codigo, BigDecimal metragemInicial,
            String observacao, Long localEstoqueId) {
        if (reservaV2 != null) reservaV2.lock();
        if (codigo == null || codigo.isBlank()) {
            throw new IllegalArgumentException("Código da bobina/rolo é obrigatório.");
        }
        if (metragemInicial == null || metragemInicial.signum() <= 0) {
            throw new IllegalArgumentException("A metragem inicial deve ser maior que zero.");
        }
        PrecisaoEstoque.metragem(metragemInicial, "A metragem inicial");
        if (unidadeRepository.existsByCodigoIgnoreCase(codigo.trim())) {
            throw new IllegalArgumentException("Já existe uma bobina/rolo com este código.");
        }

        Material material = materialRepository.findByIdForUpdate(materialId)
                .orElseThrow(() -> new IllegalArgumentException("Material não encontrado."));
        if (!rastreavel(material.getTipoControle())) {
            throw new IllegalArgumentException("O material deve usar controle por bobina ou rolo.");
        }

        BigDecimal saldoAnterior = valor(material.getMetragemDisponivel());
        BigDecimal saldoPosterior = saldoAnterior.add(metragemInicial);
        PrecisaoEstoque.metragem(saldoPosterior, "O saldo resultante");
        UnidadeEstoqueRastreavel unidade = new UnidadeEstoqueRastreavel();
        unidade.setCodigo(codigo.trim());
        unidade.setMaterial(material);
        unidade.setLocalEstoque(saldoLocalService.creditar(material, localEstoqueId, metragemInicial));
        unidade.setTipo(material.getTipoControle());
        unidade.setMetragemInicial(metragemInicial);
        unidade.setMetragemAtual(metragemInicial);
        unidade.setDataEntrada(LocalDateTime.now());
        unidade.setStatus(StatusUnidadeRastreavel.LACRADA);
        unidade.setObservacao(observacao);
        UnidadeEstoqueRastreavel salva = unidadeRepository.save(unidade);

        material.setMetragemDisponivel(saldoPosterior);
        materialRepository.save(material);

        MovimentacaoEstoque movimentacao = new MovimentacaoEstoque();
        movimentacao.setMaterial(material);
        movimentacao.setUnidadeRastreavel(salva);
        movimentacao.setMetragem(metragemInicial);
        movimentacao.setUnidadeMedida(material.getUnidadeMedida());
        movimentacao.setTipo(TipoMovimentacao.ENTRADA);
        movimentacao.setDataMovimentacao(LocalDateTime.now());
        movimentacao.setObservacao("Entrada da " + material.getTipoControle().name().toLowerCase()
                + " " + salva.getCodigo() + ".");
        movimentacao.setMotivo("Entrada de unidade rastreável");
        movimentacao.setLancadoPor("Sistema");
        movimentacao.setSaldoAnterior(saldoAnterior);
        movimentacao.setSaldoPosterior(material.getMetragemDisponivel());
        movimentacao.setEstoqueDestino(salva.getLocalEstoque().getNome());
        movimentacaoRepository.save(movimentacao);
        return salva;
    }

    @Transactional
    public UnidadeEstoqueRastreavel transferir(Long unidadeId, Long destinoId, String motivo,
            String lancadoPor, String autorizadoPor) {
        if (reservaV2 != null) reservaV2.lock();
        validarTexto(motivo, "Motivo da transferência é obrigatório.");
        validarTexto(lancadoPor, "Informe quem lançou a transferência.");
        validarTexto(autorizadoPor, "Informe quem autorizou a transferência.");
        UnidadeEstoqueRastreavel unidade = unidadeRepository.findByIdForUpdate(unidadeId)
                .orElseThrow(() -> new IllegalArgumentException("Bobina/rolo não encontrado."));
        materialRepository.findByIdForUpdate(unidade.getMaterial().getId())
                .orElseThrow(() -> new IllegalArgumentException("Material não encontrado."));
        if (unidade.getLocalEstoque() == null) {
            throw new IllegalArgumentException("Bobina/rolo sem depósito de origem definido.");
        }
        if (unidade.getMetragemAtual() == null || unidade.getMetragemAtual().signum() <= 0) {
            throw new IllegalArgumentException("Bobina/rolo esgotado não pode ser transferido.");
        }

        SaldoLocalService.TransferenciaLocal transferencia = saldoLocalService.transferir(
                unidade.getMaterial(), unidade.getLocalEstoque().getId(), destinoId, unidade.getMetragemAtual());
        String origem = transferencia.origem().getNome();
        unidade.setLocalEstoque(transferencia.destino());
        UnidadeEstoqueRastreavel salva = unidadeRepository.save(unidade);

        MovimentacaoEstoque movimentacao = new MovimentacaoEstoque();
        movimentacao.setMaterial(unidade.getMaterial());
        movimentacao.setUnidadeRastreavel(unidade);
        movimentacao.setMetragem(unidade.getMetragemAtual());
        movimentacao.setUnidadeMedida(unidade.getMaterial().getUnidadeMedida());
        movimentacao.setTipo(TipoMovimentacao.TRANSFERENCIA);
        movimentacao.setDataMovimentacao(LocalDateTime.now());
        movimentacao.setSaldoAnterior(unidade.getMaterial().getMetragemDisponivel());
        movimentacao.setSaldoPosterior(unidade.getMaterial().getMetragemDisponivel());
        movimentacao.setEstoqueOrigem(origem);
        movimentacao.setEstoqueDestino(transferencia.destino().getNome());
        movimentacao.setMotivo(motivo.trim());
        movimentacao.setObservacao("Transferência integral de " + unidade.getCodigo() + ". " + motivo.trim());
        movimentacao.setLancadoPor(lancadoPor.trim());
        movimentacao.setAutorizadoPor(autorizadoPor.trim());
        movimentacaoRepository.save(movimentacao);
        return salva;
    }

    private boolean rastreavel(TipoControleEstoque tipo) {
        return TipoControleEstoque.BOBINA.equals(tipo) || TipoControleEstoque.ROLO.equals(tipo);
    }

    private BigDecimal valor(BigDecimal valor) {
        return valor != null ? valor : BigDecimal.ZERO;
    }

    private void validarTexto(String valor, String mensagem) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(mensagem);
        }
    }
}
