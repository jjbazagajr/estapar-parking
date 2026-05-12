# Feature — `GET /revenue`: faturamento acumulado por setor e data

Plano de implementação do endpoint de consulta de receita: dado um setor e uma data, retornar a soma dos `amount_charged` das sessões com `exit_time` no dia.

Referências:

- Regras de negócio: [`docs/contexto.md`](../contexto.md)
- Arquitetura e convenções de código: [`docs/arquitetura.md`](../arquitetura.md)
- Features anteriores: [`docs/features/entry.md`](./entry.md), [`docs/features/parked.md`](./parked.md), [`docs/features/exit.md`](./exit.md)

## 1. Resumo

Quando o cliente chama `GET /revenue` com `{ "date": "2025-01-01", "sector": "A" }`:

1. Aplicação valida que o **setor existe**.
2. Aplicação calcula a **janela do dia em UTC** (`start = date 00:00:00 UTC`, `end = start + 1 dia`).
3. Aplicação soma `amount_charged` das sessões com `sector = ?` e `exit_time` em `[start, end)`.
4. Aplicação responde `200 OK` com `{ amount, currency: "BRL", timestamp: clock.instant() }`.

Setor inexistente responde **`404 Not Found`** via `SectorNotFoundException` anotada com `@ResponseStatus`.

## 2. Decisões de design

### D1 — Setor inexistente → `404 Not Found` (não 200 com zero)

Retornar `200 + 0.00` mascararia typos de cliente (ex.: `sector=AA` em vez de `A`). 404 dá sinal explícito.

Implementação: exceção **própria** da camada `revenue/`, anotada com `@ResponseStatus(HttpStatus.NOT_FOUND)`. Spring MVC mapeia direto, sem necessidade de `@ControllerAdvice` (que aliás não existe no projeto).

### D2 — Por que **não** reusar `SectorMissingException`

Esta foi a pegadinha principal do plano. `garage/GarageExceptions.kt` define `SectorMissingException` herdando de `WebhookEventIgnored` — uma `sealed class` que o `WebhookController` captura para **silenciar erros e devolver 200**. Reusar essa exceção no `/revenue` faria:

- O contrato do webhook (200 sempre) não é afetado, porque o `/revenue` não passa pelo `WebhookController`. Mas:
- A semântica fica errada: "ignorar evento" não é o que se espera de um endpoint de consulta. Cliente precisa do 404.
- Acoplamento entre `revenue/` e a hierarquia de exceções do `webhook/garage`.

Por isso: exceção **nova** em `revenue/`, independente da hierarquia `WebhookEventIgnored`.

### D3 — Janela do dia em **UTC**

`entry_time` e `exit_time` são gravados como `Instant` derivados via `LocalDateTime.toInstant(ZoneOffset.UTC)` (ver D8 do `exit.md`). Para manter o invariante:

> `start = date.atStartOfDay().toInstant(UTC)`, `end = date.plusDays(1).atStartOfDay().toInstant(UTC)`.

Comparação `[start, end)` (semiaberta): sessão com `exit_time` exatamente `00:00:00.000Z` do dia consultado **conta**; sessão com `exit_time` exatamente `00:00:00.000Z` do dia seguinte **não conta**. Operadores `>=` e `<` na query JPQL já existente refletem isso.

Se a regra mudar para "fuso de São Paulo", basta trocar `ZoneOffset.UTC` por `ZoneId.of("America/Sao_Paulo")` no service — sem afetar a query.

### D4 — `timestamp` = `clock.instant()`

O `contexto.md` mostra `"timestamp": "2025-01-01T12:00:00.000Z"` como exemplo, mas não define semântica. Decisão:

> `timestamp` é o instante de geração da resposta (`clock.instant()`).

Motivos:
- Coerente com o que um response REST genérico carrega (similar a `Date` header).
- Já é o que o stub atual faz; nenhuma mudança no contrato.
- Alternativas (início do dia consultado, `now` do servidor sem clock) ou são redundantes (date já está na request) ou quebram testes determinísticos.

### D5 — Remover o `Clock = Clock.systemUTC()` *default* do construtor de `RevenueService`

O scaffolding atual tem:

```kotlin
class RevenueService(
    private val sessions: ParkingSessionRepository,
    private val clock: Clock = Clock.systemUTC(),
)
```

O default impede injeção determinística em produção (Spring resolve o bean configurado em `ClockConfig`, mas o default mascara o erro caso o bean suma). Remover força a injeção explícita — alinhado com `GarageService`.

### D6 — Reuso do índice existente

`V1__init.sql` já cria `idx_sessions_revenue (sector, exit_time)`. A query `sumRevenue` filtra por exatamente essas duas colunas e atende ao índice sem mudanças. **Sem migration nesta PR.**

### D7 — Sem `@ControllerAdvice` global

Hoje o projeto não tem `@ControllerAdvice` (ver `entry.md` §5 — foi previsto mas removido junto com o redesign do webhook para "200 sempre"). Adicionar agora seria *over-engineering*: uma única exceção, anotada com `@ResponseStatus`, basta. Se outras camadas REST precisarem de 4xx no futuro, aí sim vale a discussão.

### D8 — Sem `@Transactional(readOnly = true)` no service

A operação faz **uma** query agregada. `readOnly=true` daria hint para o Hibernate evitar dirty checking, mas o ganho é nulo aqui (não há entidade carregada para checar). KISS — adicionar só se métricas mostrarem custo.

### D9 — `setScale(2, HALF_EVEN)` no `amount`

A query JPQL já retorna `COALESCE(SUM(s.amountCharged), 0)`. `BigDecimal.ZERO` tem escala 0 — sem `setScale(2)`, a resposta seria `"amount": 0` em vez de `"amount": 0.00`. Padronizar a escala em 2 (HALF_EVEN, consistente com `PricingPolicy.feeFor`) preserva o formato do contrato.

## 3. Estrutura de pacotes

```
com.estapar.parking/
└── revenue/
    ├── RevenueController.kt    (inalterado)
    ├── RevenueDtos.kt          (inalterado)
    ├── RevenueExceptions.kt    NOVO — SectorNotFoundException
    └── RevenueService.kt       MODIFICADO — implementação real + injeção de SectorRepository
```

Nenhuma mudança em `domain/`, `garage/`, `webhook/`, `config/` ou migrations. PR isolado em `revenue/`.

## 4. Detalhamento por arquivo

### `revenue/RevenueExceptions.kt` (novo)

```kotlin
package com.estapar.parking.revenue

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.NOT_FOUND)
class SectorNotFoundException(sector: String) :
    RuntimeException("Setor $sector não existe")
```

Anotação `@ResponseStatus` faz o Spring MVC traduzir a exceção em `404` automaticamente, sem handler.

### `revenue/RevenueService.kt` (modificar)

```kotlin
package com.estapar.parking.revenue

import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.SectorRepository
import org.springframework.stereotype.Service
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

@Service
class RevenueService(
    private val sessions: ParkingSessionRepository,
    private val sectors: SectorRepository,
    private val clock: Clock,
) {

    fun revenueFor(date: LocalDate, sector: String): RevenueResponse {
        sectors.findByName(sector) ?: throw SectorNotFoundException(sector)

        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val amount = sessions.sumRevenue(sector, start, end).setScale(2, RoundingMode.HALF_EVEN)

        return RevenueResponse(
            amount = amount,
            currency = "BRL",
            timestamp = clock.instant(),
        )
    }
}
```

Observações:

- `sectors.findByName` é a única consulta extra — barata (UNIQUE em `sectors.name`).
- Sem `@Transactional`: uma única query de leitura; Spring abre transação por request padrão para chamadas JPA via repository já basta.
- `setScale(2, HALF_EVEN)` na linha do `amount` garante `"0.00"` em vez de `"0"` na serialização JSON.

### `revenue/RevenueController.kt` (inalterado)

Já segue o contrato (`@GetMapping` + `@RequestBody`). Sem mudanças.

### `revenue/RevenueDtos.kt` (inalterado)

Já tem `RevenueRequest(date, sector)` e `RevenueResponse(amount, currency, timestamp)`. Sem mudanças.

## 5. Testes

