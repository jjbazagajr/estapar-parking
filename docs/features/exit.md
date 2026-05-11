# Feature — `EXIT`: registro de saída e cálculo da tarifa

Plano de implementação do terceiro e último handler do webhook: fechar a sessão de um veículo, calcular a tarifa devida e liberar a vaga.

Referências:

- Regras de negócio: [`docs/contexto.md`](../contexto.md)
- Arquitetura e convenções de código: [`docs/arquitetura.md`](../arquitetura.md)
- Contrato do simulador (payload): [`docs/garage-simulator.md`](../garage-simulator.md)
- Features anteriores: [`docs/features/entry.md`](./entry.md), [`docs/features/parked.md`](./parked.md)

## 1. Resumo

Quando o simulador dispara `POST /webhook` com `event_type=EXIT`:

1. Aplicação localiza a **sessão aberta** da placa (criada no `ENTRY`, estacionada no `PARKED`).
2. Aplicação valida que a sessão está **estacionada** (tem `spotId` e `sector`).
3. Aplicação carrega o **spot** e o **setor** vinculados (para `basePrice`).
4. Aplicação calcula a **duração** (`exit_time − entry_time`).
5. Aplicação valida que a duração **não é negativa**.
6. Aplicação calcula a **tarifa** (faixa de 30 min grátis, arredondamento para cima em horas, multiplicador congelado).
7. Aplicação marca `Spot.occupied = false`.
8. Aplicação atualiza a sessão com `exitTime` e `amountCharged`.
9. Aplicação responde `200 OK` (sem corpo).

Erros conhecidos de negócio respondem **`409 Conflict`** com `{ "message": "..." }`. Erros internos de integridade (setor ausente, spot ausente, duração negativa) caem como **`500 Internal Server Error`** via tratamento default do Spring Boot.

## 2. Decisões de design

### D1 — Âncora temporal da duração = `entry_time` (não `parked_time`)

A regra "primeiros 30 minutos: grátis" pode ser ancorada em `entry_time` (cancela) ou `parked_time` (sensor da vaga). Decisão:

> A duração que define cortesia e tarifa é `exit_time − entry_time`.

Motivos:
- `entry_time` é o momento em que o veículo entra no sistema (cancela). Cliente já tem seu "tempo" rodando.
- `parked_time − entry_time` depende de quanto demora pra achar vaga — fora do controle do cliente. Cobrar pelo tempo de busca interna seria injusto.
- Convenção mais comum em estacionamentos reais.

Reversível: se o cenário do simulador exigir o contrário, troca de uma linha em `processExit`.

### D2 — `EXIT` sem `PARKED` (sessão sem `spotId`/`sector`) → `SessionNotParkedException` (409)

Cenário: `ENTRY` aconteceu, `PARKED` falhou (ex.: vaga já ocupada, setor fechado), simulador eventualmente envia `EXIT`. Sessão fica com `spotId=null`.

> Rejeitar com `SessionNotParkedException` (409). Não inventar tarifa para sessão que nunca foi estacionada.

Trade-off: a sessão fica aberta no banco indefinidamente — placa não consegue dar novo `ENTRY` (`SessionAlreadyOpenException`). Aceito porque:
- O simulador **observado** (vide validação do PARKED) só envia `EXIT` para placas que estacionaram com sucesso. O caso é teórico/defensivo.
- Cycle violation é melhor sinalizar alto que mascarar. Consistente com o D4 do PARKED (`SessionAlreadyParkedException`).
- "Cleanup" de sessões zumbis é fora de escopo (job separado ou intervenção manual).

### D3 — `open_hour`/`close_hour` **não** são validados no `EXIT`

> Cancela de saída **sempre** abre, independente da janela do setor.

Motivos:
- Aprisionar veículo em garagem fechada é cenário operacional inválido (segurança, regulamentação).
- A janela do setor regula **entrada/estacionamento** (cancela só deixa entrar / vaga só aceita carro em horário). Saída é desbloqueada por design.
- Nada no `contexto.md` proíbe `EXIT` fora da janela.

