# Feature — `PARKED`: registro de estacionamento em vaga específica

Plano de implementação do segundo handler do webhook: vincular uma vaga física (`spot`) e setor à sessão aberta da placa, marcando a vaga como ocupada.

Referências:

- Regras de negócio: [`docs/contexto.md`](../contexto.md)
- Arquitetura e convenções de código: [`docs/arquitetura.md`](../arquitetura.md)
- Contrato do simulador (payload): [`docs/garage-simulator.md`](../garage-simulator.md)
- Feature anterior: [`docs/features/entry.md`](./entry.md)

## 1. Resumo

Quando o simulador dispara `POST /webhook` com `event_type=PARKED`:

1. Aplicação localiza a **sessão aberta** da placa (criada no `ENTRY`).
2. Aplicação valida que a sessão **ainda não tem vaga vinculada** (não houve `PARKED` duplicado).
3. Aplicação localiza a **vaga** pelas coordenadas (`lat`/`lng`).
4. Aplicação valida que a vaga **não está ocupada**.
5. Aplicação valida que o **setor da vaga está aberto** no instante do evento.
6. Aplicação marca `Spot.occupied = true`.
7. Aplicação atualiza a sessão com `sector`, `spotId` e `parkedTime`.
8. Aplicação responde `200 OK` (sem corpo).

Erros conhecidos de negócio respondem **`409 Conflict`** com `{ "message": "..." }`, mapeados pelo `@ControllerAdvice` global já existente. Erros internos de integridade (ex.: setor referenciado pelo spot inexistente) caem como **`500 Internal Server Error`** via tratamento default do Spring Boot — intencional (ver D7).

## 2. Decisões de design

### D1 — Validação **por setor** no PARKED (não global)

No `ENTRY` a validação de horário é "fraca" (basta **um** setor aberto na garagem). No `PARKED`, **conhecemos o setor exato** porque a vaga foi escolhida. Logo:

> A janela de funcionamento checada no `PARKED` é a do **setor da vaga**, não a da garagem inteira.

Motivo: setor `B` pode estar fechado às 03:00 mesmo com setor `A` aberto 24h. Aceitar `PARKED` em `B` às 03:00 viola a janela do setor.

### D2 — Origem do `parked_time`

O DTO `ParkedEvent` (`webhook/WebhookEvents.kt`) traz apenas `license_plate`, `lat` e `lng` — **sem timestamp** — e usa `@JsonIgnoreProperties(ignoreUnknown = true)`. Decisão atual:

> `parkedTime = clock.instant()` (relógio do servidor, UTC).

`Clock` é injetado no `GarageService` para permitir testes determinísticos. Bean em `config/`.

**Ação de verificação antes de codar** (ver §8, passo 0): logar uma vez o `@RequestBody String` cru do `PARKED` em produção (ou via `curl` simulado) para confirmar que o simulador **não** envia `parked_time` no payload. O `@JsonIgnoreProperties` mascararia silenciosamente um campo presente. Se vier no payload, D2 inverte: estender o DTO e usar o timestamp autoritativo, não o clock do servidor.

### D3 — Capacidade do setor **não** é revalidada no `PARKED`

> Se a vaga retornada por `(lat, lng)` está com `occupied=false`, por definição o setor dela tem ao menos 1 vaga livre — capacidade `< 100%`.

Checar "setor com 100%" novamente seria redundante com a checagem `spot.occupied`. KISS.

### D4 — Idempotência: `PARKED` duplicado **falha alto**

> Segundo `PARKED` para a mesma placa (sessão já com `spotId != null`) → `SessionAlreadyParkedException`.

Mesmo princípio do `ENTRY`: duplicação é sinal de replay/race/bug do simulador. Falhar loud é melhor que aceitar e sobrescrever vaga silenciosamente (deixaria a vaga anterior marcada como ocupada para sempre).

### D5 — Quatro exceções de negócio + uma de integridade interna

Mantém o estilo do PR do `ENTRY` (cada exceção de negócio tem `@ExceptionHandler` próprio). A lista cresce de 3 → 8 handlers quase idênticos — aceito porque:

- O escopo deste PR é **só `PARKED`**; introduzir uma `GarageBusinessException` base e refatorar os 3 handlers existentes seria mistura de feature + refactor (proibido pelo `CLAUDE.md`).
- A extração pode entrar como **PR separado** depois (sealed parent + 1 handler).

Adicionalmente, **`SectorMissingException` não recebe handler dedicado** — ver D7.

### D6 — Ordem das validações

