# Feature — `ENTRY`: registro de entrada de veículo

Plano de implementação do primeiro handler do webhook: registrar a entrada de um veículo na garagem, capturando o **multiplicador de preço dinâmico congelado** no momento da entrada.

Referências:

- Regras de negócio: [`docs/contexto.md`](../contexto.md)
- Arquitetura e convenções de código: [`docs/arquitetura.md`](../arquitetura.md)
- Contrato do simulador (payload de entrada): [`docs/garage-simulator.md`](../garage-simulator.md)

## 1. Resumo

Quando o simulador dispara `POST /webhook` com `event_type=ENTRY`:

1. Aplicação valida que a placa não tem sessão aberta.
2. Aplicação valida que a garagem inteira não está 100% cheia.
3. Aplicação calcula o **multiplicador de preço dinâmico** com base na **ocupação global** no momento da entrada.
4. Aplicação abre uma `ParkingSession` (sem `sector`, `spot_id` nem `exit_time`) com o multiplicador congelado.
5. Aplicação responde `200 OK` (sem corpo).

Erros conhecidos respondem **`409 Conflict`** com `{ "message": "..." }`, mapeado por `@ControllerAdvice` global.

## 2. Decisões de design

### D1 — Multiplicador no `ENTRY` sem saber o setor

O `ENTRY` traz apenas `license_plate` e `entry_time`. O setor só é decidido no `PARKED` (via `lat`/`lng`). O contexto fala em "lotação **do setor** no momento da entrada", mas como o setor é desconhecido nesse momento, foi definido:

> Usar **lotação global** da garagem (`ocupados_total / capacidade_total`).

Motivos: o `priceMultiplier` da `ParkingSession` é `NOT NULL` desde o `ENTRY`; deferir o cálculo pro `PARKED` exigiria afrouxar a coluna e contradiria "registrado no momento do ENTRY".

### D2 — Critério de rejeição por lotação 100%

> Rejeitar `ENTRY` se a garagem inteira está 100% (`countByOccupiedTrue >= count`).

Faz sentido físico: cancela só abre se cabe alguém em **algum** setor. Rejeitar por setor seria impossível agora (setor desconhecido).

### D3 — Janela de funcionamento (`open_hour`/`close_hour`)

> **Rejeitar `ENTRY` se _nenhum_ setor está aberto** no horário do `entry_time`.

Estacionamento real: a cancela só abre se há setor disponível pra estacionar. Aceitar entrada com toda a garagem fechada deixaria o veículo em limbo (entrou, mas não consegue parar no `PARKED`).

A validação é "fraca" no ENTRY (basta **um** setor estar aberto). A validação **específica** do setor alocado é refeita no `PARKED` — o setor escolhido pode ter regra mais restritiva que a janela global.

`null` em `open_hour`/`close_hour` é tratado como "sem restrição" (sempre aberto), seguindo o `contexto.md` que descreve esses campos como **opcionais**.

### D4 — `ENTRY` para placa que já tem sessão aberta

> Rejeitar com `409 Conflict` (`SessionAlreadyOpenException`).

Duplicação de `ENTRY` é sinal de inconsistência (replay, bug do simulador, race). Falhar alto é melhor que silenciar.

## 3. Faixas de multiplicador (já definidas em `contexto.md`)

| Ocupação global | Multiplicador |
|---|---|
| `< 25%` | **0,90** (−10%) |
| `< 50%` | **1,00** |
| `< 75%` | **1,10** (+10%) |
| `< 100%` | **1,25** (+25%) |
| `>= 100%` | rejeita (`GarageFullException`) |

Comparação por `<` estrito: `25.0%` cai em `1.00`, não em `0.90`. Usar `Double` para a razão; manter `BigDecimal` no campo.

## 4. Estrutura de pacotes

A implementação **deleta** `webhook/WebhookService.kt` (era um service "fantasma" que só fazia dispatch). O dispatch vai pro próprio controller; as regras de negócio passam pra um pacote `garage/` novo.

