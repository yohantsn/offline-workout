# tasks.md — Tarefas de Fundação

> Este documento fecha o **B-1** de `Tasks-Import.md`: dá escopo verificável aos
> pré-requisitos que a importação de treinos declara. Escreve-se o **contrato de cada
> tarefa**, não a implementação.
>
> **Escopo deste documento**: apenas as três camadas que a importação precisa estender —
> dados, repositórios e editor. Nada aqui implementa importação.

## Contexto

A importação de treinos (`docs/Tasks-Import.md`) termina em uma gravação no banco. Hoje essa
gravação **não existe**: `WorkoutRepository` grava **um treino por vez**
(`createWorkoutWithExercises` / `updateWorkoutWithExercises`), e o editor cuida de **um
formulário por vez**.

Um `.xlsx` com treinos A/B/C gera, portanto, N gravações separadas. Se a terceira falhar, o
usuário fica com A e B no banco e C perdido — ou, pior, com um treino pela metade.

As três tarefas abaixo fecham essa lacuna **antes** de qualquer parser existir, para que a
`WorkoutImporter` (IMP-0.1) possa contar com ela.

## Estado atual (verificado no repositório)

| Item | Estado |
|---|---|
| Room schema | v1, 4 entidades, `exportSchema = true`, sem `fallbackToDestructiveMigration` |
| `WorkoutRepository` | `createWorkoutWithExercises(draft)` e `updateWorkoutWithExercises(id, draft)` — **um agregado por chamada** |
| `WorkoutAggregateDraft` | `code`, `name`, `exercises: List<ExerciseAggregateDraft>`; `position` derivado do índice, nunca enviado pela UI |
| Editor | `WorkoutEditorViewModel` com `WorkoutEditorFormState` (strings) e `fieldErrors: Map<EditorField, String>`; **um formulário por vez** |
| Testes | **477**, 0 falhas |

---

## T1.3 — Gravação atômica de múltiplos treinos (camada de dados)

**Objetivo**: gravar N agregados em **uma transação**, tudo ou nada, na camada de dados.

### Escopo

- Método em `WorkoutDao`/`AppDatabase` que recebe `List<WorkoutAggregateDraft>` e grava todos
  os treinos com seus exercícios dentro de um único `withTransaction`.
- Reaproveitar a estratégia de 2 fases já em uso (posições temporárias negativas) para
  respeitar `UNIQUE(workoutId, position)` em cada treino.
- **Validação total antes do primeiro write**: todos os drafts da lista são validados
  (código não vazio, nome, descanso `>= 0`, coerência por tipo de exercício) e só depois
  qualquer inserção começa. Falha em qualquer item → rollback completo, banco intacto.
- Nenhuma escrita fora da transação. Sem `DELETE` (importar não apaga nada existente).

### Fora de escopo

- **Nenhuma mudança de schema.** As 4 entidades existentes bastam; a versão do Room
  continua **v1** e **não há migration**. Se a implementação exigir tabela nova, a tarefa
  volta ao `architect`.
- Sem upsert: importação **não** sobrescreve treino existente.
- Sem leitura de arquivo, sem parsing, sem UI.

### Critérios de aceite

- Importar 3 treinos cria os 3 com IDs preservados e ordem correta.
- Falha no 3º item deixa o banco **exatamente** como estava (teste que compara estado antes
  e depois).
- `UNIQUE(workoutId, position)` nunca violada durante a gravação.
- `Room v1` inalterado; `app/schemas/.../1.json` idêntico ao atual.
- `RepositoryBoundaryTest` e `UiBoundaryTest` continuam verdes sem alteração.

### Risco

`withTransaction` aninhado quando o chamador já está em transação. A implementação deve
usar `TransactionScope` explicitamente, não aninhar `withTransaction`.

---

## T2.4 — Contrato de repositório para importação em lote

**Objetivo**: expor a gravação em lote pela interface `WorkoutRepository`, preservando a
fronteira `ui/viewmodel → interface → Room`.

