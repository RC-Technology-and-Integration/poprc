# Aceite pos-corte do POP RC no Dokploy - 2026-09-23

## Estado conhecido

- O Nginx da VPS atende o POP RC no Dokploy em `https://186.196.9.178`.
- O operador informou que `poprc.service` esta inativo e os tres containers do
  Compose `rc-operations-hub-poprchomologacao-wjonap` estavam saudaveis apos o
  corte. O acesso pela interface foi testado pelo operador sem falha aparente.
- A verificacao publica desta data retornou HTTP 200, `ok` em `/healthz` e
  `status: UP` em `/actuator/health`.
- O operador informou `Result=success` e `ExecMainStatus=0` para a exportacao
  manual apos gerar um dump logico novo. O SHA-256 do pacote na VPS e no
  `rcls` coincidiu: `41a37f92e1c91b3a7a11932bec807762f0b4e5caccf3dca82afb5b73a8b0b6e1`.
- O timer de exportacao na VPS esta ativo e a ultima execucao reportou sucesso.
  O pacote `poprc-dokploy-20260923T125450Z.tar.gz` existe na VPS segundo a
  listagem enviada pelo operador.
- O timer de pull no `rcls` esta habilitado. A execucao manual reportou
  sucesso, e o operador confirmou no destino local o pacote das 12:54 UTC
  (46K) e seu marcador `.verified`.
- O pacote das 12:54 UTC foi restaurado com sucesso no banco temporario
  `poprc_restore_20260923173416` no container PostgreSQL do POP RC. Contagens:
  funcionarios 3, contratos 1, projetos 8, OS 8, OR 12, materiais 62 e
  migracoes Flyway 38. O arquivo de uploads foi extraido sem erro e continha
  zero arquivos; portanto, este pacote ainda nao prova a recuperacao de um
  upload real. O operador confirmou a remocao do banco temporario e da pasta
  `restore-run-5J2F1L` apos a verificacao.

## Regressao executada localmente

- `scripts/test-pilot.ps1`: 115 testes, 0 falhas, em banco isolado
  `poprc_pilot_test` (PostgreSQL 18.4). O banco temporario foi removido.
- Testes de utilidades e seguranca do frontend: 24 testes, 0 falhas.
- O banco do Dokploy usa PostgreSQL 16; os testes locais nao substituem o
  piloto na versao implantada nem a verificacao dos dados restaurados.

## Homologacao de perfis no site implantado

- Supervisor tecnico (`chat-sup`): login e logout confirmados. A Central operacional,
  Contratos, Projetos, Ordens de Servico, Gestao de Obras, Equipes,
  Notificacoes e Area do Tecnico abriram sem erro de permissao.
- A lista de Equipes foi apresentada sem controles de criacao ou edicao.
  O menu nao exibiu Estoque, Auditoria nem Financeiro. O acesso direto as
  rotas dessas areas redirecionou para a Central operacional.
- Em viewport de 390 x 844, Central operacional, menu e Ordens de Servico
  permaneceram utilizaveis. Um rotulo de etapa na pagina de OS apareceu
  truncado; avaliar no fechamento de usabilidade.
- A navegacao direta para uma URL da API foi bloqueada pelo navegador de
  teste, antes de obter resposta HTTP. A autorizacao da API nao foi validada
  ao vivo nesta rodada; os testes automatizados locais continuam a cobri-la.
- A conta de Supervisor ainda nao esta atribuida a uma comarca livre com
  responsavel; por isso o botao `Nova OS` apareceu desabilitado. O piloto
  de criacao de OS depende dessa atribuicao.
- Tecnico (`chat-tec`): login e logout confirmados. Area do Tecnico, Ordens de Servico
  e Gestao de Obras abriram. O menu exibiu somente essas tres areas; a pagina
  de OS nao exibiu `Nova OS`. O acesso direto a Central, Contratos, Projetos,
  Equipes, Estoque, Auditoria, Financeiro e Notificacoes redirecionou para
  `/tecnico`.
- Em viewport de 390 x 844, o Tecnico conseguiu visualizar alertas, jornada
  e lista de OS sem sobreposicao aparente. A conta nao tem OS atribuida;
  execucao de campo, evidencias e devolucao dependem do piloto operacional.
  Nenhuma jornada foi registrada neste teste de permissao.
- Estoque (`chat-est`): login e logout confirmados. O menu mostrou apenas Equipes e Estoque.
  Equipes carregou em modo leitura, sem controles de criacao ou edicao.
  A navegacao direta para Contratos, Projetos, OS, Obras, Auditoria e
  Financeiro redirecionou para `/estoque`. A simulacao de retirada abriu
  sem efetuar movimentacao.
