package com.poprc.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "documentos_internos")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentoInterno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private boolean simulacao;

    private String tipo;

    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comarca_id")
    @JsonIgnoreProperties({ "materiais" })
    private Comarca comarca;

    @Column(columnDefinition = "TEXT")
    private String conteudoJson;

    @Column(columnDefinition = "TEXT")
    private String assinaturaBase64;

    @Column(columnDefinition = "TEXT")
    private String assinaturaTecnicoBase64;

    private String tecnicoAssinadoPor;

    private LocalDateTime dataAssinaturaTecnico;

    @Column(columnDefinition = "TEXT")
    private String assinaturaGestorBase64;

    private String gestorAssinadoPor;

    private LocalDateTime dataAssinaturaGestor;

    @Column(columnDefinition = "TEXT")
    private String assinaturaGerenteBase64;

    private String gerenteAssinadoPor;

    private LocalDateTime dataAssinaturaGerente;

    private String criadoPor;

    private String recebidoPor;

    private LocalDateTime dataGeracao;

    private LocalDateTime dataAssinatura;

    @Column(length = 128)
    private String hashRegistro;

    private String pdfPath;

    @Column(length = 64)
    private String pdfHash;

    private LocalDateTime pdfGeradoEm;

    private LocalDateTime invalidadoEm;

    private String invalidadoPor;

    @Column(columnDefinition = "TEXT")
    private String motivoInvalidacao;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documento_origem_id")
    @JsonIgnoreProperties({ "comarca", "documentoOrigem" })
    private DocumentoInterno documentoOrigem;
}