### D4 — Cálculo da tarifa: comparação em segundos, arredondamento em horas inteiras

A regra do `contexto.md`:

- ≤ 30 minutos → grátis.
- > 30 minutos → tarifa fixa por hora, **incluindo** a primeira hora; arredonda **para cima** em horas inteiras.

Decisão de implementação:

> Comparar duração em **segundos** (não minutos truncados); arredondar horas via `Math.ceilDiv(seconds, 3600)`.

```kotlin
val seconds = Duration.between(entry, exit).seconds
if (seconds <= GRACE_PERIOD_SECONDS) {        // 1800 s = 30 min
    return BigDecimal.ZERO.setScale(2)
}
val hours = Math.ceilDiv(seconds, 3600L).toBigDecimal()
return basePrice.multiply(hours).multiply(multiplier).setScale(2, HALF_EVEN)
```

`Duration.toMinutes()` truncaria sub-minuto silenciosamente (30 min 30 s viraria 30 min e cairia em "grátis", que é errado). Segundos preservam a fronteira exata. `Math.ceilDiv` (Java 18+, projeto em 21) é o idiomático sem flutuante.

### D5 — Precisão `BigDecimal`: `setScale(2, HALF_EVEN)` no resultado final

`basePrice` é `DECIMAL(10,2)`, `priceMultiplier` é `DECIMAL(4,3)`, `amountCharged` é `DECIMAL(10,2)`.

A multiplicação `40.50 × 0.900 = 36.4500` ganha escala. Aplicar `setScale(2, RoundingMode.HALF_EVEN)` no resultado final padroniza a coluna e usa banker's rounding (consistente com Spring/Hibernate). `BigDecimal.ZERO.setScale(2)` garante que o "grátis" também grave `0.00` (e não `0`).

### D6 — Reuso de `SessionNotFoundException` (criada no `PARKED`)

Placa sem sessão aberta no `EXIT` reusa a mesma exceção de `parkVehicle`. Mesma semântica ("não há sessão aberta para essa placa"), mesma mensagem, mesmo handler 409.

### D7 — Mutações com dirty checking (sem `save` explícito)

Idêntico ao padrão do `parkVehicle`: `session` e `spot` vêm de `find...` (managed), `@Transactional` cobre o flush no commit. Sem `sessions.save(...)` ou `spots.save(...)`.

### D8 — Time zone UTC (consistente com `ENTRY` e `PARKED`)

`exit_time` chega como `LocalDateTime` (sem zona). Converter via `time.toInstant(ZoneOffset.UTC)`, mesma convenção dos outros handlers. Coluna `TIMESTAMP(3)` + `serverTimezone=UTC` + `hibernate.jdbc.time_zone=UTC` mantém o invariante.

### D9 — Setor da sessão ausente → `SectorMissingException` (500)

Mesma decisão de integridade do `PARKED` (D7 lá). `sectors.findByName(session.sector!!)` retornando `null` é violação de FK (`fk_sessions_sector`). Reusa `SectorMissingException` (criada no PARKED), sem handler dedicado → 500 default. Cliente não tem ação possível.

### D10 — Spot da sessão ausente → `error()` (500)

`spots.findById(session.spotId!!)` retornando `null` é violação de FK (`fk_sessions_spot`). Não justifica criar uma `SpotMissingException` separada porque a probabilidade é zero sob integridade referencial; usar `error("Spot ${spotId} referenciado pela sessão não existe")` → `IllegalStateException` → 500 default. Mesma filosofia de D9 mas sem a frequência que justificaria classe própria (`Sector` já tinha uso prévio no PARKED).

### D11 — Duração negativa (`exit_time < entry_time`) → `error()` (500)

