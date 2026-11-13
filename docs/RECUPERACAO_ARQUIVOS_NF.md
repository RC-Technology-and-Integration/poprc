# Arquivos de nota fiscal: transação, retenção e recuperação

## Ciclo e limite

`analisar` recebe Base64 (XML/PDF, até 10 MB), calcula SHA-256 e lê o documento em memória, sem arquivar. `confirmar` exige usuário, hash correspondente à prévia, documento ainda não importado, seleção, validações básicas de todos os itens, funcionário e depósito válidos. Mantém o lock transacional de estoque e a restrição SQL única de hash.

O armazenamento exige uma transação ativa. Cria exclusivamente `APP_UPLOAD_DIR/.notas-fiscais/<sha256>-<sufixo-exclusivo>/`, grava `arquivo.part` com CREATE_NEW e renomeia para `original.xml` ou `original.pdf` no mesmo diretório com ATOMIC_MOVE. Falha de rename não tem fallback para cópia ou substituição. Ambos os nomes são privados. O arquivo completo ainda **não representa uma importação concluída**.

O serviço persiste o caminho exclusivo, os itens, os créditos de estoque, custos e históricos na mesma transação SQL. A resposta 201 só sai depois do commit do interceptor Spring. Download exige uma importação persistida por ID e as permissões existentes do estoque (ADMIN/ESTOQUE em modo protegido). `/uploads/.notas-fiscais/**` e `/uploads/notas-fiscais/**` são bloqueados, incluindo a raiz legada. O resolver estático também recusa variações de caixa no Windows. Arquivos anteriores permanecem acessíveis pela API de download autorizada; nenhum arquivo antigo é movido ou removido.

No `afterCompletion(ROLLED_BACK)`, a compensação remove apenas os dois nomes fixos e o diretório **criados exclusivamente por esta chamada**. Não usa caminhos de registros antigos, não reutiliza arquivo com o mesmo hash e não percorre diretórios. Nenhuma outra tentativa pode obter este caminho pelo fluxo normal antes do commit. Falha de compensação é registrada em ERROR e deixa o restante privado. `COMMITTED` preserva o arquivo. `UNKNOWN` retém tudo e registra ERROR, sem inferir rollback por falta de resposta.

Não há transação distribuída entre PostgreSQL e filesystem. ATOMIC_MOVE é somente rename no filesystem, não commit conjunto ou garantia contra perda de energia. Não há fsync/garantia de persistência física após falha de hardware. Sem suporte a ATOMIC_MOVE, a importação falha e reverte. A configuração futura de servidor/proxy deve manter estas pastas fora de qualquer alias estático externo; essa infraestrutura não é configurada nem certificada pelos testes locais.

## Estados após interrupção

| Observação | Classificação e ação |
|---|---|
| `arquivo.part` | Temporário; pode estar incompleto. Reter privado; nunca servir, concluir importação ou renomear automaticamente |
| `original.*` com vínculo confirmado no banco primário | Documento referenciado. Preservar mesmo que o cliente não tenha recebido a resposta; recuperar pelo ID |
| Arquivo sem referência após término comprovado da transação | Candidato a resíduo. A ausência momentânea de linha não basta enquanto houver tentativa ativa/commit incerto |
| Transação ativa, commit desconhecido, banco indisponível, leitura em réplica atrasada ou evidências contraditórias | Resultado incerto. Reter; não excluir e não repetir cegamente a operação |
| Rollback confirmado com callback executado | Só a tentativa atual é compensada. Falha do callback deixa resíduo privado e log para análise |

Uma morte abrupta pode acontecer antes da instalação do callback, durante a escrita, depois do rename, antes do commit, durante o commit ou depois dele. Não executa `finally`/callbacks. Diretório vazio, `.part` ou `original.*` podem permanecer. Não existe coleta automática, TTL ou exclusão no reinício/retry. A retenção é sem expiração automática até decisão explícita do responsável; prazo operacional e responsável precisam ser definidos antes de adotar o procedimento em um ambiente real.

## Procedimento seguro (sem execução automática)

