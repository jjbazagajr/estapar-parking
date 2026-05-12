# Arquitetura & Padrões de Código

> Documento que descreve **como** o código é organizado e **quais princípios** devem ser seguidos ao evoluí-lo. Mantenha-o atualizado conforme o projeto crescer.

## 1. Visão de alto nível

```
                ┌────────────────────────┐
   POST events  │     garage-sim         │
       ───────▶ │  (container externo)   │
                └─────────────┬──────────┘
                              │ webhook (HTTP)
                              ▼
┌──────────────────────────────────────────────────────────┐
│                  estapar-parking (Spring Boot)           │
│                                                          │
│   ┌────────────┐  ┌────────────┐  ┌──────────────────┐   │
│   │ webhook    │  │ revenue    │  │ simulator        │   │
│   │ (POST)     │  │ (GET)      │  │ (RestClient +    │   │
│   │            │  │            │  │  ApplicationRunner)  │
│   └─────┬──────┘  └─────┬──────┘  └─────────┬────────┘   │
│         │               │                   │            │
│         ▼               ▼                   ▼            │
│   ┌────────────────────────────────────────────────┐     │
│   │                domain (JPA + Repos)            │     │
│   │   Sector · Spot · ParkingSession               │     │
│   └─────────────────────┬──────────────────────────┘     │
└─────────────────────────┼────────────────────────────────┘
                          ▼
                    ┌──────────┐
                    │  MySQL   │  (schema gerenciado por Flyway)
                    └──────────┘
```

## 2. Estilo arquitetural

**Layered + package-by-feature.** Cada feature de produto tem um pacote raiz coeso (controller + service + DTOs). O domínio é compartilhado em `domain/`.

```
com.estapar.parking
├── EstaparParkingApplication.kt   ← entrypoint
├── config/                        ← infra (RestClient, Clock, properties, OpenAPI)
├── domain/                        ← entidades JPA + repositórios + services de contexto + exceções de domínio
├── simulator/                     ← cliente HTTP do simulador + bootstrap (consome SectorService / SpotService)
├── garage/                        ← orquestra ENTRY/PARKED/EXIT + PricingPolicy
├── webhook/                       ← endpoint POST (transporte; delega ao garage)
├── revenue/                       ← endpoint GET + listener de AddToRevenueEvent (consome SessionService / SectorService)
├── session/                       ← SessionService + ParkingSessionRepository
├── spot/                          ← SpotService + SpotRepository
├── sector/                        ← SectorService + SectorRepository
└── ledger/                        ← RevenueLedgerRepository (sem service, decisão YAGNI)
```

`webhook/` contém o `WebhookController` (fino, só desserialização) e o `WebhookDispatcher` (`@Async`, faz o `when` por tipo de evento e chama `GarageService`).

**Por que package-by-feature?**
- Mudanças num domínio ficam contidas num pacote.
- Reduz acoplamento entre features: `revenue` não precisa importar nada de `webhook`. A comunicação entre `garage` e `revenue` é mediada por evento de domínio (`AddToRevenueEvent`) — sem chamada direta entre services.
- `domain/` é a única fronteira compartilhada — o que é compartilhado fica explícito.
- Feature services (`GarageService`, `RevenueService`, `GarageBootstrap`) **não enxergam repositórios** — falam apenas com os services de contexto em `domain/` (`SessionService`, `SpotService`, `SectorService`). Repositórios são detalhe de implementação do domínio.

### Camadas

