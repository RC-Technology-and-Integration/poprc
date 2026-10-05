# Homologacao do fluxo operacional

**Status:** APROVADA em 22/07/2026 por execucao assistida e suite automatizada isolada.

A regressao mais recente executou smoke test das paginas principais e `92`
testes sem falhas, incluindo encerramento, faturamento, pagamento e seguranca.
Consulte `RESULTADO_FASE_C_INTERFACE_2026-07-22.md` para as evidencias mais
recentes de interface e regressao.

## Validacao automatizada

Antes da homologacao pela interface, execute:

```powershell
.\scripts\test.ps1
```

A suite cobre o ciclo OS, OR, retirada, devolucao, auditoria e encerramento,
alem das recusas de retirada antes da vistoria, dupla assinatura, ferramenta nao devolvida, operacoes
duplicadas, prazo invalido, material duplicado, concorrencia de estoque,
bobinas e imutabilidade do log. O banco usado termina em `_test` e e descartado
automaticamente.

## Preparacao

1. Pare a aplicacao que estiver conectada ao banco `poprc_dev`.
2. Execute `./scripts/reset-dev-database.ps1`.
3. Confirme digitando `RESETAR poprc_dev`.
4. Inicie o backend com `./mvnw.cmd -Dspring-boot.run.profiles=dev spring-boot:run`.
5. Inicie o frontend normalmente com `npm run dev` na pasta `frontend`.

O ambiente deve apresentar o contrato `DEV-CONTRATO-001`, a obra
`Obra Desenvolvimento`, um supervisor, dois tecnicos e tres materiais no
`Deposito Desenvolvimento`.

## Cenario principal

1. Abra **Ordens de Servico** e emita uma OS para a obra de desenvolvimento.
2. Defina datas validas e selecione ao menos um consumo, uma ferramenta e a
   bobina entre os materiais previstos.
3. Confira se a OS e a primeira OR nasceram vinculadas e se o status da OS e
   `AGUARDANDO_VISTORIA`.
4. Complete a Vistoria em **Gestao de Obras** com foto e assinatura. A OS deve
   passar para `AGUARDANDO_RETIRADA`.
5. Execute a retirada com as duas assinaturas obrigatorias. A OS deve passar
   automaticamente para `EM_EXECUCAO`.
6. Entre na **Area do Tecnico** com um membro da equipe, registre o checklist e
   envie evidencias fotograficas.
7. Complete Infraestrutura e Virada de Rede em **Gestao de Obras**. Esta frente
   pode avancar em paralelo com o envio e a validacao do relatorio tecnico.
8. Envie a OS para validacao administrativa e aprove a execucao. A OS deve
   ficar em `AGUARDANDO_DEVOLUCAO`; nao e necessario esperar a Virada de Rede
   para enviar o relatorio.
9. Registre a devolucao: a ferramenta deve retornar integralmente; consumo e
   metragem podem retornar parcialmente.
10. Confirme o status automatico `AGUARDANDO_AUDITORIA`. Em **Retirada e
   Devolucao**, informe os valores auditados e homologue o
   As-Built, inclusive com divergencia quando ela for justificada.
11. Confirme o status `AGUARDANDO_ENCERRAMENTO`, preencha, assine e salve o
   documento final de encerramento.
12. Encerre a obra e confirme a OS em `CONCLUIDA`.

Durante o cenario, confira a fila exibida em cada modulo:

- `AGUARDANDO_VISTORIA`: Gestao de Obras.
- `AGUARDANDO_RETIRADA`: Estoque.
- `EM_EXECUCAO`: Portal Tecnico, somente para a equipe atribuida.
- `AGUARDANDO_VALIDACAO`: Gestao de Ordens de Servico.
- `AGUARDANDO_DEVOLUCAO`: Estoque.
- `AGUARDANDO_AUDITORIA`: Auditoria de Retirada/Devolucao.
- `AGUARDANDO_ENCERRAMENTO`: Gestao de Obras.

O Dashboard Executivo deve consolidar todas essas filas e ordenar primeiro
pendencias criticas, depois as que vencem em ate 24 horas.

## Resultados esperados

- Nenhuma retirada acontece sem OR e dupla assinatura.
- Nenhuma retirada acontece antes da vistoria com foto e assinatura.
- O estoque reservado, livre e devolvido permanece conciliado por deposito.
- Ferramentas nao podem encerrar a devolucao com quantidade pendente.
- A OS nao avanca para validacao sem checklist e evidencia.
- Depois do envio para validacao, checklist e evidencias ficam bloqueados.
- A obra permanece em 90% apos a Virada de Rede e chega a 100% somente depois
  do encerramento formal.
- A OS concluida fica somente para consulta no Portal Tecnico.
- Fotos, documentos, assinaturas e movimentacoes continuam disponiveis no
  historico.
- Cada transicao da OS fica registrada no historico append-only com evento,
  responsavel e horario.
- Cada estado ativo gera uma unica pendencia para a area responsavel; ao mudar
  de estado, a pendencia anterior desaparece e a proxima area recebe a tarefa.
- Falta de material gera uma pendencia critica adicional no Estoque.

## Cenarios adicionais

- Tente retirar saldo maior que o disponivel.
- Gere uma segunda OR para a mesma OS.
- Tente devolver a mesma OR duas vezes.
- Altere o deadline para testar OS proxima do prazo e atrasada.
- Reduza e depois recomponha um saldo para validar alerta ativo, resolucao e
  nova ocorrencia.
- Homologue uma auditoria com sobra e outra com falta de material.