```
1. Sessão aberta existe?               (regra do veículo — barato, falha rápido)
2. Sessão já tem spot vinculado?        (idempotência — barato)
3. Vaga existe nas coordenadas?         (consistência do payload)
4. Vaga está livre?                     (estado físico)
5. Setor da vaga está aberto agora?     (janela do setor)
6. Persistir mutação.
```

Mais barato e mais específico primeiro. O setor só é consultado depois que a vaga já foi confirmada (evita query desnecessária quando a vaga não existe).

### D7 — Setor ausente vs. setor fechado: exceções distintas, semânticas distintas

Duas situações **muito** diferentes podem aparecer ao consultar o setor da vaga:

- **`SectorClosedException`** → setor existe, mas `isOpenAt(now) == false`. Regra de negócio legítima. Cliente "errou" no sentido funcional. **HTTP 409** via handler dedicado.
- **`SectorMissingException`** → `sectors.findByName(spot.sector)` retornou `null`. Isso só acontece se a FK `fk_spots_sector` (`V1__init.sql:17`) foi violada — bug interno (migração corrompida, dado alterado por fora do app, race no bootstrap). Cliente não tem culpa nem ação possível. **HTTP 500** via tratamento default do Spring Boot.

A `SectorMissingException` herda de `IllegalStateException` (sinaliza intenção: "estado interno inválido"), tem nome explícito (aparece nomeada em stack trace + testável via `assertFailsWith`) e **não tem `@ExceptionHandler`** registrado. O Spring Boot devolve 500 com corpo default (`{timestamp, status, error, path}` sem `message`, porque `server.error.include-message` não está ativado), o stack trace cai no log em nível ERROR, e o `@Transactional` da `parkVehicle` faz rollback automático. Esse é o comportamento desejado: bugs de integridade devem ser barulhentos e diferenciáveis em monitoria, não mascarados como 409 de negócio.

### D8 — Time zone: decisão explícita por UTC

Os campos `open_hour`/`close_hour` do setor são `LocalTime` (sem zona). `application.yaml` configura `serverTimezone=UTC` no datasource e `hibernate.jdbc.time_zone=UTC`. O `entry_time` é gravado com `ZoneOffset.UTC` no `registerEntry`. Para manter consistência:

> No `parkVehicle`, comparar `sector.isOpenAt(now.atZone(ZoneOffset.UTC).toLocalTime())` — mesma convenção do `ENTRY`.

Limitação consciente: os valores recebidos do simulador (`00:00–23:59`, `08:00–23:59`) são wall-clock locais do simulador, não UTC. Hoje funciona porque a janela do A absorve qualquer horário e a do B é larga; uma janela estreita (ex.: `02:00–06:00`) em produção fora de UTC quebraria. Migrar para zona local exigiria também alterar `registerEntry` e a propriedade de conversão — fora do escopo. Marcado como item conhecido em §6.

## 3. Estrutura de pacotes

```
com.estapar.parking/
├── config/
│   ├── ClockConfig.kt                  NOVO — @Bean Clock = Clock.systemUTC()
│   └── GlobalExceptionHandler.kt       MODIFICADO — +5 handlers (CONFLICT)
├── domain/
│   └── (inalterado — métodos já existem: findFirstByLatAndLng, findByName, isOpenAt)
├── garage/
│   ├── GarageService.kt                MODIFICADO — +parkVehicle, injeta Clock
│   └── GarageExceptions.kt             MODIFICADO — +SessionNotFoundException,
│                                                    +SessionAlreadyParkedException,
│                                                    +SpotNotFoundException,
│                                                    +SpotAlreadyOccupiedException,
│                                                    +SectorClosedException,
│                                                    +SectorMissingException (sem handler)
├── webhook/
│   └── WebhookController.kt            MODIFICADO — dispatch para parkVehicle
└── ...
```

### Por que esse layout

- `Clock` como bean em `config/` segue o padrão do projeto (`OpenApiConfig`, `SimulatorConfig`, `GlobalExceptionHandler` já moram lá). Bean separado evita poluir `SimulatorConfig` com responsabilidade não relacionada.
- Repositórios já têm os métodos necessários — `SpotRepository.findFirstByLatAndLng` e `SectorRepository.findByName` foram adicionados em refactors anteriores. **Não criar nada novo em `domain/`** nesta feature.
- `Sector.isOpenAt(LocalTime)` já existe — reusado tal e qual.

## 4. Detalhamento por arquivo

### `config/ClockConfig.kt` (novo)

```kotlin
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
```

UTC por consistência com `entryTime` (também armazenado em UTC via `ZoneOffset.UTC` no `registerEntry`) — ver D8.

