# Tasks-Import.md — Importação de Treinos (Excel/CSV, PDF, Foto)

> Especificação de feature. Este documento **não implementa nada**: ele descreve o
> pipeline, o time de agentes e o encadeamento de tarefas. A implementação só começa
> depois que os bloqueios registrados em "Antes de começar" estiverem resolvidos.

Escopo: **somente** a feature de importação de treinos a partir de arquivos
(Excel/CSV/PDF) e imagens, 100% offline.

## Antes de começar — bloqueios abertos

Verificados contra o repositório em 2026-10-14.

| # | Bloqueio | Evidência |
|---|---|---|
| B-1 | `intent.md` **não existe no repositório** | O `README.md` registra "`Tasks.md` da Fase N ausente (bloqueio de fonte)" nas Fases 1 a 6. **Parcialmente resolvido**: [`tasks.md`](tasks.md) agora define o escopo das tarefas de fundação |
| B-2 | Os pré-requisitos **T1.3, T2.4, T4.3 não estão implementados** | O `README.md` documenta como entregues **T1.2**, **T2.2** e **T4.2**. O escopo das tarefas `.3`/`.4` está definido em [`tasks.md`](tasks.md), mas nenhuma foi implementada |
| B-3 | O tamanho do APK não é tratado como restrição | APK debug atual: **9,9 MB**. Sem `INTERNET`, nenhum modelo pode ser baixado em runtime |
| B-4 | O formato `.xls` (binário antigo) não está em lugar nenhum | O escopo fala em `.xlsx` e `.csv`. `.xls` não é nenhum dos dois |

Enquanto B-1 e B-2 existirem, os pré-requisitos não são verificáveis. B-1 está
parcialmente resolvido por [`tasks.md`](tasks.md); B-2 só se resolve quando T1.3, T2.4 e
T4.3 forem implementadas.

> **Nota de numeração**: o desenho original citava "T2.3". Esse ID já está ocupado — foi a
extensão dos contratos de repositório para o histórico (`README.md:563`, "T2.3 intacto"). A
tarefa de importação é **T2.4**. Ver [`tasks.md`](tasks.md).

## Restrições herdadas do projeto

- **Offline total**: o app não pode declarar a permissão `INTERNET`, e nenhuma etapa do
  pipeline pode depender de rede.
- **Stack**: Kotlin + Jetpack Compose + MVVM + Room, Clean-lite (`ui → viewmodel →
  data/repository → data/local`, sem `domain`).
- Números de hoje: **477 testes, 0 falhas**; `minSdk` 26, `compileSdk`/`targetSdk` 35.

## Contexto da feature

**Problema**: treinos costumam vir em PDF ou Excel, e cadastrá-los manualmente no app é
moroso.

**Solução**: pipeline de importação em camadas, do mais barato ao mais pesado, sempre com
tela de revisão antes de salvar:

1. **Excel/CSV** → parser determinístico com mapeamento de colunas
2. **PDF com texto** → extração de texto no dispositivo + heurísticas/regex
3. **PDF escaneado / foto** → OCR on-device
4. **Casos bagunçados (opcional)** → LLM pequena local gerando JSON no schema do app
5. **Revisão obrigatória** pelo usuário antes de gravar no banco

## Time de agentes

Cada agente é uma sessão separada, com escopo e contrato de entrada/saída fixos. Nenhum
agente troca de papel. Modelos servidos pelo harness `kilocode`:

| Agente | Nível | Modelo | Alternativa |
|---|---|---|---|
| `ui-designer` | High | `kilo/qwen/qwen3-235b-a22b-thinking-2507` | `kilo/deepseek/deepseek-r1` |
| `architect` | High | `kilo/qwen/qwen3-235b-a22b-thinking-2507` | `kilo/deepseek/deepseek-r1` |
| `developer` | Low | `kilo/qwen/qwen3-coder-30b-a3b-instruct-2507` | `kilo/qwen/qwen3-coder` |
| `code-reviewer` | Medium | `kilo/qwen/qwen3-32b` | `kilo/qwen/qwen3-coder-30b-a3b-instruct-2507` |
| `ui-reviewer` | Medium | `kilo/qwen/qwen3-vl-30b-a3b-instruct-2507` | `kilo/qwen/qwen3-vl-235b-a22b-instruct` |
| `security-reviewer` | High | `kilo/deepseek/deepseek-r1` | `kilo/qwen/qwen3-235b-a22b-thinking-2507` |