| Camada | Responsabilidade | Regras |
|---|---|---|
| **Controller** (`*Controller.kt`) | HTTP I/O: bind de DTO, status code | **Nunca** contém regra de negócio. Em fronteiras assíncronas (webhook) só desserializa e despacha; payload válido sempre vira 200 |
| **Dispatcher async** (`WebhookDispatcher`) | Mudança de contexto sync→async no boundary de webhook + tratamento de exceções de domínio (log) | `@Async` em método de bean separado para o proxy AOP funcionar. Não fica no controller (self-invocation não é interceptada) |
| **Feature service** (`GarageService`, `RevenueService`) | Orquestração de regras cross-contexto + `@Transactional` | Não toca repositórios. Conversa com services de contexto e publica/consome eventos |
| **Domain service** (`SessionService`, `SpotService`, `SectorService`) | Operações de um único agregado: finders, salvamentos, transições de estado | Encapsula o repo. Chama métodos das entidades; traduz `ObjectOptimisticLockingFailureException` em exceção de domínio |
| **Entidade JPA** | Estado + invariantes do agregado | Regras de transição (`park()`, `exit()`, `occupy()`, …) vivem aqui. Validações lançam exceções de `DomainExceptions.kt` |
| **Repositório** | Persistência pura | Sem CAS UPDATE, sem regra de estado. Apenas `JpaRepository` + finders ou locks pessimistas legítimos (ex.: `findByNameForUpdate`) |
| **Config** | Beans, properties, integrações externas | Nada de regra de negócio |

### Fluxo de uma requisição (exemplo: `POST /webhook`)

```
HTTP request
    ▼
WebhookController.receive(WebhookEvent)        ← bind Jackson, sealed type discriminado por event_type
    │                                            (erro de desserializacao -> 400 default do Spring)
    ▼
WebhookDispatcher.dispatch(event)              ← @Async("webhookExecutor"), retorna imediatamente
    │
    ▼
HTTP 200          ← controller devolve antes do processamento, simulador nao espera

  ════════════════════════════════════════════════
  (depois, na thread do webhookExecutor:)
  ════════════════════════════════════════════════

GarageService.{registerEntry|parkVehicle|processExit}   ← @Transactional, orquestra
    │
    ├─ EntryEvent  → registerEntry()
    │     ├─ sectorService.isAnyOpenAt(time)
    │     └─ sessionService.openByPlate(plate, entry)
    │
    ├─ ParkedEvent → parkVehicle()
    │     ├─ sessionService.findOpenByPlate(plate)
    │     ├─ spotService.findByCoordinates(lat, lng)
    │     ├─ sectorService.lockByName(spot.sector)   ← pessimistic, regra de capacidade
    │     ├─ spotService.countOccupiedIn(sector)
    │     ├─ spotService.occupy(spot)                ← spot.occupy() + saveAndFlush; race ⇒ SpotAlreadyOccupiedException
    │     └─ sessionService.markParked(...)          ← session.park() + saveAndFlush; race ⇒ SessionAlreadyParkedException
    │
    └─ ExitEvent   → processExit()
          ├─ sessionService.findOpenByPlate(plate)
          ├─ spotService.findById(session.spotId)
          ├─ sessionService.markExited(session, exit)  ← session.exit() + saveAndFlush; race ⇒ SessionAlreadyExitedException
          ├─ spotService.release(spot)
          └─ publishEvent(AddToRevenueEvent)
                ▼
                RevenueService.addRevenue()   ← @EventListener síncrono, mesma TX:
                  ├─ sessionService.findById(event.sessionId)
                  ├─ sectorService.findByName(session.sector)
                  └─ ledger.save(...)         ← calcula via PricingPolicy, grava revenue_ledger

Excecoes de dominio (DomainRuleViolation) e DataIntegrityViolationException sao capturadas pelo dispatcher e logadas — nao tem como propagar para o cliente, que ja recebeu 200.
```

### Webhook async via `WebhookDispatcher`

Webhook é fronteira de integração com produtor externo (o simulador). Antes, o controller processava sync — `POST /webhook` segurava o simulador esperando todo o ciclo `ENTRY/PARKED/EXIT → DB → publishEvent → ledger` terminar. Inflar a regra do nosso lado iria atrapalhar o serviço de terceiros, padrão antagônico à recomendação de qualquer integrador (Stripe, GitHub, etc.: aceita payload, devolve 200 rápido, processa em background).

