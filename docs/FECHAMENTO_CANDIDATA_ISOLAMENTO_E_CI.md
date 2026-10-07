# Fechamento das validações pendentes da candidata

## Partida local: causa e contenção

`application.properties` importa `optional:file:./application-local.properties`. O arquivo local, ignorado pelo Git, existe na raiz do checkout e define `spring.datasource.url` diretamente para o banco habitual `poprc_local`. Na primeira partida, o diretório corrente era o checkout. O arquivo importado prevaleceu sobre o valor de `spring.datasource.url=${DB_URL:...}` do arquivo base: `DB_URL` só fornece valor à expressão do arquivo base, não sobrescreve uma propriedade direta do importado. A partida não tinha o perfil `test`; portanto, `DevDatabaseSafetyInitializer` não aplicou a regra de nome terminado em `_test`. O log dessa tentativa mostrou o destino `poprc_local` e parou na validação Flyway da V1. Não houve migração da V39 nessa partida.

Para uma próxima execução local, **não partir do checkout**. Usar um diretório de trabalho temporário exclusivo, vazio e sem `application-local.properties`, após confirmar esse fato. Fixar `--spring.profiles.active=test`, `--spring.datasource.url=jdbc:postgresql://127.0.0.1:<porta-livre>/<banco-exclusivo>_test` e `--spring.datasource.username=<usuario-de-teste>` diretamente na linha de partida; fornecer a senha somente por `TEST_DB_PASSWORD` no ambiente do processo, sem escrevê-la em logs ou argumentos. Fixar `-Dapp.upload.dir=<diretorio-temporario>/uploads`, `--server.address=127.0.0.1`, uma porta HTTP livre, `--app.security.enabled=true` e `--app.security.dev-login-enabled=false` para o percurso autenticado. O diretório de uploads deve ser criado dentro do mesmo espaço descartável. Não usar `--spring.config.import=''` como garantia: a declaração de importação do arquivo base ainda pode carregar o arquivo local.

Antes de criar dados ou iniciar Java, exigir `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`, diretório de dados esperado e caminho de uploads exclusivos. Recusar variável ausente, URL fora de loopback, banco sem sufixo `_test`, porta habitual, diretório sob o checkout ou diretório de trabalho com `application-local.properties`. Consultar **somente o destino explícito de teste** com `psql` e conferir `current_database()`, `current_setting('data_directory')` e `current_setting('server_version_num')` contra os valores esperados do runtime descartável. Se qualquer comparação falhar, não iniciar a aplicação. Não consultar `poprc_local`. `application-test.properties` agora não fornece URL, usuário ou senha padrão; a ausência desses valores deixa de conduzir a um banco presumido. A regra de sufixo do inicializador continua sendo uma defesa adicional, não prova de isolamento por si só.

Exemplo de pré-verificação, após preparar um runtime descartável e preencher as variáveis **somente no processo de teste** (não reutilizar o serviço da porta 5432):

```powershell
if (-not $env:TEST_DB_URL -or -not $env:TEST_DB_USERNAME -or -not $env:TEST_DB_PASSWORD) { throw 'Configuração de teste incompleta' }
$destino = [regex]::Match($env:TEST_DB_URL, '^jdbc:postgresql://127\.0\.0\.1:(\d+)/([A-Za-z0-9_]+_test)$')
if (-not $destino.Success) { throw 'Destino de teste inválido' }
if ($destino.Groups[1].Value -eq '5432') { throw 'Porta habitual recusada' }
if (Test-Path -LiteralPath (Join-Path $diretorioTemporario 'application-local.properties')) { throw 'Configuração local presente no diretório de partida' }
# Com PGPASSWORD definido só para o processo de teste e psql do runtime descartável:
& $psql -h 127.0.0.1 -p $destino.Groups[1].Value -U $env:TEST_DB_USERNAME -d $destino.Groups[2].Value -Atc 'select current_database(), current_setting(''data_directory''), current_setting(''server_version_num'')'
# Compare a saída com o banco, data_directory e versão esperados antes de iniciar Java.
# Só após a comparação, execute Java com Push-Location $diretorioTemporario,
# -Dapp.upload.dir=<temporario>/uploads, --spring.profiles.active=test,
# --spring.datasource.url=$env:TEST_DB_URL e --spring.datasource.username=$env:TEST_DB_USERNAME.
```

## Checksum da V1