> **Nota**: `Qwen2.5-VL-32B-Instruct`, citado no desenho original, não está disponível. A
> substituição `qwen3-vl-30b-a3b-instruct` cobre o mesmo papel (visão para screenshots).
>
> **Nota sobre "open source, gratuito"**: Qwen3 e DeepSeek-R1 são modelos de pesos
> abertos, mas servidos por um provedor — não são necessariamente gratuitos. Decidir se
> o custo de token importa antes de instanciar o time.

**Nota de design**: o `security-reviewer` usa modelo diferente do `architect` de propósito.
Modelos distintos têm pontos cegos distintos, e quem audita segurança não deve compartilhar
os vieses de quem projetou.

### Definição dos agentes

#### `ui-designer`

- **Responsabilidade**: definir fluxo, telas, componentes, estados (vazio/erro/carregando),
  hierarquia visual e acessibilidade. Não escreve código final.
- **Input**: requisitos da feature, contratos de dados definidos pelo `architect`, tema
  Material 3 do projeto.
- **Output**: especificação de design (wireframes, lista de componentes Compose, tokens de
  cor/tipografia, comportamento de cada estado).

#### `architect`

- **Responsabilidade**: decisões estruturais (interfaces, schemas, escolha de bibliotecas,
  fluxo de dados, ViewModels, estratégia de fallback). Não escreve implementação final.
- **Input**: objetivo da tarefa, saídas de tarefas anteriores, restrições do projeto
  (offline, Clean-lite).
- **Output**: especificação técnica que é **input obrigatório** do `developer`.

#### `developer`

- **Responsabilidade**: implementar código a partir das especificações do `architect` e do
  `ui-designer`. Não toma decisões estruturais novas; se encontrar lacuna, sinaliza e
  devolve.
- **Input**: especificação técnica (+ especificação de design nas tarefas de UI) + código
  existente.
- **Output**: código implementado, pronto para revisão.

#### `code-reviewer`

- **Responsabilidade**: validar aderência à especificação, qualidade, corretude, tratamento
  de erros, performance, testabilidade e cobertura de testes.
- **Input**: especificação do `architect` + código do `developer` + critérios de aceite.
- **Output**: relatório com veredito por item (aprovado/reprovado) e severidade
  (bloqueante/importante/sugestão).

#### `ui-reviewer`

- **Responsabilidade**: validar aderência ao design do `ui-designer`, usabilidade durante o
  treino (poucos toques, texto grande, uso com mão suada), acessibilidade, consistência com
  o restante do app e tratamento visual de todos os estados.
- **Input**: especificação de design + código/screenshots da UI implementada.
- **Output**: relatório com veredito por item e severidade.

#### `security-reviewer`

- **Responsabilidade**: auditar superfícies de ataque da feature (entrada de arquivos não
  confiáveis, parsing, permissões, dados sensíveis, supply chain de dependências, prompt
  injection no caso da LLM local).
- **Input**: especificação do `architect` + código do `developer` + threat model da tarefa.
- **Output**: relatório de segurança com achados classificados por severidade
  (crítico/alto/médio/baixo).
- **Autoridade especial**: achado **crítico ou alto** é **bloqueante com poder de veto** — a
  tarefa não avança até ser corrigido e reauditado.

## Fluxo de handoff

**Tarefa sem UI** (parsers, orquestrador):

```
architect ──(spec)──▶ developer ──(código)──▶ ┬─ code-reviewer ──┐
                                               └─ security-reviewer ┴──▶ aprovação
```

**Tarefa com UI**:

```
ui-designer ──(design)──▶ architect ──(spec técnica)──▶ developer ──(código)──▶ ┬─ ui-reviewer ───────┐
                                                                                ├─ code-reviewer ─────┼──▶ aprovação
                                                                                └─ security-reviewer ─┘
```