Bug interno: relógio do simulador atrasado em relação à app, replay com timestamps invertidos, etc. Não é regra de negócio violável pelo cliente — é integridade temporal. `error("exit_time anterior a entry_time para placa $plate")` → 500. Igual filosofia do D7 do PARKED.

### D12 — Ordem das validações

```
1. Sessão aberta existe?         (regra do veículo)
2. Sessão tem spotId + sector?    (cycle: PARKED ocorreu)
3. Duração ≥ 0?                  (integridade temporal)
4. Spot existe?                  (integridade referencial)
5. Setor existe?                 (integridade referencial — para basePrice)
6. Calcular tarifa.
7. Persistir mutações.
```

Validações de cycle/negócio antes das de integridade referencial; cálculo só depois de tudo validado.

## 3. Cálculo da tarifa (referência rápida)

| Duração (seg.) | Horas (ceil) | Fee  |
|---|---|---|
| `0 – 1800` (≤ 30 min) | — | `0.00` |
| `1801 – 3600` (30:01 – 1h) | `1` | `1 × basePrice × multiplier` |
| `3601 – 7200` (1:00:01 – 2h) | `2` | `2 × basePrice × multiplier` |
| `7201 – 10800` (2:00:01 – 3h) | `3` | `3 × basePrice × multiplier` |
| ... | `ceil(s/3600)` | `hours × basePrice × multiplier` |

Exemplos numéricos (setor A: `basePrice=40.50`, setor B: `basePrice=4.10`):

| Duração | Multiplicador | Fee |
|---|---|---|
| 30 min (A) | qualquer | `0.00` |
| 30 min 1 s (A) | `0.900` | `1 × 40.50 × 0.900 = 36.45` |
| 1 h (A) | `1.000` | `40.50` |
| 1 h 5 min (A) | `1.250` | `2 × 40.50 × 1.250 = 101.25` |
| 2 h (B) | `1.250` | `2 × 4.10 × 1.250 = 10.25` |
| 25 h (A) | `1.100` | `25 × 40.50 × 1.100 = 1113.75` |

Fronteira estrita: `duração = 1800 s` → grátis. `duração = 1801 s` → 1 h cobrada.

## 4. Estrutura de pacotes

```
com.estapar.parking/
├── config/
│   └── GlobalExceptionHandler.kt       MODIFICADO — +1 handler (SessionNotParked)
├── garage/
│   ├── GarageService.kt                MODIFICADO — +processExit
│   └── GarageExceptions.kt             MODIFICADO — +SessionNotParkedException
├── webhook/
│   └── WebhookController.kt            MODIFICADO — dispatch ExitEvent
└── revenue/                            (inalterado — TODO em RevenueService permanece;
                                         /revenue continuará retornando 0,00 até a PR
                                         específica do endpoint)
```

### Por que esse layout

- `GarageService` continua sendo o **application service único** das operações de cancela/vaga, agora com os três handlers (`registerEntry`, `parkVehicle`, `processExit`). Sem dividir em `EntryService`/`ParkingService`/`ExitService` — eles compartilham os mesmos repositórios e a coesão é alta.
- O **endpoint `/revenue`** já tem o método de repo (`sumRevenue`) pronto, mas `RevenueService.revenueFor` ainda retorna `BigDecimal.ZERO` (TODO no código). Após esta PR, o **dado existe** (`amount_charged` é preenchido), mas o endpoint só passa a refletir quando alguém remover o TODO. **Esta PR não toca em `revenue/`** — escopo mínimo. Vai como follow-up.
- Nenhum método novo no `domain/`: tudo que precisamos (`findFirstBy...`, `findById`, `findByName`) já existe.

## 5. Detalhamento por arquivo

### `garage/GarageExceptions.kt` (modificar)

Adicionar uma única exceção (Sector/Spot ausente reusam as do PARKED / `error()`):

```kotlin
class SessionNotParkedException(plate: String) :
    RuntimeException("Sessão da placa $plate ainda não estacionou")
```

