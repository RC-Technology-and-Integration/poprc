package com.poprc.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ordens_servico")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrdemServico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private boolean simulacao;

    @Column(unique = true, nullable = false)
    private String numeroOs;

    private String titulo;

    private LocalDate dataExecucao;

    private LocalDateTime dataHoraInicio;

    private LocalDateTime dataHoraFim;

    private LocalDateTime deadline;

    @Column(columnDefinition = "TEXT")
    private String descricao;

    @Column(columnDefinition = "TEXT")
    private String checklist;

    @Column(columnDefinition = "TEXT")
    private String assinaturaDigital;

    @Enumerated(EnumType.STRING)
    private StatusOS status;

    private Boolean arquivado = false;

    private LocalDateTime arquivadoEm;

    private String arquivadoPor;

    @Column(length = 1000)
    private String motivoArquivamento;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contrato_id")
    private Contrato contrato;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projeto_id")
    private Projeto projeto;
}
