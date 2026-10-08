# Simulação documental e revisão do build

## Simulação documental

O modo é escolhido apenas ao criar uma **nova OS fictícia**. V40 acrescenta
`simulacao BOOLEAN NOT NULL DEFAULT FALSE` à OS e ao documento interno, com
triggers que impedem conversão em qualquer direção. Documentos anteriores
continuam oficiais; V40 não inventa dados nem reescreve conteúdo, assinatura,
hash ou caminho do PDF. V1–V39 permanecem intactas. OR e seus snapshots herdam
a classificação da OS vinculada; versões e regenerações preservam o indicador.
Não converter as OS existentes #10/#11 para retomar o teste.

Habilitação futura, somente no servidor de homologação:

```dotenv
APP_AMBIENTE=homologacao
DOCUMENTOS_SIMULACAO_ENABLED=true
```

Os padrões são `nao-definido` e `false`. As duas condições são obrigatórias.
Payload e interface não habilitam o recurso. Desabilitá-lo bloqueia criação,
assinatura, versionamento, retirada/devolução simulada e encerramento formal
simulado. Consulta/download continuam sujeitos às permissões existentes e
preservam a identificação e o PDF marcado.

Empresa, CNPJ, nomes, contrato, unidade, endereço e declarações documentais
usam representações fictícias. O cabeçalho e a marca
**TESTE — SEM VALIDADE OPERACIONAL** aparecem em todas as páginas, incluindo
assinaturas, OR de retirada/devolução e documentos inicial/final. Conteúdo livre
do cenário deve ser fictício. Cadastros globais e operadores da auditoria não
são substituídos.

As assinaturas percorrem o **canvas normal**: desenhar a palavra **TESTE**,
confirmar e persistir o PNG capturado. O backend preserva esses bytes; não gera
assinaturas automáticas. Não usar nomes ou assinaturas reais. Imagens geradas
por `AssinaturaTeste` existem exclusivamente em fixtures Java para PDF e regras
de negócio; não comprovam captura pelo usuário.

Permissões, vínculo, checklist/foto, conciliação física, justificativa,
histórico, reabertura, bloqueios e encerramento formal continuam obrigatórios.
Relatório Técnico e Virada de Rede continuam independentes. O modo documental
**movimenta o estoque normalmente**: usar somente materiais, saldos, contrato,
projeto e nova OS fictícios no ambiente descartável/de homologação autorizado.

Após publicação autorizada, uma nova OS fictícia permitirá retomar vistoria
inicial e três assinaturas TESTE, OR, retirada/devolução, homologação física,
justificativa/histórico/reabertura quando permitida, Virada de Rede e documento
final com três assinaturas, seguido do encerramento formal.

## Origem da revisão do build

Exigir 40 caracteres hexadecimais valida somente o formato. Para vincular o SHA
ao código, o preparo resolve um **commit existente** e exporta seus arquivos
rastreados com `git archive` para um diretório novo:

```powershell
./scripts/prepare-build-revision.ps1 -Revision <commit-ou-ref-selecionado> -ContextDirectory <diretorio-novo>
```

O resultado contém `BUILD_CONTEXT=<snapshot>` e `APP_REVISION=<SHA completo>`.
Construir com o Compose/Dockerfiles **desse snapshot** e o valor produzido pelo
mesmo comando. `.git`, configuração ignorada e documentos não rastreados ficam
fora do snapshot e permanecem intactos no checkout original. Destino existente
é recusado. Nenhum arquivo de publicação é preenchido pelo teste.

Sem `ContextDirectory`, o modo informativo exige HEAD correspondente e checkout
limpo. Se houver trabalho ou documentos locais, preservar os arquivos e usar o
snapshot; não excluir arquivos para atender a essa exigência. O modo informativo
isolado não prova qual contexto será usado por um build posterior.