### Por que T2.4 e não T2.3

`T2.3` **já existe e está entregue**: foi a extensão dos contratos de repositório para o
histórico (`HistoryQuery`, `observeHistoryPage`, `observeHistoryWindow`,
`observeHistoryStats`), registrada em `README.md:563` como "T2.3 intacto". Reusar o número
criaria duas tarefas com o mesmo ID. A numeração de `Tasks-Import.md` que diz "T2.3" precisa
ser corrigida para **T2.4**.

### Escopo

- Um método novo em `WorkoutRepository`, na mesma forma dos agregados existentes:
  recebe `List<WorkoutAggregateDraft>` e retorna `Result<List<Long>>` com os IDs criados,
  na mesma ordem da entrada.
- Reaproveitar `RepositoryFailure` como está. **Sem tipo de falha novo** — colisão de
  `code` com treino existente já é `Conflict`.
- Implementação `RoomWorkoutRepository` delegando ao método de T1.3.
- Testes: `RoomWorkoutRepositoryTest` (ordem dos IDs, rollback, lista vazia), `MappingTest`,
  `RepositoryBoundaryTest` (superfície só-interfaces).

### Fora de escopo

- Sem mudança em `ExerciseHistoryRepository` (a importação **não** grava histórico).
- Sem ajuste de schema, sem UI.

### Critérios de aceite

- `ui`/`viewmodel` continuam sem importar Room, DAOs ou `data.local` (verificado por grep +
  `RepositoryBoundaryTest`).
- `Result` carrega `RepositoryFailure` estável, sem SQL e sem caminho de arquivo.
- `CancellationException` relançada.
- Suíte completa verde: **477 testes + os novos, 0 falhas**.

### Decisões tomadas

| Decisão | Escolha | Por quê |
|---|---|---|
| Código de treino já existente | **Abortar tudo com `Conflict`**, informando qual código colidiu | Sufixo automático ("A (2)") cria treino que o usuário não pediu e polui a lista. Sugerir código livre é bom, mas é **ação de UI** (IMP-6.1), não política de repositório: o repositório é conservador e determinístico, a revisão oferece a correção |
| Limite de treinos por importação | **`IMPORT_MAX_WORKOUTS = 20`**, aplicado no repositório; exceder falha com `Validation` | Cobre um programa real (A/B/C) e mantém a transação limitada. **Rejeitar**, nunca truncar em silêncio — truncamento perderia treino sem o usuário perceber |
| Treino vazio vindo do arquivo | **Rejeitar com `Validation`.** `WorkoutEditorConfig.allowEmptyWorkout` continua `true` no editor manual | Treino sem exercício vindo de planilha é quase sempre artefato de parsing, não intenção. Falhar alto é melhor que criar lixo no banco. Não mexe em `WorkoutEditorConfig` |

---

## T4.3 — Lógica de formulário reutilizável para a revisão de importação

**Objetivo**: permitir que a tela de revisão de importação edite **vários** rascunhos sem
duplicar a validação que já existe no editor.

### O problema

`WorkoutEditorViewModel` mantém **um** `WorkoutEditorFormState` por vez: um `codeText`, um
`nameText`, uma lista de exercícios. A tela de revisão de importação precisa manter **vários**
dessas unidades ao mesmo tempo, marcar as de baixa confiança e permitir corrigir um campo em
poucos toques.

Copiar o `WorkoutEditorViewModel` para uma variante "multi" duplicaria a validação por tipo
(SERIES/TIMED/DISTANCE_TIME) — exatamente o que a revisão de código já reprova em T4.2
(duplicar lógica em vez de reaproveitar).

### Escopo

- Extrair de `WorkoutEditorViewModel` uma unidade **pura e reutilizável** que represente o
  rascunho de **um** treino: strings + erros de campo + validação. Nome a definir na
  implementação; o requisito é que não dependa de `ViewModel`, `Repository` ou Android.