### `garage/GarageExceptions.kt` (modificar)

Adicionar:

```kotlin
class SessionNotFoundException(plate: String) :
    RuntimeException("Não existe sessão aberta para placa $plate")

class SessionAlreadyParkedException(plate: String) :
    RuntimeException("Sessão da placa $plate já está estacionada")

class SpotNotFoundException(lat: Double, lng: Double) :
    RuntimeException("Não existe vaga nas coordenadas ($lat, $lng)")

class SpotAlreadyOccupiedException(lat: Double, lng: Double) :
    RuntimeException("Vaga em ($lat, $lng) já está ocupada")

class SectorClosedException(sector: String) :
    RuntimeException("Setor $sector fechado no momento")

class SectorMissingException(sector: String) :
    IllegalStateException("Setor $sector referenciado pelo spot não existe")
```

Observação: `SpotAlreadyOccupiedException` recebe `lat/lng` (e não `spot.id`) para não vazar identificador interno do banco no corpo do 409 — a mensagem fala na linguagem do cliente do webhook.

`SectorMissingException` herda de `IllegalStateException` por D7 e **não recebe handler** em `GlobalExceptionHandler`.

### `garage/GarageService.kt` (modificar)

Injetar `Clock` e implementar `parkVehicle`:

```kotlin
@Service
class GarageService(
    private val sessions: ParkingSessionRepository,
    private val spots: SpotRepository,
    private val sectors: SectorRepository,
    private val clock: Clock,
) {

    // registerEntry permanece como está (sem usar clock — entry_time vem do payload).

    @Transactional
    fun parkVehicle(plate: String, lat: Double, lng: Double) {
        val session = sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)
            ?: throw SessionNotFoundException(plate)
        if (session.spotId != null) throw SessionAlreadyParkedException(plate)

        val spot = spots.findFirstByLatAndLng(lat, lng)
            ?: throw SpotNotFoundException(lat, lng)
        if (spot.occupied) throw SpotAlreadyOccupiedException(lat, lng)

        val now = clock.instant()
        val sector = sectors.findByName(spot.sector)
            ?: throw SectorMissingException(spot.sector)
        if (!sector.isOpenAt(now.atZone(ZoneOffset.UTC).toLocalTime())) {
            throw SectorClosedException(sector.name)
        }

        spot.occupied = true
        session.sector = spot.sector
        session.spotId = spot.id
        session.parkedTime = now
    }

    // priceMultiplierFor e companion permanecem.
}
```

Observações:

- `spots.save(spot)` e `sessions.save(session)` são **omitidos** — entidades vêm do `find...` (managed), e o `@Transactional` + dirty checking do JPA garante o flush no commit. Mais idiomático e enxuto.
- A ausência do setor (`findByName == null`) é mapeada para `SectorMissingException` (500), **não** para `SectorClosedException` (409): semânticas diferentes, status HTTP diferente, como discutido em D7.
- `now` é calculado **antes** da resolução do setor para não capturar timestamp distinto em fluxos de retry (se houver delay de query entre eles).

### `webhook/WebhookController.kt` (modificar)

```kotlin
when (event) {
    is EntryEvent  -> garage.registerEntry(event.licensePlate, event.entryTime)
    is ParkedEvent -> garage.parkVehicle(event.licensePlate, event.lat, event.lng)
    is ExitEvent   -> Unit
}
```

### `config/GlobalExceptionHandler.kt` (modificar)

Adicionar **5 handlers** no mesmo estilo dos existentes (CONFLICT + `ErrorResponse`). `SectorMissingException` **não** entra aqui — cai no default do Spring Boot (500).

```kotlin
@ExceptionHandler(SessionNotFoundException::class)
fun handleSessionNotFound(ex: SessionNotFoundException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

@ExceptionHandler(SessionAlreadyParkedException::class)
fun handleSessionAlreadyParked(ex: SessionAlreadyParkedException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

@ExceptionHandler(SpotNotFoundException::class)
fun handleSpotNotFound(ex: SpotNotFoundException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

@ExceptionHandler(SpotAlreadyOccupiedException::class)
fun handleSpotAlreadyOccupied(ex: SpotAlreadyOccupiedException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

@ExceptionHandler(SectorClosedException::class)
fun handleSectorClosed(ex: SectorClosedException): ResponseEntity<ErrorResponse> =
    ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))
```

Total: 5 handlers novos. Mais 1 exceção interna (`SectorMissingException`) sem handler, intencional (D7).

## 5. Testes

