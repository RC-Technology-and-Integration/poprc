package com.poprc.demo.service;

import com.poprc.demo.exception.DescricaoAtividadeObrigatoriaException;
import com.poprc.demo.model.AtividadeComarca;
import com.poprc.demo.model.Comarca;
import com.poprc.demo.repository.AtividadeComarcaRepository;
import com.poprc.demo.repository.ComarcaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises both persisted-text paths without Spring, a database, or photo writes. */
class AtividadeComarcaSanitizacaoTest {
    private final LocalDate inicio = LocalDate.of(2026, 10, 19);
    private AtividadeComarcaRepository atividades;
    private ComarcaRepository comarcas;
    private AtividadeComarcaService service;

    @BeforeEach
    void setUp() {
        atividades = mock(AtividadeComarcaRepository.class);
        comarcas = mock(ComarcaRepository.class);
        service = new AtividadeComarcaService(atividades, comarcas);
    }

    static Stream<Arguments> textos() {
        return Stream.of(
                Arguments.of("texto", "Instalação concluída em João Pessoa — ação, conexão e ç."),
                Arguments.of("espaços", "  Revisão concluída.  "),
                Arguments.of("entidades", "A &amp; B &lt;teste&gt; &#231; &#xE3;"),
                Arguments.of("marcação", "<p>Instalação <b>concluída</b>.</p>"),
                Arguments.of("script", "Revisão<script>alert(1)</script><img src=x onerror=alert(1)> segura"),
                Arguments.of("aninhamento incompleto", "<div><b>Conexão <i>revisada"),
                Arguments.of("raw text incompleto", "<textarea>Conexão &amp; revisão"),
                // A trailing non-whitespace control character is pertinent to the Cleaner advisory.
                Arguments.of("tag com controle", "<style" + (char) 0x1f
                        + ">antes<img src=x onerror=alert(1)></style" + (char) 0x1f + ">Depois"),
                Arguments.of("xml como descrição HTML", "<r xmlns:a='urn:a'><a:x xmlns:b='urn:b'>Revisão</a:x></r>")
        );
    }

    // Explicit persisted representations: entities stay escaped for the HTML consumer.
    private static String esperado(String caso) {
        return switch (caso) {
            case "texto" -> "Instalação concluída em João Pessoa — ação, conexão e ç.";
            case "espaços" -> "Revisão concluída.";
            case "entidades" -> "A &amp; B &lt;teste&gt; ç ã";
            case "marcação" -> "Instalação concluída.";
            case "script" -> "Revisão segura";
            case "aninhamento incompleto" -> "Conexão revisada";
            case "raw text incompleto" -> "Conexão &amp; revisão";
            case "tag com controle" -> "antesDepois";
            case "xml como descrição HTML" -> "Revisão";
            default -> throw new IllegalArgumentException(caso);
        };
    }

    @ParameterizedTest(name = "cadastro: {0}")
    @MethodSource("textos")
    void sanitizaAoCadastrar(String caso, String entrada) {
        Comarca comarca = new Comarca();
        when(comarcas.findById(1L)).thenReturn(Optional.of(comarca));
        when(atividades.save(any(AtividadeComarca.class))).thenAnswer(call -> call.getArgument(0));

        AtividadeComarca salva = service.registrarAtividade(1L, inicio, null, entrada, List.of());

        assertEquals(esperado(caso), salva.getDescricaoAtividades());
        assertSame(comarca, salva.getComarca());
        assertEquals(inicio, salva.getDataInicio());
        assertEquals(List.of(), salva.getFotosEvidencia());
        verify(atividades).save(salva);
    }

    @ParameterizedTest(name = "edição: {0}")
    @MethodSource("textos")
    void sanitizaAoEditar(String caso, String entrada) {
        AtividadeComarca existente = new AtividadeComarca();
        existente.getFotosEvidencia().add("/uploads/comarcas/evidencia-sintetica.png");
        when(atividades.findById(2L)).thenReturn(Optional.of(existente));
        when(atividades.save(existente)).thenReturn(existente);

        AtividadeComarca salva = service.atualizarAtividade(2L, inicio, inicio, entrada, List.of());

        assertSame(existente, salva);
        assertEquals(esperado(caso), salva.getDescricaoAtividades());
        assertEquals(inicio, salva.getDataEncerramento());
        assertEquals(List.of("/uploads/comarcas/evidencia-sintetica.png"), salva.getFotosEvidencia());
        verify(atividades).save(existente);
    }

    static Stream<Arguments> vazios() {
        return Stream.of(Arguments.of((String) null), Arguments.of(""), Arguments.of(" \n\t "),
                Arguments.of("<p><br></p>"), Arguments.of("<script>alert(1)</script>"),
                Arguments.of("<!-- comentário -->"));
    }

    @ParameterizedTest
    @MethodSource("vazios")
    void rejeitaDescricaoSemTextoAntesDeAcessarRepositorios(String entrada) {
        assertThrows(DescricaoAtividadeObrigatoriaException.class,
                () -> service.registrarAtividade(1L, inicio, null, entrada, List.of()));
        assertThrows(DescricaoAtividadeObrigatoriaException.class,
                () -> service.atualizarAtividade(2L, inicio, null, entrada, List.of()));
        verifyNoInteractions(atividades, comarcas);
    }
}