`src/test/kotlin/com/estapar/parking/revenue/RevenueServiceTest.kt` (novo). Padrão `given_when_then` + Mockito + `Clock.fixed`. Sem teste de controller (cobertura redundante). Teste de integração separado em arquivo próprio (ver §5.3).

### Helpers sugeridos

```kotlin
private val fixedInstant: Instant = Instant.parse("2026-05-11T12:00:00Z")
private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
private val sessions: ParkingSessionRepository = mock(...)
private val sectors: SectorRepository = mock(...)
private val service = RevenueService(sessions, sectors, clock)

private fun stubSectorExists(name: String) {
    `when`(sectors.findByName(name)).thenReturn(Sector(name = name, basePrice = ..., maxCapacity = 10, openHour = null, closeHour = null))
}

private fun stubSumRevenue(sector: String, start: Instant, end: Instant, sum: BigDecimal) {
    `when`(sessions.sumRevenue(sector, start, end)).thenReturn(sum)
}
```

### Cenários

**Validação de setor:**

- `given setor inexistente when revenueFor then lanca SectorNotFoundException e nao consulta sessions`

**Janela do dia (UTC):**

- `given data 2025-01-01 when revenueFor then consulta sumRevenue com start 2025-01-01T00 00 UTC e end 2025-01-02T00 00 UTC`
- *(verificado via ArgumentCaptor nos `start`/`end` passados)*

**Cálculo numérico:**

- `given setor sem sessoes encerradas no dia when revenueFor then retorna amount 0_00 e currency BRL`
- `given setor com sessoes encerradas no dia somando 123_45 when revenueFor then retorna amount 123_45`
- `given soma retornada com escala 4 (ex 123_4500) when revenueFor then normaliza para escala 2`

**Resposta:**

- `given clock fixo when revenueFor then timestamp e o instante do clock`
- `given setor valido when revenueFor then currency e BRL`

Total: **7 cenários**. Cobertura próxima de 100% das linhas do service.

### Por que sem `RevenueControllerTest`

O controller só faz `service.revenueFor(req.date, req.sector)`. Não há mapping não-trivial, não há `@Valid`, não há `@ControllerAdvice` próprio para o pacote — o `@ResponseStatus` é declarativo. Um teste de controller validaria apenas que o Spring faz dispatch (já garantido pelo framework). Adiar até existir lógica no controller.

### 5.3. Teste de integração (`RevenueFlowIntegrationTest`)

Decisão revisada com o usuário: incluir integração. Justificativa: validar fim-a-fim **(a)** o filtro pela janela `[start, end)` rodando contra um SQL real (H2 modo MySQL) e não contra a JPQL mockada; **(b)** a serialização da resposta (`amount` com escala 2 no JSON, `timestamp` em ISO-8601); **(c)** o mapeamento de `SectorNotFoundException` → 404 pelo `@ResponseStatus`.

`src/test/kotlin/com/estapar/parking/revenue/RevenueFlowIntegrationTest.kt`. Setup idêntico ao `WebhookFlowIntegrationTest` (H2 modo MySQL via `@TestPropertySource`, Flyway off, bootstrap off). Sessões são criadas direto via `ParkingSessionRepository` (não via webhook) para não acoplar `/revenue` ao ciclo `ENTRY → PARKED → EXIT`.

Assertions usam `JsonPath` (Jayway, já no `spring-boot-starter-test`) — evita dependência do `jackson-module-kotlin`, que **não está no classpath** do projeto.

Cenários (3):

- `given sessoes encerradas no dia em setores diferentes when GET revenue then soma apenas o setor e dia consultados` — popula 4 sessões cobrindo fronteiras `00:00:00Z` do dia consultado, `23:59:59Z` do mesmo dia, outro setor no mesmo dia, e `00:00:00Z` do dia seguinte. Confirma que apenas as duas primeiras entram no agregado.
- `given nenhuma sessao encerrada no dia consultado when GET revenue then retorna 0_00 com currency BRL` — sessão existe num dia adjacente; consulta retorna `0.00` (com escala 2) e currency BRL.
- `given setor inexistente when GET revenue then responde 404` — confirma o `@ResponseStatus(NOT_FOUND)` do `SectorNotFoundException` chega no cliente.