```
com.estapar.parking/
├── config/
│   ├── OpenApiConfig.kt                  (existente)
│   ├── SimulatorConfig.kt                (existente)
│   ├── SimulatorProperties.kt            (existente)
│   └── GlobalExceptionHandler.kt         NOVO — @ControllerAdvice global
├── domain/
│   ├── Sector.kt                         MODIFICADO — adiciona método de domínio isOpenAt(time)
│   ├── Spot.kt                            (inalterado)
│   ├── ParkingSession.kt                  (inalterado)
│   ├── SectorRepository.kt                (inalterado)
│   ├── SpotRepository.kt                 MODIFICADO — adiciona countByOccupiedTrue()
│   └── ParkingSessionRepository.kt        (inalterado)
├── garage/                                NOVO — núcleo de negócio
│   ├── GarageService.kt                  application service (registerEntry + futuros parkVehicle/closeSession)
│   └── GarageExceptions.kt               SessionAlreadyOpenException, GarageFullException, GarageClosedException
├── revenue/                               (inalterado)
├── simulator/                             (inalterado)
├── webhook/
│   ├── WebhookController.kt              MODIFICADO — dispatch direto pro GarageService
│   └── WebhookEvents.kt                  (inalterado)
└── EstaparParkingApplication.kt
```

### Por que esse layout

- **Webhook é transporte**, não domínio. O nome `WebhookService` descrevia o canal HTTP, não a regra. Remover.
- **`garage/` é o bounded context** das operações de estacionamento. Como o app vai crescer com `PARKED` e `EXIT`, todos pertencem ao mesmo serviço orquestrador.
- **`@ControllerAdvice` é cross-cutting** — global por natureza, captura exceções de qualquer controller. Por isso vai em `config/`, junto com `OpenApiConfig`/`SimulatorConfig`, em vez de morar em `webhook/`.
- **`SpotRepository.countByOccupiedTrue()`** é adicionado em `domain/` (consulta sobre o agregado existente).

### Posicionamento DDD

- `GarageService` é um **application service**: orquestra caso de uso, abre transação, usa repositórios.
- **Pricing** continua como função privada dentro do `GarageService` enquanto for usado em 1 lugar. Quando `previewPrice` ou `closeSession` precisarem da mesma faixa, extrair pra `Pricing.kt` (domain service puro).
- **`Sector.isOpenAt(time)` é o primeiro método de domínio** na entidade — justificado por **2 call sites já previstos**: validação fraca no ENTRY (qualquer setor aberto?) e validação específica no PARKED (o setor alocado está aberto?). Pergunta de negócio idêntica em dois lugares.
- Demais entidades (`Spot`, `ParkingSession`) permanecem anêmicas por enquanto. Métodos como `Spot.occupy()` / `ParkingSession.close(time, fee)` entram quando os outros handlers chegarem.

## 5. Detalhamento por arquivo

### `domain/SpotRepository.kt` (modificar)

Adicionar:

```kotlin
fun countByOccupiedTrue(): Long
```

`count()` já vem de `JpaRepository`.

### `domain/Sector.kt` (modificar)

Adicionar método de domínio:

```kotlin
fun isOpenAt(time: LocalTime): Boolean {
    val open = openHour ?: return true
    val close = closeHour ?: return true
    return time in open..close
}
```

Trata `null` em `open_hour`/`close_hour` como "sem restrição". Operador `in` no `ClosedRange<LocalTime>` é inclusivo nos dois lados (fronteira `23:59` ainda aceita).

### `garage/GarageExceptions.kt` (novo)

```kotlin
class SessionAlreadyOpenException(plate: String)
    : RuntimeException("Já existe sessão aberta para placa $plate")

class GarageFullException
    : RuntimeException("Garagem está com lotação máxima")

class GarageClosedException
    : RuntimeException("Garagem fechada no momento")
```

### `garage/GarageService.kt` (novo)

Responsabilidades:

- `registerEntry(plate: String, time: LocalDateTime)` — única operação implementada nesta feature.
- `parkVehicle(...)` / `closeSession(...)` — assinaturas criadas só no PR do `PARKED`/`EXIT`.