### `garage/GarageService.kt` (modificar)

Adicionar `processExit` e o helper privado de cálculo:

```kotlin
@Transactional
fun processExit(plate: String, time: LocalDateTime) {
    val session = sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)
        ?: throw SessionNotFoundException(plate)
    val spotId = session.spotId ?: throw SessionNotParkedException(plate)
    val sectorName = session.sector ?: throw SessionNotParkedException(plate)

    val exitInstant = time.toInstant(ZoneOffset.UTC)
    val seconds = Duration.between(session.entryTime, exitInstant).seconds
    if (seconds < 0) error("exit_time anterior a entry_time para placa $plate")

    val spot = spots.findById(spotId).orElse(null)
        ?: error("Spot $spotId referenciado pela sessão $plate não existe")
    val sector = sectors.findByName(sectorName)
        ?: throw SectorMissingException(sectorName)

    val amount = calculateFee(seconds, sector.basePrice, session.priceMultiplier)

    spot.occupied = false
    session.exitTime = exitInstant
    session.amountCharged = amount
}

private fun calculateFee(seconds: Long, basePrice: BigDecimal, multiplier: BigDecimal): BigDecimal {
    if (seconds <= GRACE_PERIOD_SECONDS) return BigDecimal.ZERO.setScale(2)
    val hours = Math.ceilDiv(seconds, SECONDS_PER_HOUR).toBigDecimal()
    return basePrice.multiply(hours).multiply(multiplier).setScale(2, RoundingMode.HALF_EVEN)
}

private companion object {
    val EMPTY = BigDecimal("0.90")
    val NORMAL = BigDecimal("1.00")
    val HIGH = BigDecimal("1.10")
    val PEAK = BigDecimal("1.25")
    const val GRACE_PERIOD_SECONDS = 30L * 60
    const val SECONDS_PER_HOUR = 3600L
}
```

Observações:

- A ordem dos checks segue **D12**: cycle (sessão/spot/sector da sessão) antes de integridade do banco; cálculo só depois de tudo validado.
- `Math.ceilDiv(Long, Long)` é Java 18+ (projeto em 21). Sem flutuante, sem off-by-one.
- `calculateFee` permanece privado dentro do `GarageService`. Vira `Pricing.kt` (domain service) **quando** outro caller precisar (ex.: endpoint de "preview" de tarifa) — regra da 3ª repetição.
- O bloco `if (seconds < 0)` vem **antes** do `findById` do spot: integridade temporal é mais barata de validar que ida ao banco.

### `webhook/WebhookController.kt` (modificar)

```kotlin
when (event) {
    is EntryEvent  -> garage.registerEntry(event.licensePlate, event.entryTime)
    is ParkedEvent -> garage.parkVehicle(event.licensePlate, event.lat, event.lng)
    is ExitEvent   -> garage.processExit(event.licensePlate, event.exitTime)
}
```

### `config/GlobalExceptionHandler.kt` (modificar)

Adicionar **1 handler** novo:

```kotlin
@ExceptionHandler(SessionNotParkedException::class)
fun handleSessionNotParked(ex: SessionNotParkedException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))
```

Total após a PR: **9 handlers** + 1 exceção interna sem handler (`SectorMissingException`) + 2 caminhos `error()` (spot ausente, duração negativa) → 500 default. A pressão por extrair `GarageBusinessException` sealed parent + 1 handler único cresce, mas continua **fora desta PR**.

## 6. Testes

`src/test/kotlin/com/estapar/parking/garage/GarageServiceTest.kt` (modificar — adiciona ao arquivo existente). Mesmo padrão `given_when_then` + Mockito + `Clock.fixed`.

### Reuso do setup atual

`Clock.fixed("2026-05-10T12:00:00Z")` permanece, mas para `processExit` o `clock` é **opcional** — o método usa apenas o `time` do payload (igual `registerEntry`). Os cenários montam `entryTime` e `exitTime` LocalDateTime relativos.

