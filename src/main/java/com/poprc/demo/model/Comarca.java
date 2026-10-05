package com.poprc.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "comarcas")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Comarca {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nomeComarca;

    private String endereco;

    private String juizResponsavel;

    private String contatoLocal;

    private Integer quantidadePontos;

    private String situacao;

    @Column(precision = 19, scale = 2)
    private BigDecimal percentualConcluido;

    @Column(columnDefinition = "TEXT")
    private String pendencias;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projeto_id")
    @JsonIgnoreProperties({ "materiais" })
    private Projeto projeto;

    // 💥 NOVOS CAMPOS EXIGIDOS PELA REGRA DE NEGÓCIO 💥

    @OneToOne
    @JoinColumn(name = "ordem_servico_id", referencedColumnName = "id")
    private OrdemServico ordemServico;

    // Mude de "private int" para isso aqui 💥
    @Column(name = "etapa_atual", columnDefinition = "integer default 1")
    private Integer etapaAtual = 1; // 1 = Vistoria Técnica, 2 = Infraestrutura

    @Column(columnDefinition = "TEXT")
    private String assinaturaBase64; // Armazena a rubrica digitalizada do canvas

    private String fotoVistoriaUrl; // Armazena a prova fotográfica do local

    @Column(name = "as_built_status")
    private String asBuiltStatus = "PENDENTE";

    @Column(name = "as_built_homologacao_legada_sem_justificativa", nullable = false)
    private Boolean asBuiltHomologacaoLegadaSemJustificativa = false;

    @Column(columnDefinition = "TEXT")
    private String viradaRedeProvasFuncionamento;

    @Column(columnDefinition = "TEXT")
    private String viradaRedeChecklist;

    private Boolean viradaRedeConcluida = false;

    private LocalDateTime dataConclusao;

    private String concluidaPor;

    private Boolean arquivado = false;

    private LocalDateTime arquivadoEm;

    private String arquivadoPor;

    @Column(length = 1000)
    private String motivoArquivamento;

    private Boolean faltouMaterial = false;

    @Column(columnDefinition = "TEXT")
    private String descricaoMaterialFaltante;

    @OneToMany(mappedBy = "comarca", cascade = CascadeType.ALL)
    @JsonIgnoreProperties({ "comarca" })
    private List<MaterialItem> materiais;
}