**Loops de correção**:

- Problema de implementação → volta ao `developer`
- Problema de especificação técnica → volta ao `architect`
- Problema de design/usabilidade → volta ao `ui-designer` (e depois `architect`/`developer`
  se o design mudar contratos)
- Lacuna encontrada pelo `developer` → volta ao `architect` (ou `ui-designer`, se for de UI)

## Regras de execução

1. **Instanciar os 6 agentes** como sessões separadas. Nenhum agente troca de papel.
2. Cada tarefa (`IMP-x.y`) é uma **unidade de alto nível**. Quebrar em **subtarefas
   executáveis** (arquivos a criar/editar, comandos, checklists), sempre atribuídas ao
   **mesmo agente** da tarefa-mãe.
3. Respeitar a ordem do fluxo de handoff dentro de cada fase. O `developer` só começa
   depois de todas as tarefas de `architect` (e `ui-designer`, quando houver) da fase
   estarem concluídas.
4. **Handoff explícito**: injetar a saída de cada agente como contexto de entrada do
   próximo. Nunca deixar um agente inferir a especificação sozinho.
5. **Revisão paralela**: `code-reviewer`, `ui-reviewer` e `security-reviewer` são
   independentes entre si e **podem rodar em paralelo** sobre o mesmo código entregue pelo
   `developer`. Cada um recebe o mesmo código, mas **não vê o relatório dos outros**
   (evita viés de concordância). O Traycer consolida os relatórios depois.
6. **Critério de aprovação**: a tarefa só é considerada concluída quando **todos** os
   revisores aplicáveis aprovam. Achado bloqueante de qualquer revisor reabre a tarefa;
   achado crítico/alto do `security-reviewer` tem poder de veto.
7. Revisores **nunca corrigem código diretamente**. Eles reportam; o Traycer devolve ao
   agente adequado conforme os loops de correção.
8. Tarefas de revisão devem gerar **checklists verificáveis** (item + veredito +
   severidade), nunca revisão genérica.
9. **Rastreabilidade**: toda subtarefa gerada deve referenciar a tarefa-mãe (`IMP-x.y`) e o
   agente de origem.
10. **Fixtures e dados de teste**: usar apenas arquivos **anonimizados ou sintéticos**.
    Nenhum dado pessoal real deve aparecer em prompts, logs ou repositório.

---

## Fase 0 — Fundação do Pipeline de Importação

### IMP-0.1 — Arquitetar o pipeline de importação

- **Agente**: `architect`
- **Tipo**: Arquitetura
- **Descrição**: Definir a arquitetura do pipeline: interface comum `WorkoutImporter`,
  modelo intermediário `ImportedWorkoutDraft` (treinos, exercícios dos 3 tipos do app,
  campos de confiança por item, lista de warnings), JSON Schema do draft, detecção do tipo
  de arquivo (por conteúdo, não só por extensão), limites de tamanho/páginas/linhas,
  estratégia de erros. Definir também **como a LLM local seria entregue offline** (modelo
  embutido no APK vs. importado manualmente pelo usuário via seletor de arquivos) e registrar
  a decisão com prós e contras.
- **Critérios de aceite**:
  - O draft cobre os 3 tipos de exercício (Séries, Tempo, Distância/Tempo) e múltiplos
    treinos (A/B/C)
  - Toda saída de qualquer importer passa pelo mesmo schema de validação
  - Decisão de entrega do modelo local documentada
  - Nenhum componente exige permissão `INTERNET`
- **Depende de**: T1.3, T2.4 (de [`tasks.md`](tasks.md)) — **não implementadas**

### IMP-0.2 — Implementar contratos, modelo de draft e validador

- **Agente**: `developer`
- **Tipo**: Desenvolvimento
- **Descrição**: Implementar `WorkoutImporter`, `ImportedWorkoutDraft`, o validador de schema
  (com limites de tamanho de strings e de quantidade de itens), o detector de tipo de
  arquivo e os tipos de erro.
- **Depende de**: IMP-0.1

### IMP-0.3 — Revisar contratos e validador