Helpers novos sugeridos:

```kotlin
private fun parkedSession(
    entry: LocalDateTime,
    spotId: Long = 7,
    sectorName: String = "A",
    multiplier: BigDecimal = BigDecimal("1.000"),
) = ParkingSession(
    licensePlate = plate,
    entryTime = entry.toInstant(ZoneOffset.UTC),
    priceMultiplier = multiplier,
    sector = sectorName,
    spotId = spotId,
    parkedTime = entry.toInstant(ZoneOffset.UTC),
)

private fun sectorWithBasePrice(name: String, basePrice: BigDecimal) = Sector(
    name = name,
    basePrice = basePrice,
    maxCapacity = 10,
    openHour = null,
    closeHour = null,
)
```

### Padrão de asserção

Idêntico ao `parkVehicle`: mutações no objeto retornado pelo mock; **não** usar `verify(save)`.

```kotlin
val spot = spotAt(id = 7, sector = "A", occupied = true)
val session = parkedSession(entry = ...)
`when`(sessions.findFirstByLicensePlate...).thenReturn(session)
`when`(spots.findById(7)).thenReturn(Optional.of(spot))
`when`(sectors.findByName("A")).thenReturn(sectorWithBasePrice("A", BigDecimal("40.50")))

service.processExit(plate, exitTime)

assertEquals(false, spot.occupied)
assertEquals(BigDecimal("36.45"), session.amountCharged)
assertNotNull(session.exitTime)
```

### Cenários do `processExit`

**Validações de fluxo / regras de negócio**:

- `given placa sem sessao aberta when process exit then lanca SessionNotFoundException`
- `given sessao sem spot vinculado when process exit then lanca SessionNotParkedException`
- `given sessao sem sector vinculado when process exit then lanca SessionNotParkedException` *(defensivo — estado inalcançável pelo `parkVehicle` real, que seta `sector` e `spotId` atomicamente; cobre o `?:` do segundo unboxing)*

**Erros de integridade (500)**:

- `given setor da sessao removido do banco when process exit then lanca SectorMissingException`
- `given spot da sessao removido do banco when process exit then lanca IllegalStateException`
- `given exit antes de entry when process exit then lanca IllegalStateException`

**Faixas de duração (fronteiras)**:

- `given duracao 0 min when process exit then amount 0_00 e libera spot`
- `given duracao exata 30 min when process exit then amount 0_00 e libera spot`
- `given duracao 30 min e 1 segundo when process exit then amount cobra 1 hora`
- `given duracao 31 min when process exit then amount cobra 1 hora`
- `given duracao 59 min when process exit then amount cobra 1 hora`
- `given duracao exata 60 min when process exit then amount cobra 1 hora`
- `given duracao 60 min e 1 segundo when process exit then amount cobra 2 horas`
- `given duracao 1h 5min when process exit then amount cobra 2 horas`
- `given duracao exata 2h when process exit then amount cobra 2 horas`

**Cálculo numérico (multiplicador × basePrice)**:

- `given basePrice 40_50 multiplicador 0_900 duracao 1h when process exit then amount 36_45`
- `given basePrice 4_10 multiplicador 1_250 duracao 2h when process exit then amount 10_25`
- `given basePrice 40_50 multiplicador 1_100 duracao 25h when process exit then amount 1113_75` (sanity de duração longa)

**Mutações**:

- `given exit valido when process exit then libera spot definido pela sessao` (`spot.occupied=false`)
- `given exit valido when process exit then atualiza sessao com exit_time e amount_charged`

Total: **20 cenários** novos do `processExit`. Volume alto, mas as fronteiras de tarifa exigem exaustividade — qualquer drift no `Math.ceilDiv` ou no comparador `≤` quebra um teste específico.

### Impacto nos testes do `registerEntry` e `parkVehicle`