1. Identificar o ambiente e a raiz efetiva sem alterar dados. Preservar backup conjunto do banco e arquivos, logs, horário com fuso, versão da aplicação, caminho canônico, tamanho e SHA-256 do candidato. Restringir a leitura local às pessoas autorizadas. Não copiar para diretório público. Não aplicar este procedimento a dados reais sem autorização específica.
2. Correlacionar o prefixo SHA-256 e o diretório exclusivo com o log da tentativa e a requisição. A aplicação registra compensação incompleta e status UNKNOWN; uma queda abrupta pode não produzir esse log. Não tratar o prefixo como prova de conteúdo: calcular o hash real. `.part` pode não ter o hash anunciado no diretório.
3. Confirmar no **primário**, por conexão independente, o resultado e que não há tentativa ativa ou preparada. Se isso não puder ser comprovado, manter classificação incerta. Fazer consulta parametrizada por caminho e hash, por exemplo:

   ```sql
   SELECT id, arquivo_path, hash_sha256, data_importacao, funcionario_id, local_estoque_id,
          itens_processados, valor_total
     FROM importacoes_notas_fiscais
    WHERE arquivo_path = :caminho OR hash_sha256 = :sha256;
   ```

   Examinar também itens e movimentos vinculados à intenção observada, snapshots e logs. Comparar caminhos canônicos de **todos** os registros potencialmente relacionados: caixa, separadores, links e aliases podem esconder compartilhamento, sobretudo no Windows. Não excluir apenas porque a comparação textual acima deu zero. Se qualquer referência ou compartilhamento existir, preservar o arquivo. Em resultado desconhecido, a consulta deve ser repetida depois de esclarecer o término da transação.
4. Com vínculo confirmado e arquivo íntegro, recuperar o resultado pelo histórico/detalhe e download por ID, verificar itens/saldos/reservas/históricos e encerrar o retry. A proteção existente por hash responde 400 “já foi importada” ao reenvio; ela não devolve automaticamente o resultado anterior e não usa a intenção R-02 das entradas manuais. Não trocar o conteúdo/hash para contornar essa proteção.
5. Sem vínculo, com rollback/término comprovado e nenhuma referência/compartilhamento, pode-se reenviar a **mesma** NF após a conciliação. Será criada outra tentativa exclusiva; os créditos ocorrem uma vez no commit. O resíduo antigo continua retido e privado, com hashes preservados. O sistema não promove nem reaproveita `.part`/`original.*` incertos.
6. Qualquer recuperação excepcional de arquivo referenciado ausente/corrompido deve partir de backup íntegro ou fonte fiscal validada e autorização própria. Não criar NF SQL, movimentos ou vínculos manualmente para “resolver” resíduo. Se o caso exigir descarte, obter autorização explícita sobre o candidato exato, guardar a evidência e reconferir referências/atividade imediatamente antes. Esta entrega não fornece varredura destrutiva nem ferramenta de remoção.

Arquivos legados `notas-fiscais/<hash>.*` podem ser compartilhados ou resíduos anteriores, sem identidade exclusiva da tentativa. A compensação nova nunca atua neles. Não existe reparo retroativo implícito.

## Repetir a validação descartável

Executar Java 25 e PostgreSQL 16 em loopback, sem `application-local.properties`. Os testes Java `NotaFiscalArquivoStorageTest`, `NotaFiscalInterrupcaoTest`, `ImportacaoNotaFiscalEstoqueServiceTest` e `EntradaFracionadoIntegrationTest` cobrem armazenamento, interrupções de JVM e regressões do serviço. As JVMs filhas usam `Runtime.halt` e um banco UUID próprio, sem dados reais. Os testes completos preservam as condições dos gates existentes.

`python src/test/python/nf_arquivo_http.py --jar <jar> --out <diretorio-novo>` executa HTTP TCP com login/SESSION/CSRF/reauth, falhas SQL imediatas e adiadas, erro de filesystem, resposta perdida, concorrência, morte do backend e retry após reinício. Requer `JAVA_HOME`, `NF_PGBIN`, `NF_PGPORT`, `NF_PGDATA`, `TEST_DB_USERNAME` e `TEST_DB_PASSWORD` apontando para o cluster descartável. Confere diretório/versão do servidor antes de semear, cria seu próprio banco UUID e o remove ao encerrar. `--antes` demonstra o resíduo e acesso estático na base vulnerável; deve ser usado somente com a base em recursos sintéticos. Arquivos sintéticos/evidências são retidos para inspeção no diretório de saída.
