# Retiradas V2 · recuperação de arquivos e espera do estoque

Este procedimento complementa o backup geral. Ele usa Python 3.12+ e clientes PostgreSQL compatíveis com o servidor. Os exemplos pressupõem credenciais no passfile de um operador autorizado e destino fora dos volumes da aplicação. Não há tarefa agendada, coletor ou remoção automática.

## Diagnóstico somente leitura

Use uma conta SQL com leitura das referências e visibilidade da atividade exigida. O papel e o acesso de leitura ao volume devem ser concedidos pelo responsável do ambiente. Os relatórios detalhados são privados porque contêm referências a ordens, autores e caminhos.

~~~powershell
python scripts/retiradas_v2_recuperacao.py --pg-bin $pgBin --host $pgHost --port $pgPort --usuario $leitor reconciliar --banco $banco --uploads $raizUploads
~~~

A ferramenta consulta o guard e os metadados antes/depois de ler os arquivos. A pasta V2 é .retiradas-v2 sob a raiz informada. A leitura online não sincroniza o filesystem com o snapshot do banco. Mesmo quando as duas consultas estão quietas, operações curtas ou outros escritores podem atravessar a janela.

| Resultado | Ação |
| --- | --- |
| Referenciado íntegro | Preservar. O SHA-256 corresponde à referência atual. |
| Referência ausente, divergente ou indeterminada | Repetir em janela drenada. Se confirmado, abrir incidente e procurar cópia de hash exato. Preservar bytes divergentes e trilha. Restaurar o caminho original a partir da cópia exata, sob autorização, e conferir acesso protegido. Se não houver cópia íntegra, registrar a perda e aguardar tratamento autorizado. |
| Sem referência observada | Registrar caminho, hash e janela e preservar. Reconciliar novamente após drenagem. Uma ausência de referência não autoriza descarte nem associação manual. |
| Temporário, entrada fora do padrão ou possível operação em andamento | Preservar e reavaliar com escritores drenados. Não inferir autoria, versão ou OR pela aparência do arquivo. |

Para observabilidade, exportar somente agregados do relatório. Usar contagens por estado, bytes sem referência e espaço disponível. Alertar após confirmar referência ausente/divergente em janela estável, ou crescimento continuado de sobras. Definir destinatário, frequência, espaço mínimo, retenção e prazos de recuperação com o responsável. Esses valores permanecem pendentes de definição operacional.

## Backup consistente

1. Obter autorização para a janela. Bloquear novos ingressos e drenar a aplicação inteira, jobs, workers, integrações, conexões em pool e escritores diretos do volume. Manter a barreira até a ferramenta concluir. Em degradação de storage, drenar de modo controlado e registrar quais solicitações ficaram sem resultado confirmado.
2. Conferir servidor, banco e raiz. Usar os clientes compatíveis com o servidor e saída inédita em armazenamento protegido. A ausência de sessões SQL é verificação adicional. Ela não substitui a barreira externa nem detecta um processo que escreve apenas em disco.
3. Executar o backup completo e guardar o manifesto com o dump, uploads e diagnóstico. O script compara as impressões de tabelas relevantes, o volume original e a cópia. Saída parcial sem manifesto de conclusão fica preservada para inspeção. Se houver referência danificada na origem, o backup conserva o estado diagnosticado e exige triagem antes de aceite.

~~~powershell
python scripts/retiradas_v2_recuperacao.py --pg-bin $pgBin --host $pgHost --port $pgPort --usuario $leitor backup --banco $banco --uploads $raizUploads --saida $pastaNova --escritores-drenados
~~~

O dump é de todo o banco. A conferência dirigida inclui autoria, ordens, comarcas, versões, referências, eventos, intenções, reservas, movimentos e saldos. Proteger o pacote como dado sensível, incluindo banco e arquivos. Guardar o manifesto em cópia protegida para verificar a integridade do par. Hash de manifesto ao lado do pacote não prova origem contra adulteração conjunta.

## Restauração e acesso protegido