**Nenhum**. O construtor do `GarageService` não muda; nenhum cenário existente tem suposição que conflite. Setup é reutilizado.

### Teste de integração (`@SpringBootTest`)

Confirmado em escopo. Justificativa: o `EXIT` é o nó que **fecha o ciclo crítico** ENTRY→PARKED→EXIT — qualquer drift no contrato HTTP, no `@Transactional`, no `@ControllerAdvice` ou na persistência só aparece em integração. Mocks de repositório cobrem cálculo, não a stack.

**Setup minimalista**:

Novo arquivo `src/test/kotlin/com/estapar/parking/webhook/WebhookFlowIntegrationTest.kt`. Configurar H2 in-memory **via `@TestPropertySource`** na própria classe — sem criar `src/test/resources/application.yaml` (evita afetar `EstaparParkingApplicationTests.contextLoads()`):

```kotlin
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "spring.datasource.url=jdbc:h2:mem:exit-it;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "estapar.simulator.bootstrap-enabled=false",
])
class WebhookFlowIntegrationTest { ... }
```

Trade-offs explícitos:
- **Override de `hibernate.dialect=H2Dialect`**: pegadinha real. `application.yaml:17` declara `hibernate.dialect=MySQLDialect` **explicitamente**. Sem override, Hibernate ignora o JDBC URL e segue emitindo SQL MySQL contra o H2 → falha. O Spring Boot 4 já auto-detecta o dialeto pelo URL, e há um `WARN` em produção sugerindo remover essa propriedade do `application.yaml` — mas mexer nela é refactor fora desta PR (consistente com "PR mínimo"). Override no teste é a saída cirúrgica.
- **H2 com `MODE=MySQL`** em vez de Testcontainers: rapidez (~2s startup) prevalece. Perdemos validação da migration Flyway (que usa `ENGINE=InnoDB`, sintaxe MySQL-específica); ganhamos teste auto-contido sem Docker. Aceito — a migration já é exercitada pelo `contextLoads()` quando o MySQL real está de pé.
- **`ddl-auto=create-drop`**: Hibernate cria o schema a partir das entidades JPA. Schema gerado **não** é byte-a-byte igual ao da Flyway (faltam FKs definidas só no SQL — `fk_spots_sector`, `fk_sessions_sector`, `fk_sessions_spot` — e índices manuais), mas é semanticamente equivalente para o que o teste verifica. Os testes pré-populam dados na ordem correta (sectors → spots → sessions), então a ausência de FK no schema de teste não importa.
- **`bootstrap-enabled=false`**: pula a chamada ao simulador. Os dados de garagem são populados manualmente via `SectorRepository`/`SpotRepository` no `@BeforeEach` do teste.

**Cenários cobertos** (escopo enxuto, dois testes):

1. `given garagem com setor A e uma vaga when fluxo completo ENTRY PARKED EXIT then session fechada com amount_charged correto e spot liberado`
   - Pré-popula 1 setor A (basePrice `40.50`) + 1 spot.
   - `POST /webhook ENTRY` (`entry_time=12:00:00`) → assert `200`, session existe com `priceMultiplier=0.90`.
   - `POST /webhook PARKED` (lat/lng do spot) → assert `200`, `spot.occupied=true`, `session.spotId/sector/parkedTime` populados.
   - `POST /webhook EXIT` (`exit_time=13:00:00`) → assert `200`, `session.exitTime=13:00 UTC`, `session.amountCharged=36.45`, `spot.occupied=false`.
   - Cobre: dispatch HTTP, `@RequestBody` polimórfico Jackson, `@Transactional` fim-a-fim, `@ControllerAdvice` não dispara em fluxo feliz, persistência real em H2.

2. `given placa com ENTRY sem PARKED when EXIT then retorna 409 e mantem session aberta`
   - Pré-popula setor A.
   - `POST /webhook ENTRY`.
   - `POST /webhook EXIT` (10 min depois) → assert HTTP `409`, corpo `{"message":"...ainda não estacionou"}`, `session.exitTime` continua null.
   - Cobre: `SessionNotParkedException` chega no `@ControllerAdvice` corretamente, mensagem do `ErrorResponse` é serializada, transação faz rollback.