`src/test/kotlin/com/estapar/parking/garage/GarageServiceTest.kt` (modificar — adiciona ao arquivo existente). Mesmo padrão `given_when_then` com Mockito.

### Mudança de padrão em relação ao `registerEntry`

Os testes do `registerEntry` usam `ArgumentCaptor` + `verify(sessions).save(...)` porque a session é nova (transient → persistida pela primeira vez).

Os testes do `parkVehicle` precisam usar **padrão diferente**: a session e o spot já são entidades managed retornadas pelo mock dos repositórios; o service apenas **muta atributos** confiando em dirty checking — não chama `save`. Logo, as asserções devem ser feitas no objeto que o próprio teste injetou no mock:

```kotlin
val spot = Spot(id = 7, sector = "A", lat = -23.5, lng = -46.6)
val session = ParkingSession(licensePlate = plate, entryTime = ..., priceMultiplier = BigDecimal("1.00"))
`when`(spots.findFirstByLatAndLng(-23.5, -46.6)).thenReturn(spot)
`when`(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)).thenReturn(session)
`when`(sectors.findByName("A")).thenReturn(sectorOpenBetween("A", null, null))

service.parkVehicle(plate, -23.5, -46.6)

assertEquals(true, spot.occupied)
assertEquals("A", session.sector)
assertEquals(7L, session.spotId)
assertNotNull(session.parkedTime)
```

Nada de `verify(...).save(...)`. Quem implementar pode tentar replicar o padrão de `registerEntry` por inércia e estranhar a falha — ressaltar isso na review.

### Estratégia para variar o cenário sem mexer no `Clock`

`Clock` fica fixo no setup:

```kotlin
private val clock: Clock = Clock.fixed(Instant.parse("2026-05-10T12:00:00Z"), ZoneOffset.UTC)
private val service = GarageService(sessions, spots, sectors, clock)
```

Para testar "setor fechado agora" sem reinstanciar o service, construir o **setor** com janela fora do instante fixo (ex.: open `14:00`, close `18:00` para um clock fixo às `12:00`). O cenário varia pelo `Sector`, o relógio permanece estável — isso evita ter de criar `Clock`s diferentes por teste e mantém o setup uniforme.

### Cenários do `parkVehicle`

- `given placa sem sessao aberta when park vehicle then lanca SessionNotFoundException`
- `given sessao ja tem spot vinculado when park vehicle then lanca SessionAlreadyParkedException`
- `given coordenadas sem vaga when park vehicle then lanca SpotNotFoundException`
- `given vaga ja ocupada when park vehicle then lanca SpotAlreadyOccupiedException`
- `given setor da vaga fechado no horario when park vehicle then lanca SectorClosedException`
- `given setor referenciado pelo spot ausente when park vehicle then lanca SectorMissingException`
- `given sessao valida e vaga livre when park vehicle then marca spot como occupied`
- `given sessao valida e vaga livre when park vehicle then atualiza sessao com sector spotId e parkedTime`
- `given setor sem open hour e close hour when park vehicle then aceita`

### Impacto nos testes do `registerEntry`

Construtor do `GarageService` ganha o parâmetro `clock`. O setup do `GarageServiceTest` precisa passá-lo — **uma linha**, sem mudar nenhuma asserção dos cenários existentes. `registerEntry` continua usando `time` do payload, não `clock`.

**Teste de integração** (`@SpringBootTest` end-to-end de `PARKED` → DB → `Spot.occupied=true`) **não** será criado sem confirmação explícita do usuário, conforme convenção do projeto.

## 6. Riscos e pegadinhas

- **Concorrência `findFirstByLatAndLng` + `spot.occupied = true`**: dois `PARKED` concorrentes para a mesma vaga podem ambos ler `occupied=false`. A migration tem apenas `INDEX idx_spots_latlng`, **sem unique constraint** — o banco não defende. Race aceito para escopo de avaliação (volume baixo, simulador serial). Fix natural seria `SELECT ... FOR UPDATE`, `@Version` no spot, ou `UNIQUE(lat, lng)` + `INSERT ... ON DUPLICATE KEY` — fora do escopo desta PR.

- **Sem unique constraint em sessões abertas**: idem para `parking_sessions`. Não há `UNIQUE(license_plate)` filtrado por `exit_time IS NULL`. Duas `ENTRY` concorrentes da mesma placa podem criar duas sessions abertas; o `parkVehicle` então pega a "mais recente por entry_time" e ignora a outra silenciosamente. MySQL não tem unique parcial nativo — exigiria coluna gerada ou trigger. Risco herdado do ENTRY, não introduzido aqui.

