package com.poprc.demo.service;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class EstoqueIntencaoService {
    public static final String HEADER = "Idempotency-Key";

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final ObjectMapper json;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String executar(long funcionarioId, UUID chave, String operacao, Object pedido, Supplier<?> registrar) {
        String conteudo = json.writeValueAsString(pedido);
        // A restrição única faz a concorrente aguardar a transação dona da chave.
        // Se a primeira transação reverter, a concorrente pode ocupar a chave.
        int inseridos = jdbc.update("""
                insert into estoque_intencoes (funcionario_id, chave, operacao, pedido)
                values (?, ?, ?, cast(? as jsonb))
                on conflict (funcionario_id, chave) do nothing
                """, funcionarioId, chave, operacao, conteudo);
        if (inseridos == 0) {
            return jdbc.queryForObject("""
                    select operacao = ? and pedido = cast(? as jsonb) as mesmo_pedido, resultado
                    from estoque_intencoes where funcionario_id = ? and chave = ?
                    """, (linha, numero) -> {
                        if (!linha.getBoolean("mesmo_pedido")) {
                            throw new IllegalStateException("Chave já utilizada com conteúdo diferente.");
                        }
                        return linha.getString("resultado");
                    }, operacao, conteudo, funcionarioId, chave);
        }

        Object resposta = registrar.get();
        entityManager.flush();
        String resultado = json.writeValueAsString(resposta);
        jdbc.update("update estoque_intencoes set resultado = ? where funcionario_id = ? and chave = ?",
                resultado, funcionarioId, chave);
        return resultado;
    }
}