Cenários **não** cobertos por integração (continuam apenas em unit): fronteiras de duração, multiplicador, integridade (sector/spot ausente). A unidade cobre exaustivamente; integração só valida a *plumbing*.

## 7. Riscos e pegadinhas

- **Truncamento de `toMinutes()`**: pegadinha-mor desta feature. Implementação ingênua usaria `Duration.toMinutes() <= 30`, que aceita "30 min 59 s" como grátis. Decisão D4 força segundos; teste de fronteira `30 min e 1 segundo` cobre.

- **`BigDecimal` ganha escala em `multiply`**: `40.50 × 0.900 = 36.4500` (escala 4). Sem `setScale(2, HALF_EVEN)`, o JPA gravaria com mais casas e a coluna `DECIMAL(10,2)` truncaria silenciosamente, dependendo do dialeto. Sempre normalizar o resultado final.

- **`BigDecimal.ZERO.setScale(2)`**: sem o `setScale`, MySQL armazena `0` mas a igualdade `BigDecimal("0.00").equals(BigDecimal.ZERO)` é **false** (escala diferente). Testes que comparam com `BigDecimal("0.00")` quebram se esquecermos.

- **`HALF_EVEN` vs `HALF_UP`**: D5 escolhe banker's rounding (statisticamente sem viés). A convenção comercial mais comum é `HALF_UP` ("5 sempre arredonda pra cima"). Para os dados atuais do simulador (`basePrice` e `priceMultiplier` resultam em multiplicações exatas — `36.4500`, `101.2500`, `10.2500`), as duas regras produzem o mesmo resultado. A diferença só aparece em casos como `0.005 → 0.00` (HALF_EVEN) vs `0.01` (HALF_UP). Se o domínio mudar para tarifas com terceiro decimal exatamente 5, revisitar.

- **Race condition `EXIT` concorrente para mesma placa**: dois `EXIT` simultâneos. Ambos leem a sessão com `exitTime=null`, calculam mesma tarifa (entrada/saída idênticas), gravam. Resultado final convergente (idempotente). Spot é liberado uma vez (ou duas — `occupied=false` é idempotente). **Sem proteção adicional**, aceito no escopo.

- **EXIT → ENTRY consecutivos no fluxo serial do simulador**: o simulador pode emitir ENTRY de uma nova placa logo após EXIT. `exitTime IS NOT NULL` na sessão antiga garante que `findFirst...IsNull` da nova ENTRY retorna `null` → cria nova sessão. Sem cruzamento. (Importante: "transação" aqui é o ciclo lógico do simulador, **não** a transação Spring — cada webhook é uma request HTTP independente com sua própria `@Transactional`.)

- **Janela do setor: cancela sempre abre na saída (D3)**: regra explícita aqui. Se um dia alguém quiser cobrar penalidade por "saiu fora do horário do setor", isso é regra **separada** — usa `duration_limit_minutes` (hoje ignorado, ver §8).

- **`/revenue` continua retornando 0,00**: efeito colateral importante de comunicar. `amount_charged` passa a ser preenchido por esta PR, mas o endpoint `/revenue` (em `RevenueService.revenueFor`) tem um TODO e ainda retorna zero. Quem chamar `/revenue` esperando ver receita real vai se confundir. **Não consertar nesta PR** (escopo mínimo) — abrir como follow-up.

- **Setor da sessão é o setor do `PARKED`, não o setor atual do spot**: `session.sector` foi gravado no PARKED como `spot.sector`. Se algum dia o spot trocar de setor entre PARKED e EXIT (bootstrap mid-run, etc.), a tarifa usará o setor **do momento do PARKED**, consistente com "preço congelado no ENTRY". Bom.

