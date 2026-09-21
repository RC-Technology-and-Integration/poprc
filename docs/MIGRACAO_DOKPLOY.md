# Migracao do RC Operations Hub para Dokploy

## Objetivo e estado seguro

A migracao deve ocorrer sem substituir imediatamente o ambiente atual. O servico
`poprc.service`, o PostgreSQL e o backup diario da VPS permanecem ativos ate o
ambiente Dokploy concluir restauracao, piloto e aceite.

Arquivos da implantacao:

- `compose.dokploy.yml`: backend, frontend e volume persistente de uploads;
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
portas 80/443, o Nginx ou o Traefik durante a homologacao; usar um dominio de
homologacao roteado pelo Dokploy e manter o dominio legado onde esta.

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
2. Criar um PostgreSQL 16 exclusivo, inicialmente vazio, chamado
   `poprc_homolog`.
3. Nao publicar a porta do PostgreSQL na internet.
4. Copiar o host, porta, usuario e nome mostrados em Internal Credentials.
5. Criar uma URL no formato:

```text
jdbc:postgresql://HOST_INTERNO:5432/poprc_homolog
```

6. Configurar as variaveis a partir de `deploy/env/dokploy.env.example`.
7. Definir `APP_PUBLIC_URL` com a URL HTTPS de homologacao, sem barra no final.
8. Configurar, pela aba Domains, o dominio no servico `frontend`, porta `8080`.
9. Fazer o primeiro deploy e conferir os health checks dos dois containers.

Nao adicionar Traefik ao arquivo Compose manualmente. O dominio deve ser gerido
pela interface do Dokploy. Nenhuma credencial real deve entrar no Git.

## Fase 3 - restaurar uma copia dos dados

Gerar um backup final no ambiente antigo:

```bash
cd /opt/poprc/current
sudo env POPRC_ENV_FILE=/etc/poprc/poprc.env bash deploy/scripts/backup-vps.sh
sudo ls -lht /var/backups/poprc | head
```

O pacote possui `database.dump`, `uploads/`, `manifest.txt` e `SHA256SUMS`.
Extraia uma copia em diretorio temporario. Restaure `database.dump` somente no
banco `poprc_homolog`, nunca diretamente sobre o banco de producao. Copie o
conteudo de `uploads/` para o volume nomeado `poprc_uploads` com os containers
parados ou por um container auxiliar controlado.

Depois da copia:

1. iniciar PostgreSQL e backend;
2. aguardar o backend ficar healthy e o Flyway validar as migracoes;
3. iniciar o frontend;
4. abrir fotos, evidencias e PDFs antigos;
5. comparar materiais, saldos, faltas, ORs e valor total do estoque.

## Fase 4 - backups no Dokploy

Configurar dois backups distintos para o mesmo destino S3 privado:

1. backup logico do PostgreSQL na aba Backup do banco;
2. Volume Backup do volume nomeado de uploads.

Executar o botao Test nas duas configuracoes. Restaurar ambos em recursos de
homologacao descartaveis antes de considerar a rotina aprovada. O backup geral do
Dokploy protege a plataforma, nao substitui esses dois backups da aplicacao.

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