- **Agente**: `code-reviewer`
- **Tipo**: Revisão
- **Descrição**: Checar aderência à especificação, cobertura dos casos de borda do validador
  (campos nulos, valores negativos, strings enormes), testabilidade e ausência de
  acoplamento com Room/UI.
- **Depende de**: IMP-0.2

### IMP-0.4 — Auditar segurança da fundação

- **Agente**: `security-reviewer`
- **Tipo**: Revisão (Segurança)
- **Descrição**: Produzir o **threat model** da feature (arquivos não confiáveis como vetor
  de entrada) e auditar a fundação: manifest sem `INTERNET`, acesso a arquivos apenas via
  seletor do sistema (SAF/Photo Picker) sem permissões amplas de armazenamento, limites
  aplicados antes de qualquer parsing, validação estrita do draft, mensagens de erro sem
  vazar caminhos ou dados.
- **Critérios de aceite**: threat model documentado e usado como referência pelas fases
  seguintes; nenhum achado crítico/alto em aberto
- **Depende de**: IMP-0.2

---

## Fase 1 — Parser de Excel/CSV

### IMP-1.1 — Arquitetar o parser de Excel/CSV

- **Agente**: `architect`
- **Tipo**: Arquitetura
- **Descrição**: Definir a estratégia de leitura de `.xlsx` e `.csv` (escolha de biblioteca
  compatível com Android, justificada por tamanho, manutenção e segurança contra XXE/zip
  bomb), detecção de cabeçalhos por sinônimos em PT/EN (ex: "Exercício/Exercise",
  "Séries/Sets", "Reps/Repetições", "Carga/Peso/Weight", "Descanso/Rest"), múltiplas abas ou
  blocos como treinos A/B/C, detecção de encoding/delimitador no CSV, e **mapeamento manual
  de colunas** como fallback quando a detecção falhar.
- **Critérios de aceite**:
  - Definida a biblioteca e a justificativa
  - Estratégia de fallback para mapeamento manual especificada (dados que a UI precisará
    coletar)
  - Células com valores como "4x10", "3 x 12" ou "10 min" tratadas por regras explícitas
- **Depende de**: IMP-0.4

### IMP-1.2 — Implementar parser de Excel/CSV

- **Agente**: `developer`
- **Tipo**: Desenvolvimento
- **Descrição**: Implementar o importer conforme IMP-1.1, incluindo a detecção de cabeçalhos,
  a interpretação de células compostas e a emissão de draft com confiança por campo.
- **Depende de**: IMP-1.1

### IMP-1.3 — Revisar parser de Excel/CSV

- **Agente**: `code-reviewer`
- **Tipo**: Revisão
- **Descrição**: Validar corretude com planilhas variadas (cabeçalho em linha diferente,
  linhas vazias, células mescladas, decimais com vírgula), performance e testes.
- **Depende de**: IMP-1.2

### IMP-1.4 — Auditar segurança do parser de Excel/CSV

- **Agente**: `security-reviewer`
- **Tipo**: Revisão (Segurança)
- **Descrição**: Verificar proteção contra XXE na leitura do XML do `.xlsx`, zip bomb
  (limite de tamanho descompactado e de entradas), arquivos `.xlsm` com macros (macros nunca
  executadas nem lidas), limites de linhas/colunas, timeouts e execução fora da thread
  principal com cancelamento.
- **Depende de**: IMP-1.2

---

## Fase 2 — Parser de PDF com texto

### IMP-2.1 — Arquitetar o parser de PDF

- **Agente**: `architect`
- **Tipo**: Arquitetura
- **Descrição**: Definir a extração de texto on-device (biblioteca compatível com Android e
  licença adequada), preservação de estrutura tabular quando possível, catálogo de
  padrões/regex (`4x10`, `3 séries de 12`, `20kg`, `10 min`, `5km`, descanso `60s`),
  agrupamento de linhas em exercícios e treinos, e cálculo de **confiança** por item. Definir
  também o critério para detectar PDF sem texto útil (escaneado) e encaminhá-lo ao OCR.
- **Critérios de aceite**:
  - Catálogo de padrões documentado com exemplos
  - Critério objetivo para "PDF sem texto" definido
  - Itens de baixa confiança sinalizados no draft
