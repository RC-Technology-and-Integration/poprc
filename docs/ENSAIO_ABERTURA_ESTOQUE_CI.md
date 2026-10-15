# Regressão da abertura de estoque

`EnsaioAberturaEstoqueTest` adapta os 11 cenários do ensaio local de 14/10/2026
para a suíte. Usa apenas dados sintéticos: 78 produtos, 73 por unidade inteira,
5 por metro, 64 saldos positivos, 14 zerados e dois custos zero. Quantidades,
nomes, preços e hash não representam a planilha operacional. O total esperado
é calculado a partir dessa fixture, com arredondamento por produto.

O teste cadastra saldos zerados e sincroniza integralmente para João Pessoa.
Confere custos, reservas, metragem, igualdade global/local e Natal zerado;
repetição do hash, rollback no último item, cobertura incompleta, duplicidade,
negativo, fração em unidade inteira, destino inexistente, 0,125 m com custo
1,2345, concorrência no serviço e consulta de resultado persistido sem reenvio.

Outro hash continua permitindo nova sincronização em estoque carregado. Essa
caracterização não oferece garantia de abertura única. A concorrência cobre
duas chamadas transacionais ao serviço e persistência final; não comprova o
tratamento HTTP da exceção concorrente. A consulta após descartar a resposta
não simula uma interrupção real de rede.

## Isolamento e execução

O executor recebe `TEST_DB_URL`, `TEST_DB_USERNAME` e `TEST_DB_PASSWORD`.
A URL deve apontar para loopback e banco `_test`, sem parâmetros ou credenciais
embutidas; o usuário precisa poder criar bancos no servidor descartável.
Não usa porta fixa nem lê `application-local.properties`.

`BancoAberturaDescartavel` cria um banco novo, com UUID e `TEMPLATE template0`.
Nunca limpa o banco fornecido em `TEST_DB_URL`. Antes de cada limpeza, confere
propriedade, URL JDBC efetiva, banco, schema e usuário. O JUnit registra a
remoção do recurso mesmo se a inicialização Spring falhar. O contexto fecha
ao fim da classe; o banco criado é removido e a ausência dele é conferida.
Uploads usam diretório temporário do JUnit. Não executar contra servidores
operacionais. Uma interrupção externa forçada do processo pode exigir remoção
manual do recurso descartável identificado no log.

O workflow CI existente usa Java 25 e serviço PostgreSQL 16. Um passo explícito
executa a classe e exige XML Surefire com **11 testes e zero falhas, erros ou
ignorados**. O executor verifica as versões efetivas no GitHub Actions e falha
se elas divergirem. A classe também participa do `verify` completo, sem condição
que a ignore quando faltar banco. Os relatórios do passo direcionado são
preservados antes do `verify`; `revisoes.txt` registra HEAD do PR e SHA do checkout
efetivamente testado, que pode ser o merge temporário do GitHub.

O ensaio anterior passou localmente com Java 26/PostgreSQL 18. Ele não comprova
esta candidata, Java 25/PostgreSQL 16, futura release, carga real ou T0. A fonte
de 14/10/2026 permanece uma referência histórica aprovada, sem atualização
automática do saldo ou novo corte operacional.