1. Provisionar outro cluster PostgreSQL com initdb, diretório e porta inéditos em loopback. Nunca usar o cluster habitual. Criar, na pasta pai do diretório de dados dedicado, poprc-r01-isolado.json com chaves tipo, data e port. O tipo é poprc-r01-isolado, data é o caminho absoluto canônico do cluster dedicado e port é sua porta numérica. A ferramenta confronta o marcador com SHOW data_directory.
2. Rodar a restauração com saída inédita. A ferramenta cria um banco com nome aleatório terminado em _restore_test e não sobrescreve base ou uploads. O restore omite donos e privilégios da origem. Preparar a conta da aplicação exclusivamente nesse destino. Diferenças preservam artefatos para diagnóstico, sem limpeza automática.
3. Iniciar a mesma revisão do backend em 127.0.0.1, com datasource no banco recém-criado e APP_UPLOAD_DIR no caminho restaurado. Isolar integrações. Registrar a revisão, porta, destino de conexão e volume do processo. Conferir emissão, comprovante e devolução por HTTP com conta permitida, sessão anônima e técnico comprovadamente sem vínculo. O teste compara os bytes recebidos com o SHA-256 das referências. Segredos devem ser fornecidos pelo prompt ou por variáveis de processo em ensaio automatizado.
4. Aceitar o ensaio apenas com referências íntegras e provas HTTP de 200/403/401 nos três tipos. Para perda de resposta V2, consultar o estado e repetir a chave e o conteúdo originais. Em operação legada sem chave, conferir primeiro se houve efeito. Manter evidência de restauração, responsável e destino. Restauração de produção exige autorização e plano próprios.

~~~powershell
python scripts/retiradas_v2_recuperacao.py --pg-bin $pgBin --host 127.0.0.1 --port $portaIsolada --usuario $usuarioIsolado restaurar --pacote $pastaPar --dados-isolados $dadosIsolados --saida $pastaRestauradaNova
python scripts/retiradas_v2_verificar_http.py --url $urlLoopback --inventario $inventarioRestaurado --autorizado $cpfPermitido --negado $cpfSemVinculo
~~~

## Espera do guard e resposta da API

A propriedade app.estoque.guard.lock-timeout-ms aceita inteiro positivo. A configuração de ambiente é APP_ESTOQUE_GUARD_LOCK_TIMEOUT_MS. O padrão técnico local é 2000 ms e o ensaio dirigido usa 800 ms para provocar a recusa. Calibrar antes do uso operacional com três e cinco participantes em Java 25/PostgreSQL 16, preservando também um timeout preexistente mais restritivo. O limite cobre a aquisição de pg_advisory_xact_lock. Conexão, execução do titular, geração e leitura/escrita podem durar mais. A aplicação não interrompe I/O do titular por esse limite.

A recusa de aquisição identificada pelo banco aborta a transação e retorna HTTP 503 com codigo ESTOQUE_OCUPADO. Na tela V2, o operador aguarda e repete manualmente a mesma intenção. Para comprovante, usar o mesmo arquivo. Se a resposta se perder, manter o mesmo payload/chave. O legado mantém o procedimento manual e a consulta de estado, sem reenvio automático.

| Métrica Micrometer | Uso |
| --- | --- |
| poprc.estoque.guard.espera | Timer de aquisição, incluindo ajuste JDBC do limite. Rótulo resultado com adquirido, timeout ou erro. |
| poprc.estoque.guard.duracao | Timer por transação desde a primeira aquisição até a conclusão. Rótulo resultado com commit, rollback ou indefinido. Aproxima o tempo de retenção. |
| poprc.estoque.guard.rejeicoes | Contador de recusas de espera. |

Não incluir OR, material, autor, chave ou detalhes SQL em rótulos. A instrumentação usa o registro existente e não altera exposição de endpoints de gestão. Calibrar alertas pelo aumento de rejeições e pela duração do titular junto de disponibilidade do volume. Definir limites de operação após ensaio nominal e degradado e confirmar uma resposta recuperável por HTTP no V2 e no legado.