`APP_REVISION` passa pelo Compose para `VITE_APP_REVISION`, Dockerfile e Vite.
Ausência impede o Compose; valor curto/placeholder impede a compilação da imagem.
A imagem frontend recebe `org.opencontainers.image.revision` pelo mesmo valor.
O build não depende de `.git` dentro do contexto Docker. No desenvolvimento
local, o fallback Git pode adicionar `-local`; isso não é revisão implantada.

O CI resolve `git rev-parse HEAD` **depois do checkout**. Em `pull_request`, esse
HEAD normalmente é o merge temporário do workflow; não deve ser substituído por
`github.event.pull_request.head.sha`. O job registra os dois valores, exporta o
snapshot do HEAD efetivo, constrói as imagens e confere label e SHA no bundle.

O Dokploy não foi alterado. Sua interpolação de Environment no
[Compose é documentada](https://docs.dokploy.com/docs/core/docker-compose).
Não se pressupõe variável automática nem execução automática do script.
Para a futura publicação pelo provedor Git do Dokploy, será necessário:

1. Selecionar e verificar o commit realmente usado na origem configurada. Se a
   origem seguir uma branch, manter essa seleção estável durante o build e
   conferir o SHA do checkout nos registros do preparo/build.
2. Substituir `APP_REVISION=fe7828b` pelo SHA completo **daquele código**, a cada
   publicação. Só definir o valor manualmente não comprova a origem do contexto.
3. Aplicar as duas variáveis de simulação apenas na homologação autorizada.
4. Após autorização para implantação, verificar conteúdo servido e imagens da
   publicação real. Labels e o teste do CI não comprovam uma implantação futura.

O mecanismo de snapshot é diretamente reproduzível com Docker/Compose. Se o
build operacional não usar esse snapshot, a correspondência do checkout com
`APP_REVISION` precisa ser demonstrada pelo preparo operacional antes de publicar.
Não há SHA permanentemente fixado no exemplo de configuração.

## Validação e limites

Na revisão local de 07/10/2026 foram reproduzidos e corrigidos a substituição
indevida do PNG e a falta de vínculo do SHA com o contexto. Passaram 17 testes
Java pertinentes sem banco, 34 testes de frontend/preparo isolado, pacote Java,
build Vite e validação sintática de YAML/Bash. A auditoria npm manteve o gate
de vulnerabilidades altas: zero altas/críticas e duas moderadas de React Router,
cuja atualização major exige revisão separada. Evidências locais estão em
`outputs/revisao-simulacao-20261007/` (ignorado).

PostgreSQL 16, suíte completa, Chromium e imagens são gates do CI do novo PR:

- banco vazio V1–V40; atualização V38–V40 preservando homologações legadas;
- banco V39 recebendo **somente V40**, preservando conteúdo, assinatura,
  hash/caminho do PDF e OS legadas, e protegendo nova OS simulada;
- suíte completa em `poprc_test`, importação local explicitamente desabilitada;
- captura de TESTE por eventos de mouse no canvas normal, com comparação dos
  bytes enviados/persistidos, documentos inicial/final, vistoria e OR;
- E2E em banco novo `poprc_simulacao_e2e_test` e uploads em `RUNNER_TEMP`, com
  um único material fictício e evidências PNG/PDF/trace;
- imagens construídas do snapshot sem `.git`, Compose, Nginx, label e bundle.

O E2E comprova a captura normal e o percurso de retirada/devolução. O
encerramento formal e suas regras são exercitados separadamente pelos testes
de integração; assinar o documento final não significa concluir a obra.
PDFs multipágina são extraídos por página e renderizados para inspeção visual.
Resultados definitivos, skips e limitações ficam no PR e na entrega da revisão.
Um teste dedicado de migração habilitado por propriedade é executado em seu
passo específico mesmo se aparecer ignorado na execução geral.

Não iniciar PostgreSQL local nem contornar o bloqueio dos binários do Windows.
Nenhum resultado local/CI valida V40 ou o modo de simulação no Dokploy.
Merge, deploy, banco ativo e mudanças em serviços continuam sem autorização.