- **Duração extremamente longa (semanas/meses)**: `Long` aguenta com folga. `DECIMAL(10,2)` permite até ~99M — cabe pra 30 dias × 24h × 40.50 × 1.25 = ~36k. Sem overflow.

- **Mutação de entidade sem `save` explícito**: mesma observação do `parkVehicle` — depende de `@Transactional` + dirty checking.

## 8. Fora de escopo deste PR

- **Endpoint `/revenue` real**: remover o TODO de `RevenueService.revenueFor` e plugar `ParkingSessionRepository.sumRevenue`. Mecânica trivial, mas é PR separada.
- **`duration_limit_minutes`** por setor: hoje carregado no banco mas não aplicado. Sem regra definida em `contexto.md` (a doc diz "regra a confirmar conforme cenário do simulador"). Sem dado para cobrar penalidade, deixar quieto.
- **Job de "cleanup" de sessões zumbis** (placas com `ENTRY` mas sem `PARKED` que pegaram `EXIT` rejeitado): job batch separado ou intervenção manual.
- **Testcontainers / `@SpringBootTest` contra MySQL real**: integração roda em H2 modo MySQL (decisão em §6). Validar migration Flyway de ponta-a-ponta exige Testcontainers — fora desta PR.
- **Extração de `Pricing.kt`** (domain service puro com `calculateFee` + `priceMultiplierFor`): regra da 3ª repetição ainda não bate — só dois call sites (um no `registerEntry`, outro no `processExit`), mas em métodos distintos com responsabilidades distintas. Adiar.
- **Refactor para `GarageBusinessException` sealed parent + handler único**: pressão real, mas mistura escopo. Próxima PR dedicada.
- **Lock pessimista ou `@Version`** para race conditions: aceito como risco no escopo da avaliação.
- **Time zone configurável**: continua UTC end-to-end.

## 9. Checklist de execução

1. Adicionar `SessionNotParkedException` em `garage/GarageExceptions.kt`.
2. Modificar `garage/GarageService.kt`:
   - implementar `processExit(plate, time)` na ordem de **D12**;
   - implementar `calculateFee(seconds, basePrice, multiplier)` privado;
   - adicionar `GRACE_PERIOD_SECONDS` e `SECONDS_PER_HOUR` no companion.
3. Modificar `webhook/WebhookController.kt` — dispatch do `ExitEvent` para `garage.processExit`.
4. Modificar `config/GlobalExceptionHandler.kt` — adicionar **1** `@ExceptionHandler` (`SessionNotParkedException` → `CONFLICT`).
5. Modificar `garage/GarageServiceTest.kt`:
   - adicionar helpers `parkedSession(...)` e `sectorWithBasePrice(...)`;
   - adicionar 20 cenários do `processExit` cobrindo regras, integridade, fronteiras de duração e cálculo numérico;
   - manter padrão "mutação direta" (sem `verify(save)`).
6. Criar `webhook/WebhookFlowIntegrationTest.kt`:
   - `@SpringBootTest(MOCK)` + `@AutoConfigureMockMvc` + `@TestPropertySource` (H2 modo MySQL, Flyway desligado, bootstrap desligado);
   - 2 cenários: ciclo completo (happy path) e EXIT sem PARKED (409 fim-a-fim).
7. `./gradlew build` verde (unit + integração).
8. Subir `docker compose up -d` + `./gradlew bootRun`. Esperar ciclo completo do simulador (`ENTRY → PARKED → EXIT`). Verificar no banco:
   - `parking_sessions.exit_time` populado;
   - `parking_sessions.amount_charged` calculado;
   - `spots.occupied = false` para a vaga que era da sessão;
   - logs do simulador mostram `Exit successful for plate: X` + `Current revenue per sector: {...}`.
9. (Opcional) Confirmar que `/revenue` continua retornando `0,00` — esperado, abrir issue/PR para o TODO de `RevenueService`.