A separação que adotamos:

| Componente | Responsabilidade |
|---|---|
| `WebhookController` | Só desserialização e despacho. Sem try/catch — payload malformado vira 400 pelo handler padrão do Spring. Payload válido vira 200 imediato. |
| `WebhookDispatcher` | `@Async("webhookExecutor")`, faz o `when` por tipo de evento e chama `GarageService`. Captura `DomainRuleViolation` e `DataIntegrityViolationException` no log (não tem como propagar para o cliente que já recebeu 200). |
| `webhookExecutor` (em `AsyncConfig`) | `ThreadPoolTaskExecutor` com `core=4`, `max=16`, `queue=100`, `CallerRunsPolicy` em overflow — degrada para sync no caller quando o pool satura, sem perder eventos por rejeição silenciosa. |

| Decisão | Justificativa |
|---|---|
| **`@Async` em vez de fila durável** | Suficiente pro escopo de uma instância. Trade-off explícito: se a app crashar entre o `200` e a execução da task, o evento se perde (o simulador emite uma vez, sem retry). Caminho para produção real (outbox / Kafka / Rabbit) listado em `README.md` "Limitações conhecidas". |
| **Qualifier nomeado (`@Async("webhookExecutor")`)** | Em integration tests, o executor é trocado por `SyncTaskExecutor` via `@Profile("sync-async")`. Sem qualifier, a resolução por tipo bate em ambiguidade com `applicationTaskExecutor` auto-configurado pelo Boot. |
| **`CallerRunsPolicy`** | Quando `corePool` + `queue` saturam, o pool rejeitaria por default — eventos perdidos sem trace. Com `CallerRunsPolicy`, a task roda no thread do dispatcher (o request thread), efetivamente aplicando backpressure no simulador. |
| **Integration tests com `@ActiveProfiles("sync-async")`** | `SyncAsyncTestConfig` substitui o executor por `SyncTaskExecutor` (executa na thread chamadora). Asserts após `mockMvc.post` continuam determinísticos — o estado já está commitado quando a resposta retorna. Sem isso, precisaríamos de polling ou `Thread.sleep`, ambos vedados pelos princípios de teste. |

### Comunicação cross-feature via Spring Events

Saída do veículo dispara dois efeitos distintos: liberar a vaga (operacional, dono é `garage`) e contabilizar a receita (financeiro, dono é `revenue`). Em vez de `GarageService` chamar `RevenueService` diretamente, o `processExit` publica `AddToRevenueEvent(sessionId, exitTime)` e o pacote `revenue` reage por meio de `AddToRevenueListener` → `RevenueService.addRevenue(event)`.

| Decisão | Justificativa |
|---|---|
| **Listener síncrono (`@EventListener` sem `@Async` nem `@TransactionalEventListener`)** | Roda na mesma transação do `processExit`. Se o `INSERT` no ledger falhar, todo o exit dá rollback — não existe estado "carro saiu sem receita registrada". É a opção que dispensa outbox/retry, em troca de acoplar a disponibilidade do ledger à do exit. |
| **Evento carrega `exitTime` no payload** | Mantém o evento auto-descritivo (consumidor não precisa abrir a entidade para saber em que instante a saída aconteceu). Também desacopla o consumidor da forma como o produtor persiste o estado. |
| **Listener fica em `revenue/`, evento publicado por `garage`** | Mantém o consumidor dono da reação. `garage` só conhece o tipo do evento (importa `AddToRevenueEvent`), não como a receita é contabilizada. |
| **Idempotência no banco** | `revenue_ledger.session_id` é `UNIQUE` — qualquer republicação acidental falha no insert e o exit rolla back, sem ledger duplicado. |

### Domain services e regra de estado no agregado

A camada anterior tinha dois sintomas clássicos de violação de boundary:

- **Feature services chamavam repositórios de múltiplos contextos.** `GarageService` injetava `ParkingSessionRepository`, `SpotRepository` e `SectorRepository` ao mesmo tempo, decidindo quando salvar cada um. Modificações na regra de uma entidade puxavam edição em vários pontos.
- **Regra de estado dentro de query SQL.** `markParked`/`markExited`/`tryOccupy` eram `@Modifying` UPDATE com `WHERE` que filtrava o estado de origem (`exit_time IS NULL`, `occupied = false`). Idempotência grátis no banco, mas a regra de transição ficava espalhada entre Kotlin e JPQL — ruim para testar e para evoluir.

Solução em duas frentes:

**1. Services de contexto em `domain/`** (`SessionService`, `SpotService`, `SectorService`). Cada feature service fala só com esses; os repositórios viraram detalhe interno do domínio. O exemplo do `processExit` deixa de ser:

```kotlin
sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)
spots.findById(spotId)
sessions.markExited(id, exitInstant)
spots.findById(spotId).get().apply { occupied = false }
```

para:

```kotlin
val session = sessionService.findOpenByPlate(plate) ?: throw ...
val spot = spotService.findById(session.spotId) ?: error(...)
sessionService.markExited(session, exitInstant)
spotService.release(spot)
```

**2. Comportamento nas entidades + optimistic locking.** As regras de transição mudam de SQL para método de entidade:

```kotlin
fun park(parkedAt: Instant, sector: String, spotId: Long, multiplier: BigDecimal) {
    if (parkedTime != null) throw SessionAlreadyParkedException(licensePlate)
    this.parkedTime = parkedAt; this.sector = sector
    this.spotId = spotId;       this.priceMultiplier = multiplier
}
```

A atomicidade que a CAS UPDATE dava de graça vem agora de `@Version`: cada `saveAndFlush` no service emite `UPDATE … WHERE id = ? AND version = ?` e, se duas transações tentarem a mesma transição, a perdedora ergue `ObjectOptimisticLockingFailureException`, que o service traduz em `SessionAlreadyParkedException`/`SessionAlreadyExitedException`/`SpotAlreadyOccupiedException`.

| Decisão | Justificativa |
|---|---|
| **`@Version` (optimistic) em `ParkingSession`, `Spot`, `Sector`** | Sem locks de banco no caminho feliz; corridas reais são raras (uma placa não passa duas vezes na cancela simultaneamente). Falha rápido e o service traduz em exceção de negócio. |
| **Pessimistic mantido em `sectorService.lockByName`** | A checagem de capacidade (`countOccupiedIn` vs `maxCapacity`) precisa serializar o setor: dois `parkVehicle` simultâneos no mesmo setor poderiam ambos ver "ocupação 9/10" e ocupar a vaga 10 — `SELECT … FOR UPDATE` no setor fecha essa janela. Não é CAS — é serialização legítima de recurso compartilhado. |
| **Domain services não impõem `@Transactional`** | A transação é aberta pelo feature service (`GarageService`/`RevenueService`); todos os calls aos domain services participam dela. Anotar `@Transactional` no domain service iria propagar `REQUIRED` redundante e poderia mascarar uso fora de TX. |
| **Hierarquia `DomainRuleViolation` vive em `domain/`** | É exceção de regra de domínio: indica que a operação não pôde prosseguir por causa de um invariante do domínio. `WebhookController` captura o sealed base e devolve 200 (política de callsite), mas o tipo pertence ao domínio e ganhou nome neutro depois de migrar de `garage/GarageExceptions.kt`. |

## 3. Tecnologias e decisões

