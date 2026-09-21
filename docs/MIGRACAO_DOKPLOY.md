# Migracao do RC Operations Hub para Dokploy

## Objetivo e estado seguro

A migracao deve ocorrer sem substituir imediatamente o ambiente atual. O servico
`poprc.service`, o PostgreSQL e o backup diario da VPS permanecem ativos ate o
ambiente Dokploy concluir restauracao, piloto e aceite.

Arquivos da implantacao:

- `compose.dokploy.yml`: PostgreSQL 16, backend, frontend e volumes persistentes;
- `deploy/docker/backend.Dockerfile`: build e runtime Java 25 sem usuario root;
- `deploy/docker/frontend.Dockerfile`: build Node 22 e frontend Nginx;
- `deploy/docker/nginx.conf`: SPA, proxy interno, cache e compressao;
- `deploy/env/dokploy.env.example`: inventario de variaveis sem credenciais reais.

## Fase 0 - inventario e separacao dos projetos

Antes de alterar qualquer servico, registrar onde cada carga esta executando. O
POP RC deve possuir projeto proprio no Dokploy, separado dos projetos de Zabbix,
Grafana e GLPI. Nao reutilizar bancos, volumes, dominios ou variaveis desses
projetos.

Na VPS legada, coletar sem alterar nada:

```bash
hostnamectl --static
ip -br address
sudo ss -ltnp | grep -E ':(80|443|5432|8085)\b' || true
sudo systemctl status poprc.service nginx postgresql --no-pager
sudo systemctl list-timers poprc-backup.timer --no-pager
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}\t{{.Status}}'
docker network ls
docker volume ls
```

No Dokploy, registrar para cada projeto existente: projeto, ambiente, servicos,
dominios, banco, volumes e servidor de destino. Confirmar especialmente se o
Dokploy e o legado compartilham a mesma VPS. Se compartilharem, nao alterar as
portas 80/443, o Nginx ou o Traefik durante a homologacao.

Inventario confirmado em 21/09/2026:

- o Nginx do host ocupa `80/443` e entrega o POP RC legado;
- o backend legado permanece restrito a `127.0.0.1:8085`;
- o Traefik do Dokploy ocupa `8080/8443`;
- Dokploy, Grafana, Zabbix e GLPI continuam em seus projetos atuais;
- a porta `8090` esta livre e foi reservada para o frontend de homologacao;
- o certificado do IP `186.196.9.178` expira em 27/09/2026 e deve ser renovado
  antes do deploy de homologacao.

## Fase 1 - preparar e publicar a estrutura

1. Confirmar que o backup diario atual continua com `Result=success`.
2. Executar `scripts/test-pilot.ps1` e `npm run build` antes do checkpoint.
3. Publicar a estrutura Docker sem desligar o ambiente existente.
   O job `Containers Dokploy` do CI deve validar o Compose e construir as duas
   imagens antes da criacao do ambiente de homologacao.
4. No Dokploy, usar o tipo `Docker Compose`, branch `main` e caminho
   `./compose.dokploy.yml`.
5. Ativar `Isolated Deployments`. O Compose mantem uma rede privada propria entre
   frontend e backend, e o Dokploy adiciona a rede isolada usada pelo Traefik.
6. Conferir no `Preview Compose` que nenhuma rede, volume, porta ou nome de
   container pertencente a Zabbix, Grafana ou GLPI foi incorporado.

## Fase 2 - criar a homologacao no Dokploy

1. Criar no Dokploy um projeto exclusivo `RC Operations Hub` e um ambiente
   `homologacao`. Nao usar os projetos de observabilidade nem o projeto do GLPI.
2. Criar um servico `Docker Compose` chamado `poprc-homologacao`, usando a
   branch `main` e o caminho `./compose.dokploy.yml`.
3. Ativar `Isolated Deployments`. O PostgreSQL 16 fica dentro deste Compose,
   sem `ports`, e nao deve ser substituido por banco de outro projeto.
4. Configurar as variaveis a partir de `deploy/env/dokploy.env.example`.
5. Usar `DB_NAME=poprc_homolog`, usuario exclusivo e senha aleatoria forte.
6. Definir `APP_PUBLIC_URL` com a URL HTTPS de homologacao, sem barra no final.
7. Manter a aba Domains do Dokploy sem dominio para este Compose. O frontend e
   publicado apenas em `127.0.0.1:8090`; o Nginx do host sera responsavel pelo
   dominio e pelo certificado HTTPS de homologacao.
8. Conferir o `Preview Compose`: somente o frontend publica
   `127.0.0.1:8090->8080`; nao existe porta publicada para `database` nem para
   `backend`.
