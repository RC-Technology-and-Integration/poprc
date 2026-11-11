package com.poprc.demo.service;

import com.poprc.demo.exception.SaldoInsuficienteException;
import com.poprc.demo.model.LocalEstoque;
import com.poprc.demo.model.Material;
import com.poprc.demo.model.SaldoMaterialLocal;
import com.poprc.demo.model.TipoControleEstoque;
import com.poprc.demo.repository.LocalEstoqueRepository;
import com.poprc.demo.repository.MaterialRepository;
import com.poprc.demo.repository.SaldoMaterialLocalRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SaldoLocalService {
    @org.springframework.beans.factory.annotation.Autowired
    private ReservaRetiradaV2Guard reservaV2;

    private static final String ESTOQUE_PRINCIPAL = "Estoque Principal";

    private final LocalEstoqueRepository localRepository;
    private final SaldoMaterialLocalRepository saldoRepository;
    private final MaterialRepository materialRepository;

    @Transactional(readOnly = true)
    public List<LocalEstoque> listarLocais() {
        return localRepository.findAllByOrderByNomeAsc();
    }

    @Transactional(readOnly = true)
    public List<SaldoMaterialLocal> listarSaldos(Long materialId) {
        return materialId != null
                ? saldoRepository.findByMaterialIdOrderByLocalEstoqueNomeAsc(materialId)
                : saldoRepository.findAllByOrderByMaterialNomeAscLocalEstoqueNomeAsc();
    }

    @Transactional
    public SaldoMaterialLocal atualizarEstoqueMinimo(Long saldoId, BigDecimal estoqueMinimo) {
        if (reservaV2 != null) reservaV2.lock();
        SaldoMaterialLocal saldo = saldoRepository.findByIdForUpdate(saldoId)
                .orElseThrow(() -> new IllegalArgumentException("Saldo local não encontrado."));
        if (estoqueMinimo != null && estoqueMinimo.signum() < 0) {
            throw new IllegalArgumentException("O estoque mínimo local não pode ser negativo.");
        }
        saldo.setEstoqueMinimo(estoqueMinimo);
        return saldoRepository.save(saldo);
    }

    @Transactional
    public LocalEstoque cadastrarLocal(String nome, String endereco) {
        if (reservaV2 != null) reservaV2.lock();
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("Nome do depósito é obrigatório.");
        }
        if (localRepository.findByNomeIgnoreCase(nome.trim()).isPresent()) {
            throw new IllegalArgumentException("Já existe um depósito com este nome.");
        }
        LocalEstoque local = new LocalEstoque();
        local.setNome(nome.trim());
        local.setEndereco(endereco);
        local.setAtivo(true);
        return localRepository.save(local);
    }

    @Transactional
    public void sincronizarSaldoInicial(Material material) {
        if (reservaV2 != null) reservaV2.lock();
        if (saldoRepository.existsByMaterialId(material.getId())) {
            return;
        }
        LocalEstoque local = resolverOuCriar(material.getLocalizacao());
        SaldoMaterialLocal saldo = novoSaldo(material, local);
        saldo.setQuantidadeDisponivel(valor(material.getQuantidadeDisponivel()));
        saldo.setQuantidadeReservada(valor(material.getQuantidadeReservada()));
        saldo.setMetragemDisponivel(valor(material.getMetragemDisponivel()));
        saldo.setMetragemReservada(valor(material.getMetragemReservada()));
        saldoRepository.save(saldo);
        material.setLocalizacao(local.getNome());
    }

    @Transactional
    public void sincronizarReservas(Material material) {
        if (reservaV2 != null) reservaV2.lock();
        Material bloqueado = bloquearMaterial(material);
        List<SaldoMaterialLocal> saldos = saldoRepository.findByMaterialIdForUpdate(bloqueado.getId());
        BigDecimal restante = controlaMetragem(bloqueado)
                ? valor(bloqueado.getMetragemReservada())
                : BigDecimal.valueOf(valor(bloqueado.getQuantidadeReservada()));
        BigDecimal totalLocal = saldos.stream().map(saldo -> saldoValor(bloqueado, saldo))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (restante.signum() < 0 || restante.compareTo(totalLocal) > 0) {
            throw new SaldoInsuficienteException(
                    "Saldo dos depósitos insuficiente para a reserva de " + bloqueado.getNome() + ".");
        }
        BigDecimal identificada = saldos.stream().map(s -> reservaV2 == null ? BigDecimal.ZERO : reservaV2.fixa(bloqueado.getId(), s.getLocalEstoque().getId())).reduce(BigDecimal.ZERO, BigDecimal::add);
        restante = restante.subtract(identificada);
        if (restante.signum() < 0) throw new SaldoInsuficienteException("Reserva identificada excede reserva global.");
        for (SaldoMaterialLocal saldo : saldos) {
            BigDecimal fixa = reservaV2 == null ? BigDecimal.ZERO : reservaV2.fixa(bloqueado.getId(), saldo.getLocalEstoque().getId());
            BigDecimal capacidade = saldoValor(bloqueado, saldo).subtract(fixa);
            if (capacidade.signum() < 0) throw new SaldoInsuficienteException("Depósito contém reserva de OR V2; operação invadiria a separação identificada.");
            BigDecimal parcela = capacidade.min(restante);
            BigDecimal reservado = fixa.add(parcela);
            saldo.setQuantidadeReservada(controlaMetragem(bloqueado) ? 0 : reservado.intValueExact());
            saldo.setMetragemReservada(controlaMetragem(bloqueado) ? reservado : BigDecimal.ZERO);
            saldoRepository.save(saldo);
            restante = restante.subtract(parcela);
        }
        if (restante.signum() != 0) throw new SaldoInsuficienteException("Saldo local não cobre reservas.");
    }

    @Transactional
    public LocalEstoque creditar(Material material, Long localId, BigDecimal valorCredito) {
        if (reservaV2 != null) reservaV2.lock();
        material = bloquearMaterial(material);
        LocalEstoque local = localId != null ? buscarLocal(localId) : resolverOuCriar(material.getLocalizacao());
        SaldoMaterialLocal saldo = obterOuCriar(material, local);
        atualizarSaldo(saldo, saldoValor(material, saldo).add(valorCredito));
        saldoRepository.save(saldo);
        sincronizarReservas(material);
        return local;
    }

    @Transactional
    public LocalEstoque creditarPadrao(Material material, BigDecimal valorCredito) {
        if (reservaV2 != null) reservaV2.lock();
        return creditar(material, null, valorCredito);
    }

    @Transactional
    public LocalEstoque debitar(Material material, Long localId, BigDecimal valorDebito) {
        if (reservaV2 != null) reservaV2.lock();
        material = bloquearMaterial(material);
        if (localId == null) {
            throw new IllegalArgumentException("Depósito do ajuste é obrigatório.");
        }
        LocalEstoque local = buscarLocal(localId);
        SaldoMaterialLocal saldo = saldoRepository
                .findByMaterialIdAndLocalEstoqueIdForUpdate(material.getId(), localId)
                .orElseThrow(() -> new IllegalArgumentException("Material sem saldo neste depósito."));
        BigDecimal disponivel = saldoValor(material, saldo);
        if (valorDebito.signum() <= 0 || valorDebito.compareTo(disponivel) > 0) {
            throw new IllegalArgumentException("Ajuste superior ao saldo do depósito. Disponível: " + disponivel + ".");
        }
        atualizarSaldo(saldo, disponivel.subtract(valorDebito));
        saldoRepository.save(saldo);
        sincronizarReservas(material);
        return local;
    }

    @Transactional
    public List<MovimentoLocal> debitarDistribuido(Material material, BigDecimal valorDebito) {
        if (reservaV2 != null) reservaV2.lock();
        Material materialBloqueado = bloquearMaterial(material);
        List<SaldoMaterialLocal> saldos = new ArrayList<>(
                saldoRepository.findByMaterialIdForUpdate(materialBloqueado.getId()));
        saldos.sort(Comparator.comparing(
                (SaldoMaterialLocal saldo) -> saldoValor(materialBloqueado, saldo)).reversed());
        BigDecimal restante = valorDebito;
        List<MovimentoLocal> movimentos = new ArrayList<>();
        for (SaldoMaterialLocal saldo : saldos) {
            if (restante.signum() == 0) {
                break;
            }
            BigDecimal disponivel = saldoValor(materialBloqueado, saldo).subtract(reservaV2 == null ? BigDecimal.ZERO : reservaV2.fixa(materialBloqueado.getId(), saldo.getLocalEstoque().getId()));
            BigDecimal debitado = disponivel.min(restante);
            if (debitado.signum() <= 0) {
                continue;
            }
            atualizarSaldo(saldo, saldoValor(materialBloqueado, saldo).subtract(debitado));
            saldoRepository.save(saldo);
            movimentos.add(new MovimentoLocal(saldo.getLocalEstoque(), debitado));
            restante = restante.subtract(debitado);
        }
        if (restante.signum() > 0) {
            throw new SaldoInsuficienteException(
                    "Saldo insuficiente nos depósitos para " + materialBloqueado.getNome() + ".");
        }
        return movimentos;
    }

    @Transactional
    public TransferenciaLocal transferir(Material material, Long origemId, Long destinoId, BigDecimal valorTransferido) {
        if (reservaV2 != null) reservaV2.lock();
        material = bloquearMaterial(material);
        if (origemId == null || destinoId == null || origemId.equals(destinoId)) {
            throw new IllegalArgumentException("Informe depósitos de origem e destino diferentes.");
        }
        LocalEstoque origem = buscarLocal(origemId);
        LocalEstoque destino = buscarLocal(destinoId);
        SaldoMaterialLocal saldoOrigem = saldoRepository
                .findByMaterialIdAndLocalEstoqueIdForUpdate(material.getId(), origemId)
                .orElseThrow(() -> new IllegalArgumentException("Material sem saldo no depósito de origem."));
        BigDecimal disponivel = saldoValor(material, saldoOrigem);
        if (valorTransferido.signum() <= 0 || valorTransferido.compareTo(disponivel) > 0) {
            throw new IllegalArgumentException("Valor de transferência inválido. Disponível na origem: " + disponivel + ".");
        }
        boolean decimal = controlaMetragem(material);
        PrecisaoEstoque.saldo(valorTransferido, decimal, "O valor da transferência");
        SaldoMaterialLocal saldoDestino = obterOuCriar(material, destino);
        BigDecimal saldoDestinoResultante = saldoValor(material, saldoDestino).add(valorTransferido);
        PrecisaoEstoque.saldo(saldoDestinoResultante, decimal, "O saldo de destino");

        atualizarSaldo(saldoOrigem, disponivel.subtract(valorTransferido));
        saldoRepository.save(saldoOrigem);
        atualizarSaldo(saldoDestino, saldoDestinoResultante);
        saldoRepository.save(saldoDestino);
        sincronizarReservas(material);
        return new TransferenciaLocal(origem, destino);
    }

    public String descreverMovimentos(List<MovimentoLocal> movimentos) {
        return movimentos.stream()
                .map(movimento -> movimento.local().getNome() + " (" + movimento.valor().stripTrailingZeros().toPlainString() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
    }

    private LocalEstoque resolverOuCriar(String nome) {
        String nomeNormalizado = nome != null && !nome.isBlank() ? nome.trim() : ESTOQUE_PRINCIPAL;
        return localRepository.findByNomeIgnoreCase(nomeNormalizado).orElseGet(() -> {
            LocalEstoque local = new LocalEstoque();
            local.setNome(nomeNormalizado);
            local.setAtivo(true);
            return localRepository.save(local);
        });
    }

    private LocalEstoque buscarLocal(Long id) {
        return localRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Depósito não encontrado."));
    }

    private SaldoMaterialLocal obterOuCriar(Material material, LocalEstoque local) {
        return saldoRepository.findByMaterialIdAndLocalEstoqueIdForUpdate(material.getId(), local.getId())
                .orElseGet(() -> novoSaldo(material, local));
    }

    private Material bloquearMaterial(Material material) {
        if (material == null || material.getId() == null) {
            throw new IllegalArgumentException("Material é obrigatório para alterar o saldo local.");
        }
        return materialRepository.findByIdForUpdate(material.getId())
                .orElseThrow(() -> new IllegalArgumentException("Material não encontrado."));
    }

    private SaldoMaterialLocal novoSaldo(Material material, LocalEstoque local) {
        SaldoMaterialLocal saldo = new SaldoMaterialLocal();
        saldo.setMaterial(material);
        saldo.setLocalEstoque(local);
        return saldo;
    }

    private BigDecimal saldoValor(Material material, SaldoMaterialLocal saldo) {
        return controlaMetragem(material)
                ? valor(saldo.getMetragemDisponivel())
                : BigDecimal.valueOf(valor(saldo.getQuantidadeDisponivel()));
    }

    private void atualizarSaldo(SaldoMaterialLocal saldo, BigDecimal valorSaldo) {
        if (controlaMetragem(saldo.getMaterial())) {
            saldo.setMetragemDisponivel(valorSaldo);
        } else {
            saldo.setQuantidadeDisponivel(valorSaldo.intValueExact());
        }
    }

    private boolean controlaMetragem(Material material) {
        return material != null && (TipoControleEstoque.FRACIONADO.equals(material.getTipoControle())
                || TipoControleEstoque.METRAGEM.equals(material.getTipoControle())
                || TipoControleEstoque.BOBINA.equals(material.getTipoControle())
                || TipoControleEstoque.ROLO.equals(material.getTipoControle()));
    }

    private int valor(Integer valor) {
        return valor != null ? valor : 0;
    }

    private BigDecimal valor(BigDecimal valor) {
        return valor != null ? valor : BigDecimal.ZERO;
    }

    public record MovimentoLocal(LocalEstoque local, BigDecimal valor) {
    }

    public record TransferenciaLocal(LocalEstoque origem, LocalEstoque destino) {
    }
}