O histórico Git da V1 contém a versão inicial (`4f807af`, 14/07/2026) e a alteração `66444e4` (02/09/2026), que removeu `SET transaction_timeout = 0;` para compatibilidade com PostgreSQL 16. O checksum Flyway calculado da primeira versão é `-591636771`; o da versão atual é `377900245`. Eles coincidem, respectivamente, com `Applied to database` e `Resolved locally` no erro da primeira partida. A origem **da diferença entre os arquivos** está identificada; sem acessar o banco habitual, não há evidência para dizer quando ou por qual procedimento ele recebeu a V1 antiga. A V1 não tem alteração local não commitada. Não executar `flyway repair`, não editar a migration e não reconciliar histórico nesta preparação.

## PostgreSQL 16 no CI isolado

O workflow atual usa `pull_request` e `push` em `main`. O job backend foi ajustado para o serviço `postgres:16`. Um passo separado executa `Postgres16MigrationCiTest` antes da suíte completa. Esse teste só habilita com `-Dpoprc.pg16.migration=true`, verifica `GITHUB_ACTIONS`, URL/usuário de teste e versão real 16, cria dois bancos de nome aleatório terminado em `_test` no serviço do runner e os remove ao final. No primeiro, Flyway executa V1–V39 desde vazio. No segundo, executa até V38, insere três comarcas sintéticas (homologação divergente, homologação conciliada e pendente), aplica V39 e verifica preservação dos campos, ausência de justificativas e histórico inventados, e identificação da homologação divergente legada pelo estado mais ausência de histórico. A suíte backend existente roda em passo posterior, ainda sobre PostgreSQL 16. Não havia script/teste versionado que cobrisse explicitamente V38→V39; a suíte comum não substitui essa etapa.

Para disparar: depois de autorizar o versionamento, publicar **uma branch de validação** e abrir um pull request para `main`, sem merge. O evento `pull_request` executa o workflow nesse runner. Conferir o log do passo de migrações, o passo `Executar testes`, a versão 16 reportada e a limpeza dos bancos do teste. **CI não é implantação**. Antes de qualquer push, revisar os gatilhos de auto-deploy do repositório e do Dokploy; não usar `main` para disparar a validação. Commit, push, PR, merge e implantação exigem autorização posterior separada. O teste e o workflow preparados aqui ainda não foram executados no runner.

## Percursos visuais restantes, apenas em ambiente descartável

1. **Reabertura:** com frontend e backend reais apontando para o banco/uploads descartáveis já verificados, entrar como usuário fictício autorizado; na auditoria de uma comarca sintética homologada, clicar **Reabrir para ajuste**, aceitar o `window.confirm`, inserir a senha de teste na janela **Confirme sua identidade** e confirmar. Recarregar; verificar status `REABERTO_PARA_AJUSTE`, histórico da homologação preservado e edição protegida autorizada. Repetir a tentativa com identidade de teste sem perfil/vínculo e confirmar bloqueio sem gravação. A automação anterior não concluiu o controle de reautenticação; se persistir, a interação manual necessária é confirmar a janela do navegador e preencher/enviar a senha de teste no modal.

2. **Assinaturas:** no fluxo da comarca sintética, abrir **Documento Final - Encerramento e Aceite**; antes de salvar, conferir que *todo* o conteúdo e identidades representam teste. A interface preenche `empresa` e `cnpj` com dados reais embutidos e o PDF contém declarações formais; assim, **não registrar assinaturas nem concluir o documento com o formulário atual**. Para uma execução segura posterior, a cópia local descartável precisará apresentar empresa/CNPJ fictícios e uma marca inequívoca de teste no documento gerado, conferida no PDF antes de assinar. Então, com três identidades sintéticas, usar os botões **Assinar** de `TECNICO`, `GESTOR_RC` e `GERENTE_FORUM`; em cada modal preencher nome de teste, desenhar no canvas apenas `TESTE`/marca sem semelhança com assinatura real e confirmar. Recarregar e verificar no backend status `REGISTRADO`, três logs, hash e PDF arquivado; só depois seguir o encerramento permitido. Se a automação não operar o canvas, a interação manual é desenhar a marca de teste em cada modal e pressionar **Confirmar**. O percurso permanece pendente e não deve ser dado como aprovado por este roteiro.

Depois de qualquer execução descartável, parar processos criados nessa execução, excluir apenas os bancos/arquivos temporários confirmados por caminho e nome, e verificar que processos/porta e diretórios de teste foram removidos.
