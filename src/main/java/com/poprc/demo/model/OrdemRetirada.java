package com.poprc.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ordens_retirada")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrdemRetirada {

    public boolean isSimulacao() {
        return ordemServico != null && ordemServico.isSimulacao();
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(unique = true, nullable = false)
    private String numeroOr;

    private String status = "GERADA";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ordem_servico_id")
    @JsonIgnoreProperties({ "contrato", "projeto", "hibernateLazyInitializer", "handler" })
    private OrdemServico ordemServico;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comarca_id")
    @JsonIgnoreProperties({ "materiais", "projeto", "ordemServico", "hibernateLazyInitializer", "handler" })
    private Comarca comarca;

    private LocalDateTime dataGeracao;

    private LocalDateTime dataRetirada;

    private LocalDateTime dataDevolucao;

    private String geradoPor;

    private String conferidoPor;

    private String levadoPor;

    private String devolvidoPor;

    private String recebidoPor;

    @Column(columnDefinition = "TEXT")
    private String assinaturaConferenteBase64;

    @Column(columnDefinition = "TEXT")
    private String assinaturaRetiranteBase64;

    @Column(columnDefinition = "TEXT")
    private String assinaturaRecebimentoBase64;

    @OneToMany(mappedBy = "ordemRetirada", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnoreProperties("ordemRetirada")
    private List<OrdemRetiradaItem> itens = new ArrayList<>();
}