- **Depende de**: IMP-0.4

### IMP-2.2 — Implementar parser de PDF

- **Agente**: `developer`
- **Tipo**: Desenvolvimento
- **Descrição**: Implementar extração de texto, heurísticas/regex e geração do draft conforme
  IMP-2.1.
- **Depende de**: IMP-2.1

### IMP-2.3 — Revisar parser de PDF

- **Agente**: `code-reviewer`
- **Tipo**: Revisão
- **Descrição**: Validar robustez das regex (sem backtracking catastrófico), cobertura de
  variações de escrita, comportamento em PDFs grandes e qualidade dos testes.
- **Depende de**: IMP-2.2

### IMP-2.4 — Auditar segurança do parser de PDF

- **Agente**: `security-reviewer`
- **Tipo**: Revisão (Segurança)
- **Descrição**: Verificar limites de páginas/tamanho, proteção contra PDFs malformados ou com
  recursão, **ReDoS** nas expressões regulares, timeouts com cancelamento, ausência de
  execução de conteúdo ativo (JavaScript/anexos do PDF) e tratamento seguro de PDFs
  protegidos por senha.
- **Depende de**: IMP-2.2

---

## Fase 3 — OCR (PDF escaneado e fotos)

### IMP-3.1 — Arquitetar a camada de OCR

- **Agente**: `architect`
- **Tipo**: Arquitetura
- **Descrição**: Definir o uso de OCR on-device (variante **embutida/offline** do ML Kit
  Text Recognition ou equivalente open source, garantindo funcionamento sem rede), entrada por
  foto (câmera/galeria) e por páginas de PDF escaneado, pré-processamento (redimensionamento,
  rotação), integração do texto reconhecido com o parser da Fase 2 e penalização de confiança
  para texto vindo de OCR.
- **Critérios de aceite**:
  - Confirmado que a solução escolhida funciona 100% offline
  - Fluxo OCR → parser de texto especificado
  - Limites de resolução/memória definidos
- **Depende de**: IMP-2.3

### IMP-3.2 — Implementar OCR

- **Agente**: `developer`
- **Tipo**: Desenvolvimento
- **Descrição**: Implementar o importer de imagem/PDF escaneado conforme IMP-3.1.
- **Depende de**: IMP-3.1

### IMP-3.3 — Revisar OCR

- **Agente**: `code-reviewer`
- **Tipo**: Revisão
- **Descrição**: Validar gestão de memória com imagens grandes (downscale, reciclagem de
  bitmaps), execução fora da thread principal, tratamento de falhas de reconhecimento e testes.
- **Depende de**: IMP-3.2

### IMP-3.4 — Auditar segurança do OCR

- **Agente**: `security-reviewer`
- **Tipo**: Revisão (Segurança)
- **Descrição**: Verificar que imagens e arquivos temporários são removidos do cache após o
  uso, que a permissão de câmera (se usada) é solicitada apenas no momento necessário, que não
  há leitura de metadados sensíveis (EXIF/localização) além do necessário, limites de
  dimensão/tamanho contra imagens maliciosas e ausência de qualquer chamada de rede.
- **Depende de**: IMP-3.2

---

## Fase 4 — LLM Local (Opcional)

**Fase opcional.** Só executar se, após as Fases 1–3, a variabilidade dos arquivos de treino
justificar. Peso de RAM/bateria e a entrega do modelo offline tornam esta fase a mais
custosa.

### IMP-4.1 — Arquitetar a integração com LLM local

- **Agente**: `architect`
- **Tipo**: Arquitetura
- **Descrição**: Definir runtime on-device (ex: MediaPipe LLM Inference/LiteRT, ou Gemini
  Nano via AICore quando disponível), escolha do modelo pequeno, **gatilho de uso** (apenas
  como fallback quando a confiança das camadas anteriores estiver baixa), prompt de extração
  com saída restrita ao schema JSON do draft, validação da saída pelo validador da IMP-0.2,
  detecção de capacidade do dispositivo (RAM mínima) com degradação graciosa, gestão de
  ciclo de vida/memória do modelo e a estratégia de entrega do modelo definida na IMP-0.1.