9. Fazer o primeiro deploy e conferir os health checks dos tres containers.

Nao adicionar Traefik ao arquivo Compose manualmente. Depois do deploy, criar um
`server` separado no Nginx do host para o dominio de homologacao, com
`proxy_pass http://127.0.0.1:8090`. Nao substituir o bloco `poprc` existente.
Nenhuma credencial real deve entrar no Git.

## Fase 3 - restaurar uma copia dos dados

Gerar um backup final no ambiente antigo:

```bash
cd /opt/poprc/current
sudo env POPRC_ENV_FILE=/etc/poprc/poprc.env bash deploy/scripts/backup-vps.sh
sudo ls -lht /var/backups/poprc | head
```

O pacote possui `database.dump`, `uploads/`, `manifest.txt` e `SHA256SUMS`.
Extraia uma copia em diretorio temporario e valide `sha256sum --check
SHA256SUMS`. Restaure `database.dump` somente no banco `poprc_homolog`, dentro
do servico `database`, nunca diretamente sobre o banco legado. Copie o conteudo
de `uploads/` para o volume nomeado `poprc_uploads` com o backend parado ou por
um container auxiliar controlado.

Depois da copia:

1. iniciar o servico `database` e depois o backend;
2. aguardar o backend ficar healthy e o Flyway validar as migracoes;
3. iniciar o frontend;
4. abrir fotos, evidencias e PDFs antigos;
5. comparar materiais, saldos, faltas, ORs e valor total do estoque.

## Fase 4 - backups no Dokploy

Criar primeiro um `Compose Job` direcionado ao servico `database`. O horario
deve ser conferido com o fuso do servidor antes de salvar. Comando:

```bash
sh -ec 'stamp=$(date -u +%Y%m%dT%H%M%SZ); tmp=/backups/.poprc-$stamp.dump.tmp; PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-privileges -f "$tmp"; mv "$tmp" "/backups/poprc-$stamp.dump"; find /backups -type f -name "poprc-*.dump" -mtime +14 -delete'
```

Executar o job manualmente e confirmar que um arquivo nao vazio apareceu no
volume `poprc_db_backups`. Depois configurar os Volume Backups para o mesmo
destino S3 privado:

1. `poprc_db_backups`, depois do horario do dump logico;
2. `poprc_uploads`, desligando o backend durante a copia;
3. opcionalmente `poprc_postgres`, desligando `database` e backend durante a
   copia fisica.

Executar o botao Test nas configuracoes. Restaurar banco e uploads em recursos de
homologacao descartaveis antes de considerar a rotina aprovada. O backup geral do
Dokploy protege a plataforma, nao substitui os backups da aplicacao. Antes
do corte definitivo, gerar tambem o pacote logico pelo ambiente legado; ele e a
fonte portavel de restauracao caso seja necessario abandonar os volumes Docker.

## Fase 5 - homologacao e piloto

1. Verificar `/actuator/health` e `/healthz`.
2. Testar login e logout com cada perfil.
3. Abrir arquivos persistidos antes da migracao.
4. Executar uma importacao controlada de planilha.
5. Percorrer projeto, OS, equipe, OR, retirada, tecnico, devolucao, auditoria e
   encerramento com registros claramente identificados como homologacao.
6. Conferir logs, assinaturas, PDFs, estoque e ausencia de reservas residuais.
7. Executar `scripts/test-pilot.ps1` novamente no checkpoint que sera promovido.

## Fase 6 - corte e rollback

1. Definir uma janela sem movimentacoes operacionais.
2. Gerar o ultimo backup do banco e uploads antigos.
3. Restaurar a fotografia final no ambiente de producao do Dokploy.
4. Executar smoke test e comparar os totais antes de trocar o DNS.
5. Alterar DNS e observar logs, health checks e recursos.
6. Manter o ambiente antigo parado, mas preservado, durante o periodo de aceite.

Rollback: retirar o novo destino do DNS, reativar o ambiente anterior e bloquear
gravacoes no ambiente rejeitado. Nunca manter os dois ambientes aceitando
movimentacoes, pois os bancos divergiriam.

## Fase 7 - performance e continuidade

Os limites iniciais sao 1 CPU/1 GB para o backend e 0,5 CPU/128 MB para o
frontend. Eles sao pontos de partida, nao capacidade homologada. Durante o piloto,
registrar CPU, memoria, rede, tempo de resposta e conexoes do PostgreSQL. Ajustar
heap, limites e pool somente com essas medidas.

Depois do aceite da infraestrutura, retomar as funcionalidades do cronograma de
produto. Zoho, permissoes dinamicas e impressao fisica definitiva permanecem nas
fases posteriores ja combinadas.