| Decisão | Por quê |
|---|---|
| **Spring Boot 4 + Kotlin** | Pedido do desafio. Boot 4 modulariza auto-configs — daí `spring-boot-starter-flyway` é necessário (não basta `flyway-core`) |
| **JPA com `ddl-auto: validate`** | Hibernate **valida** que o schema bate com as entidades, mas **não modifica** o banco. Quem cria/altera é o Flyway |
| **Flyway** | Schema versionado em `db/migration/V{n}__desc.sql`. Reprodutível, auditável, fácil de revisar em PR |
| **Jackson com `SNAKE_CASE`** | DTOs em `camelCase` no Kotlin, JSON em `snake_case` no fio (`license_plate`, `entry_time`). Configurado uma vez em `application.yaml` |
| **`RestClient` (não `WebClient`)** | App é WebMVC (síncrono); `RestClient` é a API HTTP síncrona moderna do Spring 6+ |
| **Jackson 3 (`tools.jackson`)** | Default no Boot 4. Datas `java.time` já são serializadas como ISO sem config extra |
| **Sealed interface para eventos** | `WebhookEvent` + `JsonTypeInfo` discriminado por `event_type`. `when` exaustivo no service |
| **Webhook + REST na mesma porta (3003)** | Mais simples; o desafio não exige separação |
| **Docker Compose para MySQL + simulador** | Reprodutibilidade: avaliador roda `docker compose up` e a aplicação encontra tudo no lugar |

## 4. Princípios de código

### 4.1 Clean Code (resumo prático)

- **Nomes intencionais.** `priceMultiplier` é melhor que `pm` ou `mult`. `findFirstByLicensePlateAndExitTimeIsNull` documenta a query no nome.
- **Funções pequenas e focadas.** Uma função faz uma coisa. Se o nome precisar de "e", quebre.
- **ZERO comentários no código. Sem exceção.** Proibido `//`, `/* */` e KDoc dentro de arquivos `.kt` (e `.sql`, `.yaml` etc). Se sentir vontade de comentar, **renomeie** — variável, função, classe, pacote — até o comentário ficar redundante. Se o "porquê" for crítico e não couber no nome, vai pra **mensagem de commit** ou pros documentos em `docs/`, **nunca** no arquivo de código.
- **Nomes carregam o intent.** `WebhookService.handleEntry()` é melhor que um `// trata entrada`. `findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc` é melhor que um bloco explicando a query.
- **Funções autoexplicativas.** Se uma função precisa de explicação no topo, ela está fazendo coisa demais ou está mal nomeada — corrija a função, não documente.
- **Sem código morto.** Removido na hora — o git tem o histórico.
- **Erro como exceção, não como código de retorno.** Validação no boundary (controller/service de entrada), `IllegalStateException`/`IllegalArgumentException` para invariantes violadas.

### 4.2 SOLID (como aplicamos aqui)

| Princípio | Como se manifesta no projeto |
|---|---|
| **S — Single Responsibility** | `WebhookController` faz HTTP; `WebhookService` faz regra; `ParkingSessionRepository` faz persistência. Cada um muda por **uma** razão |
| **O — Open/Closed** | Sealed `WebhookEvent` + `when` exaustivo: novo evento = novo subtipo, compilador força tratar nos handlers |
| **L — Liskov** | Subtipos de `WebhookEvent` são intercambiáveis na assinatura `handle(WebhookEvent)` |
| **I — Interface Segregation** | Repositórios expõem **só** os finders usados. Não existe `findAll` se ninguém chama |
| **D — Dependency Inversion** | Services dependem de **interfaces** (`SectorRepository`, etc), não de implementações. Spring injeta a impl no construtor |

### 4.3 KISS, DRY, YAGNI

- **KISS**: solução mais simples que resolve. Webhook + REST na mesma porta, sem CQRS, sem event-sourcing — o desafio não pede.
- **DRY**: extrair só na **terceira** repetição. Duas linhas iguais é coincidência; três é padrão.
- **YAGNI**: não criar abstração para uso hipotético. Sem `BaseEntity` "porque um dia"; sem flag de feature "vai que precisa". Adiciona quando precisar.

### 4.4 "Nunca crie mais código que o necessário"