Ordem das validações:

1. **Garagem fechada** (regra global de horário) → `GarageClosedException`.
2. **Garagem cheia** (regra global de capacidade) → `GarageFullException`.
3. **Placa com sessão aberta** (regra específica do veículo) → `SessionAlreadyOpenException`.

Primeiro pergunta "a cancela deveria abrir agora pra qualquer um?", depois "esse veículo em específico tem direito?".

Esqueleto:

```kotlin
@Service
class GarageService(
    private val sessions: ParkingSessionRepository,
    private val spots: SpotRepository,
    private val sectors: SectorRepository,
) {

    @Transactional
    fun registerEntry(plate: String, time: LocalDateTime) {
        if (sectors.findAll().none { it.isOpenAt(time.toLocalTime()) }) {
            throw GarageClosedException()
        }
        val total = spots.count()
        val occupied = spots.countByOccupiedTrue()
        if (total == 0L || occupied >= total) throw GarageFullException()
        if (sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate) != null) {
            throw SessionAlreadyOpenException(plate)
        }

        val multiplier = priceMultiplierFor(occupied.toDouble() / total)
        sessions.save(
            ParkingSession(
                licensePlate = plate,
                entryTime = time.toInstant(ZoneOffset.UTC),
                priceMultiplier = multiplier,
            )
        )
    }

    private fun priceMultiplierFor(occupancy: Double): BigDecimal = when {
        occupancy < 0.25 -> EMPTY
        occupancy < 0.50 -> NORMAL
        occupancy < 0.75 -> HIGH
        else             -> PEAK
    }

    private companion object {
        val EMPTY  = BigDecimal("0.90")
        val NORMAL = BigDecimal("1.00")
        val HIGH   = BigDecimal("1.10")
        val PEAK   = BigDecimal("1.25")
    }
}
```

Sobre carregar todos os setores via `findAll()`: a garagem tem poucos setores (2 no simulador atual, dezenas no pior caso). Não justifica query custom de "conta setores abertos agora" — KISS.

### `webhook/WebhookController.kt` (modificar)

```kotlin
@RestController
@RequestMapping("/webhook")
@Tag(name = "Webhook", description = "Eventos do simulador: ENTRY, PARKED e EXIT")
class WebhookController(
    private val garage: GarageService,
) {

    @PostMapping
    @Operation(summary = "Recebe evento do simulador e atualiza estado da garagem")
    fun receive(@RequestBody event: WebhookEvent): ResponseEntity<Void> {
        when (event) {
            is EntryEvent  -> garage.registerEntry(event.licensePlate, event.entryTime)
            is ParkedEvent -> { /* implementado em feature seguinte */ }
            is ExitEvent   -> { /* implementado em feature seguinte */ }
        }
        return ResponseEntity.ok().build()
    }
}
```

O `when` aqui é **roteamento por tipo de evento** (responsabilidade de transporte) — não regra de negócio.

### `config/GlobalExceptionHandler.kt` (novo)

```kotlin
@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(SessionAlreadyOpenException::class)
    fun handleSessionOpen(ex: SessionAlreadyOpenException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message ?: ""))

    @ExceptionHandler(GarageFullException::class)
    fun handleGarageFull(ex: GarageFullException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message ?: ""))

    @ExceptionHandler(GarageClosedException::class)
    fun handleGarageClosed(ex: GarageClosedException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message ?: ""))
}

data class ErrorResponse(val message: String)
```

### `webhook/WebhookService.kt` — **deletar**

## 6. Testes

`src/test/kotlin/com/estapar/parking/garage/GarageServiceTest.kt` (novo). Estilo `given_when_then` com Mockito puro (mesmo padrão de `GarageBootstrapTest`).

Cenários:

- `given garagem com 20 porcento ocupacao when register entry entao salva sessao com multiplicador 0_90`
- `given garagem com 49 porcento ocupacao when register entry entao salva sessao com multiplicador 1_00`
- `given garagem com 74 porcento ocupacao when register entry entao salva sessao com multiplicador 1_10`
- `given garagem com 99 porcento ocupacao when register entry entao salva sessao com multiplicador 1_25`
- `given garagem vazia when register entry entao salva sessao com multiplicador 0_90`
- `given garagem cheia when register entry entao lanca GarageFullException`
- `given garagem sem spots cadastrados when register entry entao lanca GarageFullException`
- `given placa com sessao aberta when register entry entao lanca SessionAlreadyOpenException`
- `given todos setores fechados no horario when register entry entao lanca GarageClosedException`
- `given pelo menos um setor aberto no horario when register entry entao aceita`
- `given setor sem open hour e close hour when register entry entao trata como sempre aberto`

Cobertura adicional pro método de domínio `Sector.isOpenAt` (teste de unidade isolado, pacote `domain/`):

- `given sector com janela 08 as 18 quando consulta isOpenAt em 12 entao retorna true`
- `given sector com janela 08 as 18 quando consulta isOpenAt em 07_59 entao retorna false`
- `given sector com janela 08 as 18 quando consulta isOpenAt na fronteira 08_00 entao retorna true`
- `given sector com janela 08 as 18 quando consulta isOpenAt na fronteira 18_00 entao retorna true`
- `given sector sem open hour e close hour quando consulta isOpenAt em qualquer horario entao retorna true`

**Teste de integração** (`@SpringBootTest` ou `@WebMvcTest` para verificar o `@ControllerAdvice` → 409 fim-a-fim) **não** vai ser criado nesse PR sem confirmação explícita — segue convenção do projeto.

## 7. Riscos e pegadinhas

- **Faixa estrita `<`**: ocupação `= 0.25` cai em `1.00`, não `0.90`. Coberto por teste de fronteira.
- **`total == 0`** (garagem sem spots cadastrados): divisão por zero. Tratado como `GarageFullException` (sem vagas disponíveis no momento).
- **Time zone**: o simulador envia `LocalDateTime` sem offset. Assumir **UTC**. Fácil de trocar se a especificação mudar.
- **Concorrência**: `countByOccupiedTrue` + `count` não são atômicos com o `save`. Em produção real seria race; pra avaliação, `@Transactional` simples basta (volume baixo, simulador serial).
- **Janela overnight (`close_hour < open_hour`, ex.: 22:00–06:00)**: **fora de escopo** desta feature. Os dados do simulador usam apenas intervalos normais (`close > open`). Se a especificação mudar, `Sector.isOpenAt` ganha lógica adicional sem afetar o contrato externo.

## 8. Fora de escopo deste PR

- Handler `PARKED` (alocação de spot/sector, marcar `occupied=true`, capturar `parked_time` na sessão).
- Handler `EXIT` (calcular tarifa, marcar `exit_time`/`amount_charged`, liberar spot).
- Validação de `open_hour`/`close_hour` por setor (vai no `PARKED`).
- Cálculo de receita real em `RevenueService` (separado).
- Teste de integração do `@ControllerAdvice`.

## 9. Checklist de execução

1. Apagar `webhook/WebhookService.kt`.
2. Adicionar método `isOpenAt(time: LocalTime)` em `domain/Sector.kt`.
3. Adicionar `countByOccupiedTrue()` em `domain/SpotRepository.kt`.
4. Criar `garage/GarageExceptions.kt` com `SessionAlreadyOpenException`, `GarageFullException`, `GarageClosedException`.
5. Criar `garage/GarageService.kt` com `registerEntry` (injeta `SectorRepository`, `SpotRepository`, `ParkingSessionRepository`).
6. Modificar `webhook/WebhookController.kt` (dispatch direto, injeção de `GarageService`).
7. Criar `config/GlobalExceptionHandler.kt` com `ErrorResponse` e 3 handlers.
8. Criar `domain/SectorTest.kt` com 5 casos do `isOpenAt`.
9. Criar `garage/GarageServiceTest.kt` com 11 cenários.
10. `./gradlew build` verde.
11. Reiniciar `bootRun` e observar log de `ENTRY` chegando do simulador real (com `sectors` na garagem real cobrindo o horário, deve persistir sessões).