- **Critérios de aceite**:
  - Gatilho de uso objetivo e documentado
  - Saída da LLM **sempre** passa pelo validador de schema antes de virar draft
  - Dispositivos sem capacidade suficiente simplesmente não oferecem a opção
  - Todo o fluxo funciona sem rede
- **Depende de**: IMP-3.4

### IMP-4.2 — Implementar integração com LLM local

- **Agente**: `developer`
- **Tipo**: Desenvolvimento
- **Descrição**: Implementar o importer baseado em LLM local conforme IMP-4.1, incluindo
  detecção de capacidade, carregamento/descarregamento do modelo, geração com timeout e
  cancelamento.
- **Depende de**: IMP-4.1

### IMP-4.3 — Revisar integração com LLM local

- **Agente**: `code-reviewer`
- **Tipo**: Revisão
- **Descrição**: Validar liberação de memória, comportamento em baixa memória, cancelamento,
  tratamento de saída inválida/truncada e testes (com a LLM simulada por um fake
  determinístico).
- **Depende de**: IMP-4.2

### IMP-4.4 — Auditar segurança da LLM local

- **Agente**: `security-reviewer`
- **Tipo**: Revisão (Segurança)
- **Descrição**: Auditar **prompt injection** (o texto do documento é tratado estritamente
  como dado, nunca como instrução, e um documento com "ignore as instruções anteriores" não
  altera o comportamento), ausência de ferramentas/ações disponíveis para o modelo (a saída é
  só texto validado), **integridade do arquivo de modelo** (checksum/assinatura verificados
  antes de carregar), origem confiável do modelo, ausência de rede e limites de tamanho de
  entrada/saída.
- **Critérios de aceite**: testes adversariais de prompt injection documentados e aprovados
- **Depende de**: IMP-4.2

---

## Fase 5 — Orquestrador do Pipeline

### IMP-5.1 — Arquitetar o orquestrador

- **Agente**: `architect`
- **Tipo**: Arquitetura
- **Descrição**: Definir a orquestração em camadas: escolha do importer pelo tipo de arquivo,
  ordem de fallback (Excel/CSV → PDF texto → OCR → LLM local, se existir), critérios de
  escalonamento por confiança, mesclagem de warnings, emissão de progresso para a UI (fluxo de
  estados), cancelamento cooperativo e política de erros (falha parcial ainda gera draft com
  avisos).
- **Critérios de aceite**:
  - Máquina de estados do import documentada (idle → analisando → rascunho pronto → erro)
  - Cancelamento propaga a todas as camadas
  - Orquestrador funciona com a Fase 4 presente **ou** ausente
- **Depende de**: IMP-1.4, IMP-2.4, IMP-3.4 (e IMP-4.4, se a Fase 4 for executada)

### IMP-5.2 — Implementar orquestrador

- **Agente**: `developer`
- **Tipo**: Desenvolvimento
- **Descrição**: Implementar o orquestrador conforme IMP-5.1, expondo estado via `StateFlow`.
- **Depende de**: IMP-5.1

### IMP-5.3 — Revisar orquestrador

- **Agente**: `code-reviewer`
- **Tipo**: Revisão
- **Descrição**: Validar a máquina de estados, cancelamento, ausência de vazamento de
  coroutines, comportamento de fallback e testes de integração entre camadas.
- **Depende de**: IMP-5.2

---

## Fase 6 — UI de Importação

### IMP-6.1 — Desenhar fluxo e telas de importação

- **Agente**: `ui-designer`
- **Tipo**: Design de UI
- **Descrição**: Desenhar o fluxo completo: (1) entrada "Importar treino" com escolha de origem
  (arquivo ou foto), (2) estado de análise com progresso e opção de cancelar, (3) **tela de
  revisão/edição do rascunho** com destaque visual para itens de baixa confiança e warnings,
  (4) **mapeamento manual de colunas** quando a detecção falhar, (5) confirmação final com
  resumo do que será salvo, (6) estados de erro e vazio. Reaproveitar ao máximo os componentes
  da tela de cadastro/edição de treino (T4.x). Seguir o princípio do app: poucos toques, texto
  grande, Material 3, acessibilidade (contraste, tamanhos de toque, leitor de tela).