- Sem getter/setter manual em Kotlin — `var`/`val` já dão isso.
- Sem `@Builder` em DTOs — named/default args resolvem.
- Sem método de fábrica trivial — chame o construtor.
- Sem interface para serviço com **uma só** implementação.
- Sem mapper/DTO duplicando entidade JPA quando ela já serve à camada HTTP **e** o acoplamento é aceitável (decisão consciente, documentada em PR).
- Sem tratamento de erro defensivo para coisas que não acontecem (ex.: validar não-nulo o que o framework já garante).

## 5. Padrões idiomáticos de Kotlin

- **Imutabilidade primeiro**: `val` por padrão. `var` só onde JPA exige (entidades) ou quando muda mesmo.
- **Null safety**: nada de `!!`. Use `?.`, `?:`, `requireNotNull` no boundary.
- **Data classes** para DTOs e value objects. **Não** para entidades JPA (Hibernate precisa de classe aberta + setters; o plugin `kotlin-jpa` cuida do `noarg`).
- **Sealed types** para hierarquias fechadas (eventos, resultados). Combinam com `when` exaustivo.
- **Expressões em vez de statements** quando possível: `fun x() = …` em vez de `fun x() { return … }`.
- **Extension functions** para enriquecer tipos sem herdar — só quando agregam valor (não vire ginástica).
- **Scope functions com parcimônia**: `let`, `apply`, `also`, `run`, `with`. Usar **uma** delas com clareza > encadear quatro.
- **Coleções**: prefira operadores funcionais (`map`, `filter`, `groupBy`) a loops imperativos quando o intent é claro. Em hot path, meça.
- **`copy()` em vez de mutação** para data classes.
- **`object`** para singletons (sem boilerplate de DI quando não precisa de Spring).
- **Default args** em vez de overloads.
- **Não usar `companion object` como "static dump"**. Só constantes/factories que pertencem **ao tipo**.

### Spring + Kotlin

- **Injeção por construtor**, sempre. Sem `@Autowired` em campo.
- **`@ConfigurationProperties` em `data class`** com defaults — mais legível que `@Value` espalhado.
- **`@Transactional` no service**, não no controller.
- **`open` automático** via plugin `kotlin-spring` para classes anotadas com `@Component`/`@Configuration`/etc — não declarar `open` manualmente.
- **Entidades JPA** com `var` e construtores com defaults. O plugin `allOpen` (`jakarta.persistence.Entity`) já abre a classe.

## 6. Testes

### 6.1 Padrão de nomenclatura: `givenX_whenY_thenZ`

Todo teste unitário deve seguir o estilo **given / when / then** no nome **e** no corpo, deixando claro o cenário, a ação e o resultado esperado.

```kotlin
@Test
fun `given vehicle parked for 25 minutes when calculating fee then returns zero`() {
    // given
    val entry = Instant.parse("2025-01-01T12:00:00Z")
    val exit  = entry.plus(25, ChronoUnit.MINUTES)
    val sector = sector(basePrice = "10.00")

    // when
    val fee = pricingService.calculate(sector, entry, exit, multiplier = ONE)

    // then
    assertEquals(BigDecimal("0.00"), fee)
}

@Test
fun `given sector at 80 percent occupancy when entry then applies 25 percent surcharge`() {
    // given
    val sector = sector(maxCapacity = 10)
    repeat(8) { spotRepository.save(occupiedSpot(sector)) }

    // when
    val multiplier = pricingService.dynamicMultiplier(sector)

    // then
    assertEquals(BigDecimal("1.25"), multiplier)
}
```

**Regras de ouro:**

- Use **backticks** no nome para descrever em inglês legível.
- Estrutura interna sempre `// given`, `// when`, `// then` (mesmo que vazio).
- Um `assert` por bloco `then` quando possível. Múltiplos asserts só quando descrevem **uma** propriedade.
- **Nada** de `setUp` que esconde o `given`. Cada teste deve ler como uma história curta.