- **Bootstrap-mid-simulation reescreve `spots.occupied`**: `GarageBootstrap.kt:92` aplica `spot.occupied = dto.occupied` no upsert. Se a app reiniciar com simulação em andamento, o bootstrap pode sobrescrever vagas que estavam corretamente marcadas `occupied=true` (com session aberta apontando para elas) para `occupied=false` — quebrando a invariante de que `spot.occupied=true ⇔ existe session aberta com aquele spotId`. Um `PARKED` futuro nessa vaga seria aceito, deixando duas sessions "estacionadas" no mesmo lugar. Correção real é no bootstrap (não reconciliar `occupied` sem checar sessions). Fora do escopo desta PR; o `parkVehicle` herda essa fragilidade.

- **Time zone fixa em UTC (D8)**: setor `open_hour`/`close_hour` é comparado como `LocalTime` em UTC. Funciona para os dados atuais do simulador (`00:00–23:59`, `08:00–23:59`); janelas estreitas fora de UTC quebrariam. Mudar exigiria coordenar com o `registerEntry`.

- **Setor da vaga ausente cai em 500 (D7)**: comportamento intencional. Se o stack trace aparecer no log de produção, é bug real de integridade — não normalizar como erro de negócio.

- **Mutação de entidade sem `save` explícito**: depende de `@Transactional` + dirty checking do JPA. Funciona porque `parkVehicle` está em transação e as entidades vêm de `find...`. Se algum dia o método sair de transação, o write some silenciosamente.

- **Coordenadas com precisão diferente**: o simulador envia `lat`/`lng` com a mesma precisão do `GET /garage` (mesma origem). A coluna é `DOUBLE` na migration → igualdade `Double` exata via Spring Data funciona (confirmado pelo bootstrap, que já usa o mesmo padrão). Se a comparação um dia falhar por precisão, considerar arredondar ou trocar para `DECIMAL`.

## 7. Fora de escopo deste PR

- Handler `EXIT` (calcular tarifa, marcar `exit_time`/`amount_charged`, liberar spot).
- `duration_limit_minutes` por setor (será aplicado no `EXIT` ou em job de expiração — não impacta `PARKED`).
- Extração de uma `GarageBusinessException` sealed parent + handler único (refactor justificado, mas em PR separado).
- Teste de integração `@SpringBootTest` ponta-a-ponta para `PARKED`.
- Reconciliação do bootstrap com sessions abertas.
- Adicionar `UNIQUE` constraints em `spots(lat, lng)` ou em sessions abertas.
- Mudança de time zone do `Clock` para horário local.
- Tratamento de race condition na vaga (lock pessimista/otimista).

## 8. Checklist de execução

0. **Verificar payload real do `PARKED`** (D2): logar uma vez `@RequestBody String` no `WebhookController` (ou usar `curl`/`tcpdump`) para confirmar que o simulador realmente **não** envia `parked_time`. Se enviar, parar e rever D2 antes de codar — provavelmente o DTO deve ser estendido e o `Clock` removido.
1. Criar `config/ClockConfig.kt` com bean `Clock.systemUTC()`.
2. Adicionar 6 exceções em `garage/GarageExceptions.kt` (`SessionNotFoundException`, `SessionAlreadyParkedException`, `SpotNotFoundException`, `SpotAlreadyOccupiedException`, `SectorClosedException`, `SectorMissingException`). A última herda de `IllegalStateException`.
3. Modificar `garage/GarageService.kt`:
   - injetar `Clock`;
   - implementar `parkVehicle(plate, lat, lng)` com a ordem de validações de **D6**;
   - mapear `findByName == null` para `SectorMissingException` (D7).
4. Modificar `webhook/WebhookController.kt` — dispatch do `ParkedEvent` para `garage.parkVehicle`.
5. Modificar `config/GlobalExceptionHandler.kt` — adicionar **5** `@ExceptionHandler` (todos `CONFLICT` + `ErrorResponse`). **Não** registrar handler para `SectorMissingException`.
6. Modificar `garage/GarageServiceTest.kt`:
   - adicionar `Clock.fixed` no setup e passá-lo ao construtor;
   - adicionar 9 cenários do `parkVehicle` usando o padrão "mutação direta" (asserções no objeto retornado pelo mock, sem `verify(save)`);
   - variar cenário de "fechado" via `Sector`, não via `Clock`.
7. `./gradlew build` verde.
8. Subir `docker compose up -d` + `./gradlew bootRun` e observar `ENTRY → PARKED` reais do simulador persistindo `sector`/`spot_id`/`parked_time` na sessão e `occupied=true` no `spots`.