## 6. Riscos e pegadinhas

- **`@RequestBody` em `GET`**: o contrato do `contexto.md` usa `GET /revenue` com corpo JSON. É legal pelo HTTP/1.1 mas alguns proxies/curl-defaults removem corpo de GET. Esta PR **não muda** essa decisão — apenas honra o contrato existente. Se o cliente reclamar, ver se vale virar `POST /revenue/query` em PR separada.

- **Escala `0` vs `0.00`**: `BigDecimal.ZERO` tem escala 0. Sem `setScale(2)`, Jackson serializa como `0`, não `0.00`. O contrato no `contexto.md` mostra `0.00` — `setScale(2, HALF_EVEN)` cobre.

- **Time zone de `LocalDate`**: a request traz `"date": "2025-01-01"` sem fuso. A janela é calculada em UTC. Cliente em fuso diferente que pedir "minha receita de hoje" pode pegar resultados deslocados em até 24h. Aceitável enquanto todo o sistema (webhook + persistência) for UTC.

- **Setor com nome case-sensitive**: `findByName("A")` ≠ `findByName("a")`. Banco usa collation default do MySQL (geralmente case-insensitive em `utf8mb4_unicode_ci`), então `"a"` provavelmente bate. Comportamento depende do collation — não mexer agora.

- **Performance com muitas sessões**: o índice `idx_sessions_revenue (sector, exit_time)` cobre o filtro. Para 1M+ sessões/dia, considerar materialização (view ou tabela agregada) — fora do escopo (YAGNI).

- **Concorrência com `EXIT` em andamento**: se uma sessão estiver sendo encerrada exatamente durante a query, o `SUM` pega ou não pega aquele valor dependendo do isolamento. Aceitável — `/revenue` é consulta, não relatório fechado.

## 7. Fora de escopo deste PR

- **Validação `@Valid` no `RevenueRequest`** (`@NotBlank sector`, `@NotNull date`): só vale se cliente puder mandar payload malformado. O JSON parser do Jackson já falha com 400 em casos absurdos. Adicionar se houver requisito específico.
- **Cache** (`@Cacheable` em `revenueFor`): YAGNI — sem evidência de carga.
- **Endpoint de receita por **período** (range de datas)**: contrato é por dia único. PR separada se virar requisito.
- **Endpoint de receita por **placa** ou por **vaga****: fora do contrato.
- **Mudança do contrato de `GET` para `POST`**: discussão em aberto, não decidir aqui.
- **Validar migration Flyway de ponta-a-ponta** no `/revenue`: o integration test roda em H2 modo MySQL, não exercita o SQL bruto da V1. Cobrir isso exigiria Testcontainers — segue fora.

## 8. Checklist de execução

1. Criar `revenue/RevenueExceptions.kt` com `SectorNotFoundException` anotada com `@ResponseStatus(NOT_FOUND)`.
2. Modificar `revenue/RevenueService.kt`:
   - injetar `SectorRepository` no construtor;
   - remover o default `Clock = Clock.systemUTC()` (passa a ser obrigatório);
   - implementar `revenueFor` na ordem: validar setor → calcular janela UTC → somar → montar response com `setScale(2, HALF_EVEN)` e `clock.instant()`.
3. Criar `revenue/RevenueServiceTest.kt` com os **7 cenários** da §5.
4. Criar `revenue/RevenueFlowIntegrationTest.kt` com os **3 cenários** da §5.3 (setup H2 modo MySQL, assertions via `JsonPath`).
5. `./gradlew test` verde.
6. Subir `docker compose up -d` + `./gradlew bootRun`. Após pelo menos um ciclo completo `ENTRY → PARKED → EXIT`:
   - `curl -s -X GET http://localhost:3003/revenue -H 'Content-Type: application/json' -d '{"date":"2026-05-11","sector":"A"}'` → `200` com `amount` > 0.
   - `curl ... -d '{"date":"2026-05-11","sector":"INEXISTENTE"}'` → `404`.
   - `curl ... -d '{"date":"1999-01-01","sector":"A"}'` → `200` com `amount: 0.00`.