### 6.2 Pirâmide

| Nível | Ferramenta | O que cobre |
|---|---|---|
| **Unitário** | JUnit 5 + `kotlin-test` | Regras puras: pricing, lotação, formatação. Sem Spring, sem DB |
| **Slice** (opcional) | `@DataJpaTest`, `@WebMvcTest` | Camada isolada (repositório com H2; controller com mock service) |
| **Integração** (1–2) | `@SpringBootTest` + H2 | Fluxo ponta-a-ponta: webhook → DB → revenue |

### 6.3 Boas práticas de teste

- **Determinismo**: nada de `Instant.now()` no código sob teste — injete um `Clock`.
- **Sem `Thread.sleep`**: testes esperam estado, não tempo.
- **Builders/factories de teste** (`fun sector(...) = Sector(...)`) para reduzir ruído no `given`.
- **Não teste o framework**: não escreva teste que só valida que `@RestController` mapeia URL.
- **Nomes longos > nomes obscuros**. `given_no_open_session_when_exit_then_throws` é melhor que `testExitFails`.

## 7. Escopo de PR & commits

### Regra do escopo mínimo

- **PR pequeno e focado: um PR = uma feature (ou um fix).** Se está abrindo dois arquivos em pastas distintas "porque já estou aqui", **pare** — separe em outro PR.
- **Mexa o mínimo possível em outras features.** Trabalhe **dentro** do pacote da feature alvo. Se a mudança exige tocar `domain/`, `config/` ou outra feature, restrinja a edição ao **mínimo absolutamente necessário** pra fazer a nova funcionalidade funcionar — nada de "já que estou aqui, refatoro também".
- **Não mexa em libs, dependências, plugins ou build** no mesmo PR de uma feature. Bump de versão, upgrade de starter, ajuste de plugin Gradle = **PR próprio, sozinho**, sem código de aplicação misturado.
- **Não toque em código não relacionado.** Formatação alheia, rename oportunista, mover arquivos, "limpar imports" em outro pacote — tudo isso ou vira PR próprio, ou simplesmente **fica quieto**.
- Refatoração oportunista é a forma mais comum de inflar PR. Quando aparecer a tentação, anote a ideia e volte depois num PR dedicado.

### Como saber se o PR está grande demais

Se responde "sim" a qualquer:

- O diff atravessa **mais de uma feature** (`webhook` + `revenue`, p.ex.)?
- Estou alterando dependências/build **e** lógica da feature no mesmo commit?
- Existem mudanças de "limpeza" misturadas com a feature?
- Mais de ~300 linhas líquidas de diff?

→ **separe**. Extraia o que não é da feature pra outro PR.

### Commits & mensagens

- **Commits pequenos e atômicos**, mensagem no infinitivo: `add pricing service`, `fix dynamic multiplier rounding`.
- **PRs descritivos**: o que muda, **por quê**, como testar.
- **Migrations Flyway são imutáveis depois de mergeadas** — nova mudança = `V{n+1}__...sql`.

## 8. Checklist antes de abrir PR

- [ ] Compila (`./gradlew build`).
- [ ] Testes passam (`./gradlew test`).
- [ ] Não há código não usado (imports, variáveis, métodos privados).
- [ ] **Zero comentários** em arquivos de código (`//`, `/* */`, KDoc). Se houver vontade de comentar, renomeie até ficar redundante.
- [ ] **Diff restrito à feature** do PR — não mexer em outras features, libs, build ou código não relacionado sem necessidade explícita.
- [ ] Sem bump de dependência/plugin misturado com lógica de aplicação.
- [ ] Migrations novas em `V{n}__...sql`, **nunca** editar uma já mergeada.
- [ ] Nada de `println` esquecido — use `LoggerFactory`.
- [ ] DTO de request validado (`@Valid` + Jakarta Validation) quando aceita input externo.