- **Critérios de aceite**:
  - Wireframes de todas as telas e estados
  - Lista de componentes Compose a usar/reaproveitar
  - Indicador de confiança definido de forma que **não dependa só de cor**
  - Fluxo de edição mínima (corrigir um valor em no máximo 2 toques)
- **Depende de**: IMP-5.1, T4.3 (de [`tasks.md`](tasks.md))

### IMP-6.2 — Arquitetar estado, navegação e persistência da UI

- **Agente**: `architect`
- **Tipo**: Arquitetura
- **Descrição**: A partir do design (IMP-6.1), definir `ViewModel`(s), estado de UI, eventos,
  navegação (entrada a partir da lista de treinos), reaproveitamento da lógica de edição de
  treino de T4.x e a **gravação transacional** do draft aprovado via repositório (tudo ou
  nada, sem treinos pela metade).
- **Critérios de aceite**:
  - Estado sobrevive a rotação de tela e a processo em segundo plano durante a análise
  - Gravação em transação única
  - Descartar o rascunho não deixa resíduos no banco nem arquivos temporários
- **Depende de**: IMP-6.1, IMP-5.3

### IMP-6.3 — Implementar UI de importação

- **Agente**: `developer`
- **Tipo**: Desenvolvimento
- **Descrição**: Implementar Composables e `ViewModel`(s) conforme o design (IMP-6.1) e a
  especificação técnica (IMP-6.2), incluindo seletor de arquivo/foto via mecanismos do sistema.
- **Depende de**: IMP-6.2

### IMP-6.4 — Revisar UI de importação

- **Agente**: `ui-reviewer`
- **Tipo**: Revisão (UI)
- **Descrição**: Comparar a UI implementada com o design: fidelidade visual, todos os estados
  (vazio/erro/carregando), usabilidade na academia (alvos de toque grandes, texto legível),
  indicador de confiança não dependente só de cor, acessibilidade (TalkBack, contraste,
  escala de fonte) e consistência com as demais telas.
- **Depende de**: IMP-6.3

### IMP-6.5 — Revisar código da UI de importação

- **Agente**: `code-reviewer`
- **Tipo**: Revisão
- **Descrição**: Validar aderência à especificação, gestão de estado, recomposições
  desnecessárias, tratamento de ciclo de vida, reaproveitamento de componentes (sem duplicar
  lógica de T4.x) e testes de `ViewModel`.
- **Depende de**: IMP-6.3

### IMP-6.6 — Auditar segurança da UI de importação

- **Agente**: `security-reviewer`
- **Tipo**: Revisão (Segurança)
- **Descrição**: Verificar tratamento das URIs recebidas do seletor (sem persistir permissões
  desnecessárias), **sanitização e limites de tamanho** de strings vindas do arquivo antes de
  exibir ou gravar, ausência de dados sensíveis em logs e telas de erro, limpeza de arquivos
  temporários ao descartar/concluir e garantia de que nenhum conteúdo importado é interpretado
  como código ou markup.
- **Depende de**: IMP-6.3

---

## Fase 7 — Testes e Fechamento

### IMP-7.1 — Arquitetar estratégia de testes e fixtures

- **Agente**: `architect`
- **Tipo**: Arquitetura
- **Descrição**: Definir a estratégia de testes da feature: conjunto de **fixtures
  sintéticas/anonimizadas** (xlsx e csv variados, PDF com texto, PDF escaneado, foto de
  treino, arquivos malformados e maliciosos para testes de segurança), métricas mínimas de
  acurácia de extração por tipo de arquivo (ex: % de exercícios, séries, reps e cargas
  corretos) e testes de instrumentação do fluxo completo.
- **Critérios de aceite**:
  - Fixtures não contêm dados pessoais reais
  - Metas de acurácia definidas por camada
  - Casos adversariais (zip bomb, PDF recursivo, prompt injection, arquivo gigante) incluídos
- **Depende de**: IMP-6.6

### IMP-7.2 — Implementar testes e fixtures

