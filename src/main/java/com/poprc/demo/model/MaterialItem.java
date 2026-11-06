package com.poprc.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import lombok.Data;

@Entity
@Table(name = "comarca_materiais")
@Data
public class MaterialItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nomeMaterial;
    @Column(precision = 14, scale = 3, nullable = false)
    private BigDecimal quantidadePrevista = BigDecimal.ZERO;

    @Column(precision = 14, scale = 3, nullable = false)
    private BigDecimal quantidadeAuditada = BigDecimal.ZERO;
    private Boolean estoqueReservado = false;
    private Boolean estoqueBaixado = false;
    @Column(precision = 14, scale = 3, nullable = false)
    private BigDecimal quantidadeRetiradaV2 = BigDecimal.ZERO;
    private Boolean materialFaltante = false;
    private Boolean itemAdicional = false;

    @Column(columnDefinition = "TEXT")
    private String descricaoFaltante;

    private LocalDateTime dataHoraSolicitacao;
    private LocalDateTime dataHoraRetirada;
    private LocalDateTime dataHoraUso;

    @ManyToOne
    @JoinColumn(name = "material_id")
    private Material material;

    @ManyToOne
    @JoinColumn(name = "comarca_id")
    private Comarca comarca;
}
