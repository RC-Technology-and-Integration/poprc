package com.poprc.demo.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;

@Entity
@Table(name = "historico_homologacao_as_built")
@Data
public class HistoricoHomologacaoAsBuilt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comarca_id", nullable = false, updatable = false)
    private Comarca comarca;

    @Column(nullable = false, length = 40, updatable = false)
    private String status;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String justificativa;

    @Column(nullable = false, length = 160, updatable = false)
    private String responsavel;

    @Column(name = "registrado_em", nullable = false, updatable = false)
    private LocalDateTime registradoEm;
}