- Defeito bloqueante para o aceite do Estoque na versao implantada: a pagina
  exibiu `Acesso nao permitido para este perfil` apos carregar os 62 materiais
  e o valor de R$ 42.341,47. A tela mostrou 0 ORs e historico vazio, embora
  o dump anterior contivesse 12 ORs. O carregamento sequencial pede
  `/api/contratos`, negado ao perfil Estoque, e interrompe as consultas
  seguintes. Nao concluir que as ORs ou movimentacoes foram perdidas.
- Correcao feita somente no codigo local: nova consulta restrita de opcoes de
  contrato para Estoque (id, cliente, numero e arquivado); a lista completa
  de Contratos continua bloqueada. A pagina usa essa consulta para o perfil
  Estoque e tem teste de autorizacao/ausencia de dados financeiros. O build
  do frontend e 24 testes JavaScript passaram. Os 81 cenarios da suite Java
  de autorizacao passaram em banco local temporario, removido ao final.
  Nao houve deploy; revalidar a tela, as 12 ORs e o historico apos a
  implantacao controlada.
- Auditor (`chat-aud`): login e logout confirmados. O menu exibiu apenas Gestao de Obras
  e Retirada e Devolucao. Auditoria carregou as oito OS historicas e mostrou
  conciliacao, status As-Built, ciclo da OR e rastreabilidade. Gestao de Obras
  carregou oito obras. Os detalhes de OS e OR abriram em modais, e o PDF da OR
  historica abriu com duas paginas. Nenhuma quantidade foi ajustada e nenhuma
  auditoria foi homologada.
- Acesso direto a Central, Contratos, Projetos, OS, Equipes, Estoque,
  Financeiro e Area do Tecnico redirecionou para `/auditoria/tecnica`.
  Em viewport de 390 x 844, Auditoria, menu e Gestao de Obras permaneceram
  utilizaveis. Textos longos em seletores e titulos de pendencias aparecem
  truncados no celular; revisar na etapa de usabilidade.

## Pendencias para aceite operacional

1. A correcao local do Estoque requer revisao, implantacao e nova verificacao
   no site. As acoes de homologacao do Auditor
   permanecem para o piloto operacional, sem dados de campo concluidos.
2. Executar no Dokploy uma OS identificada como homologacao: materiais,
   equipe, OR, retirada, tecnico, devolucao, auditoria e encerramento.
   Conferir saldos, reservas, assinaturas, arquivos e PDFs antes e depois.
3. Confirmar no ciclo automatico seguinte que o dump logico, o pacote de
   banco/uploads e a copia no servidor local foram gerados com data recente.
   Quando houver um upload de homologacao, testar tambem sua recuperacao a
   partir de um pacote posterior.
4. Antes do uso real, definir e executar a limpeza controlada dos registros
   de teste, preservando somente os cadastros acordados. Importar e conciliar
   o estoque real depois do backup.
5. Observar erros, recursos e backups por 7 a 14 dias. So entao decidir a
   desativacao definitiva do legado, com inventario de dependencias.

## Limites do corte

- Nao remover `/opt/poprc`, `/etc/poprc`, `/var/lib/poprc/uploads`,
  `/var/backups/poprc`, o banco antigo nem as unidades de servico durante o
  periodo de rollback.
- Nao desligar Nginx ou Certbot: o acesso atual em 443 ainda depende deles.
- Nao alterar os projetos Dokploy, Zabbix, Grafana ou GLPI nesta homologacao.
- Depois de novas gravacoes no Dokploy, reativar apenas o backend legado nao
  recupera os dados recentes; rollback passa a exigir migracao de dados.

## Verificacao pendente nos servidores

O acesso SSH automatizado deste ambiente foi recusado por falta de
autenticacao. A VPS ja informou sucesso dos comandos abaixo; repetir depois
do proximo ciclo automatico, sem exibir variaveis ou senhas:

```bash
sudo systemctl list-timers poprc-dokploy-export.timer --no-pager
sudo systemctl show poprc-dokploy-export.service -p Result -p ExecMainStatus
sudo ls -lht /var/backups/poprc-dokploy-export | head -n 3
```

O `rcls` ja informou timer habilitado, pull manual concluido e pacote com
marcador `.verified` presente. O teste de restauracao do pacote foi feito na
VPS em banco isolado, sem modificar o banco ativo do Dokploy nem o proxy Zabbix.

Para referencia, os arquivos confirmados foram:

```bash
/srv/backups/poprc/poprc-dokploy-20260923T125450Z.tar.gz
/srv/backups/poprc/poprc-dokploy-20260923T125450Z.tar.gz.verified
```
