package com.poprc.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "ordens_retirada_documentos",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_or_documento_fase",
                columnNames = { "ordem_retirada_id", "fase" }))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrdemRetiradaDocumento {

    public boolean isSimulacao() {
        return ordemRetirada != null && ordemRetirada.isSimulacao();
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ordem_retirada_id", nullable = false)
    @JsonIgnore
    private OrdemRetirada ordemRetirada;

    @Column(nullable = false, length = 24)
    private String fase;

    @Column(nullable = false, length = 24)
    private String statusOr;

    @Column(nullable = false, length = 512)
    private String pdfPath;

    @Column(nullable = false, length = 64)
    private String pdfHash;

    @Column(nullable = false)
    private LocalDateTime geradoEm;
}