- **Agente**: `developer`
- **Tipo**: Desenvolvimento
- **Descrição**: Criar as fixtures e implementar testes unitários, de integração e de
  instrumentação conforme IMP-7.1.
- **Depende de**: IMP-7.1

### IMP-7.3 — Revisar cobertura e qualidade dos testes

- **Agente**: `code-reviewer`
- **Tipo**: Revisão
- **Descrição**: Validar que as metas de acurácia são medidas e atingidas, que os testes são
  determinísticos (LLM local simulada por fake), e que a cobertura inclui os caminhos de erro e
  de fallback.
- **Depende de**: IMP-7.2

### IMP-7.4 — Auditoria final de segurança

- **Agente**: `security-reviewer`
- **Tipo**: Revisão (Segurança)
- **Descrição**: Auditoria ponta a ponta: confirmar que o manifest final **não declara
  `INTERNET`** e que nenhuma dependência o exige, revisar as dependências adicionadas
  (licença, manutenção, vulnerabilidades conhecidas), executar os casos adversariais das
  fixtures, e confirmar que todos os achados anteriores foram corrigidos.
- **Critérios de aceite**: zero achados críticos/altos em aberto; relatório final assinado
- **Depende de**: IMP-7.2

### IMP-7.5 — Revisão final de UX ponta a ponta

- **Agente**: `ui-reviewer`
- **Tipo**: Revisão (UI)
- **Descrição**: Percorrer o fluxo completo (importar planilha → revisar → corrigir um campo →
  salvar → treino disponível para execução) e validar que o tempo e o número de toques são
  menores que o cadastro manual, que é o objetivo da feature.
- **Depende de**: IMP-7.2

### IMP-7.6 — Aprovação final da feature

- **Agente**: `code-reviewer`
- **Tipo**: Revisão
- **Descrição**: Verificar que todos os critérios de aceite desta feature e os requisitos do
  `intent.md` (offline, 3 tipos de exercício, Clean-lite) foram atendidos, e que todos os
  relatórios dos revisores estão aprovados.
- **Depende de**: IMP-7.3, IMP-7.4, IMP-7.5

---

## Decisões em aberto

| Decisão | Impacto | Quem decide |
|---|---|---|
| Executar a Fase 4 (LLM local)? | Depende da variabilidade dos arquivos de treino. Se forem parecidos entre si, as Fases 1–3 bastam | Você, após testar as Fases 1–3 com arquivos reais |
| Entrega do modelo local (embutido vs. importado manualmente) | Tamanho do APK vs. fricção para o usuário | `architect` em IMP-0.1, com sua validação |
| Biblioteca de leitura de Excel e de PDF | Tamanho, segurança e compatibilidade com Android | `architect` em IMP-1.1 e IMP-2.1 |
| Onde o peso das dependências vai parar | APK hoje tem 9,9 MB. Sem `INTERNET` não há download em runtime | `architect` em IMP-0.1 — ver "Antes de começar" B-3 |
| Suporte a `.xls` (binário antigo) | Não é `.xlsx` nem `.csv`; rejeitar com mensagem clara ou suportar | `architect` em IMP-1.1 — ver B-4 |

## Resumo de dependências

```
Pré-requisitos (docs/tasks.md): T1.3, T2.4, T4.3   ← definidos, não implementados
        │
Fase 0 (Fundação) ─▶ ┬─ Fase 1 (Excel/CSV) ─────────────────────────┐
                     ├─ Fase 2 (PDF texto) ─▶ Fase 3 (OCR) ─▶ Fase 4 (LLM, opcional)
                     └──────────────────────────────────────────────┴─▶ Fase 5 (Orquestrador)
                                                                            │
                                                          Fase 6 (UI) ◀─────┘
                                                                │
                                                          Fase 7 (Testes e Fechamento)
```

> **Observação sobre este grafo**: a UI (Fase 6) depende do orquestrador (Fase 5), que
> depende das Fases 1, 2 e 3. Ou seja, o usuário não vê nada até três camadas de parsing
> estarem prontas. A interface `WorkoutImporter` da IMP-0.1 permite inverter isso — entregar
> um recorte vertical (Excel/CSV + revisão) antes de construir as demais camadas.