- `WorkoutEditorViewModel` passa a **consumir** essa unidade. Comportamento observável
  **inalterado** — nenhum teste existente pode quebrar ou precisar de alteração.
- Testes da unidade extraída cobrindo as mesmas regras por tipo, sem Android.

### Fora de escopo

- **Não** implementa a tela de revisão de importação (é IMP-6.1/6.3).
- **Não** mexe em `WorkoutEditorScreen` nem no design da tela de edição.
- Sem campo de confiança nesta tarefa — confiança é do `ImportedWorkoutDraft` (IMP-0.1).

### Critérios de aceite

- A unidade extraída não referencia `android.*`, `Room`, `ViewModel` ou `Repository`
  (assertion no teste de fronteira).
- Os testes de `WorkoutEditorViewModelTest` e `WorkoutEditorScreenTest` passam **sem
  edição** — é a prova de que o comportamento não mudou.
- Cobertura por tipo (`SERIES`, `TIMED`, `DISTANCE_TIME`) e por erro de campo preservada.
- Suíte completa verde.

### Decisões tomadas

| Decisão | Escolha | Por quê |
|---|---|---|
| Onde vive a unidade | **Novo pacote `com.offlineworkout.ui.draft`**, com o nome dos tipos a definir na implementação | Não é exclusivo do editor (importação também consome), então `ui/editor` seria semanticamente errado. `data/model` é pior: é contrato de domínio, e um formulário com `fieldErrors` é preocupação de UI. `viewmodel → ui` é **permitido** pela fronteira (o que `UiBoundaryTest` proíbe é `data.local`/Room/DAO em `ui` e `viewmodel`), e já há precedente: `ExerciseHistoryViewModel` importa `com.offlineworkout.ui.MissingRecord` |
| Como N rascunhos ficam na tela | **Um rascunho por vez, com indicador de progresso** ("Treino 2 de 5") | Vários formulários simultâneos estouram o estado de composição e o TalkBack. A lista de erros fica resumida por trás de um contador, para o usuário ter um alvo claro de "o que foi mal lido". Revisão um-a-um também casa com o objetivo de "corrigir um valor em no máximo 2 toques" |

---

## Dependências

```
T1.3 (dados, atômico em lote)
  └─▶ T2.4 (contrato do repositório)
        └─▶ IMP-0.1 (arquitetar o pipeline)
              └─▶ IMP-0.2 … (resto da importação)

T4.3 (formulário reutilizável) ─┐
                               └─▶ IMP-6.1 / IMP-6.2 (UI de importação)
```

T1.3 e T4.3 são **independentes entre si** (dados vs. editor) e podem ser executadas em
paralelo. **T2.4 depende de T1.3** — a interface expõe o método cuja implementação em Room
entrega T1.3 — e não deve rodar em paralelo com ela, sob pena de dois agentes editarem
`RoomWorkoutRepository` ao mesmo tempo.

## Critérios de aceite comuns às três tarefas

- `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest --rerun-tasks` verde.
- Testes **477 + os novos, 0 falhas**.
- Nenhuma permissão nova no manifest; `INTERNET` continua ausente.
- Room continua em **v1**.
- Frontreira `ui/viewmodel → interface → Room` intacta.

## Rastreabilidade

| Tarefa | Atende o quê |
|---|---|
| T1.3 | `Tasks-Import.md` IMP-6.2 ("gravação em transação única"), IMP-0.2 |
| T2.4 | `Tasks-Import.md` IMP-0.2, IMP-6.2; substitui o "T2.3" citado lá |
| T4.3 | `Tasks-Import.md` IMP-6.1, IMP-6.3, IMP-6.5 (reaproveitar T4.x sem duplicar) |

## Ainda ausente

`intent.md` continua não existente. As Features de importação referenciam seus requisitos
("100% offline", "3 tipos de exercício", "Clean-lite") — todos já verificáveis no
`README.md` e no código. Se `intent.md` for escrito, ele deve ser a fonte de verdade e as
duas feature docs passam a citá-lo.