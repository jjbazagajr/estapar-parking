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
├── config/                        ← infra (RestClient, properties)
├── domain/                        ← entidades JPA + repositórios
├── simulator/                     ← cliente HTTP do simulador + bootstrap
├── webhook/                       ← endpoint POST + handlers de evento
└── revenue/                       ← endpoint GET + cálculo de faturamento
```

**Por que package-by-feature?**
- Mudanças num domínio ficam contidas num pacote.
- Reduz acoplamento entre features: `revenue` não precisa importar nada de `webhook`.
- `domain/` é a única fronteira compartilhada — o que é compartilhado fica explícito.

### Camadas

| Camada | Responsabilidade | Regras |
|---|---|---|
| **Controller** (`*Controller.kt`) | HTTP I/O: bind de DTO, validação, status code | **Nunca** contém regra de negócio. Só orquestra: recebe → chama service → devolve |
| **Service** (`*Service.kt`) | Regras de negócio + transação | `@Transactional` aqui (não no controller). Orquestra repositórios |
| **Domain** (entities + repos) | Persistência e modelo | Sem lógica HTTP. Repos enxutos: só os métodos efetivamente usados |
| **Config** | Beans, properties, integrações externas | Nada de regra de negócio |

### Fluxo de uma requisição (exemplo: `POST /webhook`)

```
HTTP request
    ▼
WebhookController.receive(WebhookEvent)        ← bind Jackson, sealed type
    ▼
WebhookService.handle(event)                   ← @Transactional, when sobre tipo
    ▼
EntryEvent  → handleEntry()                    ← regra: lotação, preço dinâmico
ParkedEvent → handleParked()                   ← regra: aloca vaga, occupied=true
ExitEvent   → handleExit()                     ← regra: tarifa, libera vaga
    ▼
ParkingSessionRepository / SpotRepository      ← persistência
    ▼
HTTP 200
```

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
