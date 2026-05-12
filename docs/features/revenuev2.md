# Feature — Revenue v2: ledger desacoplado via evento de domínio

Evolução do `/revenue` original (descrito em [`revenue.md`](./revenue.md)): a parte financeira sai da entidade `ParkingSession` e passa a viver em uma tabela própria, `revenue_ledger`, alimentada por um evento de domínio (`AddToRevenueEvent`) disparado pelo `GarageService.processExit` e consumido por `RevenueService` no mesmo pacote da consulta.

Em uma segunda etapa, o `revenue_ledger` ganha a coluna `currency` para destravar moedas adicionais no futuro; por enquanto o valor é fixo em `BRL`, codado como constante no `RevenueService` e usado tanto na escrita quanto no filtro/resposta.

Referências:

- Plano original do endpoint: [`docs/features/revenue.md`](./revenue.md)
- Regras de negócio: [`docs/contexto.md`](../contexto.md)
- Arquitetura, padrão de testes e seção "Comunicação cross-feature via Spring Events": [`docs/arquitetura.md`](../arquitetura.md)
- Feature relacionada (origem do evento): [`docs/features/exit.md`](./exit.md)

## 1. Resumo

Antes:

- `ParkingSession.amount_charged` armazenava o valor cobrado em cada saída.
- `RevenueService.revenueFor` somava `amount_charged` das sessões com `exit_time` no dia.
- `GarageService.processExit` calculava a tarifa via `PricingPolicy` e gravava `amount_charged` no `UPDATE` da sessão (mesma `markExited`).

Depois:

1. Saída do veículo segue passando por `GarageService.processExit`, que continua validando pré-condições, gravando `exit_time` e liberando a vaga.
2. Logo após o `markExited` bem-sucedido, o service publica `AddToRevenueEvent(sessionId, exitTime)` via `ApplicationEventPublisher`.
3. `AddToRevenueListener` (em `revenue/`) recebe o evento de forma **síncrona, na mesma transação** e delega para `RevenueService.addRevenue(event)`.
4. `RevenueService.addRevenue`:
   - busca a sessão por id, valida `sector` e `priceMultiplier`;
   - busca o setor pelo nome (para `basePrice`);
   - calcula `amount` via `PricingPolicy.feeFor(seconds, basePrice, multiplier)`;
   - persiste um `RevenueLedgerEntry(sessionId, sector, amount, currency = "BRL", earnedAt = event.exitTime, createdAt = clock.instant())`.
5. `GET /revenue` continua respondendo `{ amount, currency, timestamp }`, mas agora o `amount` vem de `RevenueLedgerRepository.sumRevenue(sector, currency, start, end)` — ledger é a fonte da verdade financeira; a sessão não tem mais o campo `amount_charged`.

## 2. Decisões de design

### D1 — Por que extrair `revenue_ledger` em vez de manter `amount_charged` na sessão

A entidade `ParkingSession` representa o ciclo operacional do veículo (entrou → estacionou → saiu). Misturar nela o `amount_charged` acoplava operação a financeiro: qualquer alteração futura no faturamento (descontos, estornos, múltiplas moedas, retenção fiscal, conciliação) iria mexer na entidade da sessão. Extrair o ledger:

- Isola o histórico financeiro em uma tabela append-only, fácil de auditar.
- Permite que a sessão e o lançamento evoluam por razões independentes (Single Responsibility).
- Abre caminho para conceitos que não fazem sentido no escopo de sessão: estorno, ajuste, lançamentos sem sessão associada.

A sessão segue carregando os dados que produzem o valor (`entry_time`, `exit_time`, `sector`, `price_multiplier`) — o ledger é estritamente o **lançamento contábil** derivado.

### D2 — Comunicação por evento de domínio, não chamada direta

Poderia-se ter `GarageService` chamando `RevenueService.addRevenue(...)` diretamente após `markExited`. Em vez disso, o `processExit` publica `AddToRevenueEvent` e o pacote `revenue` reage.

| Critério | Chamada direta | Evento de domínio |
|---|---|---|
| Acoplamento de pacote | `garage` importa `revenue` (consumidor) | `garage` importa só o tipo do evento, não o consumidor |
| Adicionar outro consumidor | precisa editar `garage` | adiciona um novo listener, `garage` intocado |
| Inversão de dependência | `garage` decide o que `revenue` faz | `garage` anuncia o que aconteceu; `revenue` decide o que fazer |

Para o escopo atual (apenas `revenue` reage ao `EXIT`), os ganhos são moderados. Mas o custo do evento é minúsculo (uma classe data e um `@EventListener`), e o padrão fica pronto para casos como: enviar e-mail de comprovante, atualizar contador de ocupação, alimentar painel em tempo real.

### D3 — Listener **síncrono na mesma transação** (e não `AFTER_COMMIT`)

Discutido em detalhe na seção "Comunicação cross-feature via Spring Events" do [`docs/arquitetura.md`](../arquitetura.md). Resumo:

- **Sync + mesma TX** (escolhido): se a gravação no ledger falhar, o `processExit` inteiro rolla back — a saída deixa de ser persistida. **Não existe estado "carro saiu, mas a receita não foi registrada"**.
- `AFTER_COMMIT` desacopla mais (a saída já está commitada quando o listener roda), mas se o listener falhar é **silent data loss**: vaga liberada, sessão fechada, ledger vazio. Mitigar isso exigiria outbox/retry — complexidade fora do escopo.
- `@Async + AFTER_COMMIT` tem o mesmo problema, pior ainda porque o erro vira só log.

O custo da escolha: a disponibilidade do `revenue_ledger` fica acoplada à da saída. Se o ledger estiver indisponível (FK quebrada, lock contention, índice corrompido), as saídas também travam — preferível a um descasamento silencioso.

### D4 — Payload do evento carrega `exitTime`

Tentação inicial: evento com `sessionId` apenas; listener re-fetch a sessão e pega `exitTime` dela. Não funciona em uma única transação porque:

- `markExited` é `@Modifying` JPQL UPDATE.
- Hibernate **não atualiza** o cache JPA de 1º nível com UPDATEs assim.
- Quando o listener faz `sessions.findById(sessionId)`, retorna a mesma entidade ainda em cache, com `exitTime = null`.
- O `requireNotNull(session.exitTime)` no recorder explodia com `IllegalArgumentException`, era capturada como erro genérico e a TX rollava silenciosamente (200 OK no webhook, exit ignorado, ledger vazio).

Soluções consideradas:

- `@Modifying(clearAutomatically = true, flushAutomatically = true)`: limpa o cache, mas detacha o `spot` que estava sendo modificado por dirty checking — quebra a liberação da vaga.
- Reordenar para ler antes/depois: frágil, dependente da ordem dos statements.
- **Passar `exitTime` no evento**: o dado é dinâmico do `EXIT`; `entryTime`/`sector`/`priceMultiplier` já estavam na entidade carregada (não foram modificados por UPDATE) e podem ser lidos do cache normalmente.

O evento ficou:

```kotlin
data class AddToRevenueEvent(val sessionId: Long, val exitTime: Instant)
```

### D5 — `RevenueRecorder` foi absorvido pelo `RevenueService`

Primeira tentativa separava um `RevenueRecorder` (escrita) do `RevenueService` (leitura), seguindo SRP. Revisado: o estilo deste projeto é **um service por pacote** (espelho de `GarageService`, que tem `registerEntry`/`parkVehicle`/`processExit`). Manter duas classes só pela diferença leitura/escrita era abstração sem ganho — virou um arquivo só com dois métodos:

```kotlin
@Service
class RevenueService(
    private val sessions: ParkingSessionRepository,
    private val sectors: SectorRepository,
    private val ledger: RevenueLedgerRepository,
    private val pricing: PricingPolicy,
    private val clock: Clock,
) {
    fun revenueFor(date: LocalDate, sector: String): RevenueResponse { ... }
    fun addRevenue(event: AddToRevenueEvent) { ... }

    private companion object {
        const val CURRENCY = "BRL"
    }
}
```

O listener (`AddToRevenueListener`) permanece como classe separada porque é apenas glue para a anotação `@EventListener` — manter a fronteira "evento → service" explícita ajuda a leitura. Misturar `@EventListener` dentro do próprio service também seria válido, mas não foi a opção escolhida.

### D6 — Idempotência via `UNIQUE(session_id)` no ledger

Cada saída produz no máximo um lançamento. Para fechar a porta a republicações acidentais (bug, retry, dupla emissão do evento), `revenue_ledger.session_id` é `UNIQUE`. Em uma segunda execução, o `INSERT` falharia com `DataIntegrityViolationException`; como o listener roda na mesma TX, isso vira rollback completo do exit. Sem ledger duplicado, sem código de "if exists" no recorder.

### D7 — `created_at` populado em código (não pelo `DEFAULT` do MySQL)

A migration `V4` declara `created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)`. Em produção (MySQL + Flyway) o default cobriria inserts sem o campo. Mas o teste de integração roda em **H2 modo MySQL com `ddl-auto: create-drop` e Flyway off** — o schema é gerado a partir das entidades JPA, ignorando a migration. Sem o `DEFAULT`, o teste falhava com `NULL not allowed for column "CREATED_AT"`.

Solução: o `RevenueService.addRevenue` injeta `Clock` e seta `createdAt = clock.instant()` explicitamente no construtor da entidade. O `DEFAULT` do MySQL fica como rede de segurança para inserts que venham fora do app; o código não depende dele.

### D8 — `currency` extraído já na primeira versão do ledger

Mesmo sendo fixo em `BRL` hoje, `currency` foi adicionado como coluna `NOT NULL` na tabela e como campo na entidade, em vez de "deixar para depois" via migration futura. Razões:

- Receita sem moeda explícita é ambígua — uma tabela financeira deve carregá-la.
- Adicionar depois exigiria migration de backfill em dados existentes; adicionar agora é uma coluna a mais sem custo.
- A constante no service (`CURRENCY = "BRL"`) marca explicitamente o local único a tocar quando suporte a outras moedas entrar.

A coluna entrou em migration separada (`V5`) para preservar `V4` (que criava a tabela) imutável após mergeada.

### D9 — Filtro `currency` na agregação

`RevenueLedgerRepository.sumRevenue` recebe `currency` além de `sector` e janela de `earned_at`. Por que filtrar se hoje só existe `BRL`?

- Quando outras moedas chegarem, agregar `BRL + USD` daria valores absurdos (soma de números em unidades diferentes). O filtro evita esse bug por construção.
- Cardinalidade hoje = 1, custo do filtro = zero. YAGNI invertido: deixar de filtrar agora cobraria refactor + migration + auditoria quando o multi-currency aparecesse.

A resposta do `GET /revenue` continua expondo `currency` (também via constante), preservando o contrato existente.

### D10 — Sem `@Transactional` explícito no `RevenueService`

O `revenueFor` faz duas leituras (sector + sum). O `addRevenue` é invocado por `@EventListener` síncrono, dentro da `@Transactional` do `processExit`. Em ambos os casos, anotar `RevenueService` com `@Transactional` seria redundante: Spring Data abre transação curta para cada chamada de repositório, e o caminho de escrita já roda dentro da TX do produtor do evento. Adicionar `@Transactional(readOnly = true)` no `revenueFor` é otimização sem evidência — KISS, fica fora.

### D11 — Índice composto antigo continua existindo

A migration V4 considerou dropar `idx_sessions_revenue (sector, exit_time)` porque a query antiga deixou de existir. MySQL recusou: o índice é necessário pelo FK `fk_sessions_sector`. Manter custaria reformatar o FK, o que é cirurgia sem benefício real. O índice ficou — redundante para a consulta de revenue, ainda útil para o FK.

## 3. Estrutura de pacotes (efeito do refactor)

```
com.estapar.parking/
├── domain/
│   ├── ParkingSession.kt              MODIFICADO — removido amount_charged
│   ├── ParkingSessionRepository.kt    MODIFICADO — sumRevenue removido; markExited sem amount
│   ├── RevenueLedgerEntry.kt          NOVO — entidade JPA
│   └── RevenueLedgerRepository.kt     NOVO — sumRevenue(sector, currency, start, end)
├── garage/
│   └── GarageService.kt               MODIFICADO — processExit publica AddToRevenueEvent
└── revenue/
    ├── AddToRevenueEvent.kt           NOVO — data class (sessionId, exitTime)
    ├── AddToRevenueListener.kt        NOVO — @EventListener síncrono
    ├── RevenueService.kt              MODIFICADO — addRevenue() + revenueFor() apontando para ledger
    ├── RevenueController.kt           inalterado
    ├── RevenueDtos.kt                 inalterado
    └── RevenueExceptions.kt           inalterado
```

Migrations Flyway:

```
src/main/resources/db/migration/
├── V4__revenue_ledger.sql             NOVO — cria revenue_ledger + drop amount_charged
└── V5__revenue_ledger_currency.sql    NOVO — adiciona currency NOT NULL DEFAULT 'BRL'
```

Testes:

```
src/test/kotlin/.../revenue/
├── RevenueServiceTest.kt              MODIFICADO — cobre revenueFor() e addRevenue() no mesmo arquivo
├── AddToRevenueListenerTest.kt        NOVO — listener delega ao service
└── RevenueFlowIntegrationTest.kt      MODIFICADO — agora roda ENTRY → PARKED → EXIT via POST /webhook
```

`GarageServiceTest`, `WebhookFlowIntegrationTest` também tocados (remoção das assertions sobre `amount_charged`, novo teste verificando `publishEvent`).

## 4. Detalhamento

### `domain/RevenueLedgerEntry.kt` e migration V4

```sql
CREATE TABLE revenue_ledger (
    id          BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id  BIGINT         NOT NULL UNIQUE,
    sector      VARCHAR(32)    NOT NULL,
    amount      DECIMAL(10, 2) NOT NULL,
    earned_at   TIMESTAMP(3)   NOT NULL,
    created_at  TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ledger_session FOREIGN KEY (session_id) REFERENCES parking_sessions (id),
    CONSTRAINT fk_ledger_sector  FOREIGN KEY (sector)     REFERENCES sectors (name)
) ENGINE = InnoDB;

CREATE INDEX idx_ledger_revenue ON revenue_ledger (sector, earned_at);

ALTER TABLE parking_sessions DROP COLUMN amount_charged;
```

`session_id UNIQUE` é a chave da idempotência (D6). `(sector, earned_at)` é o índice que cobre a consulta de `/revenue`.

### Migration V5 (currency)

```sql
ALTER TABLE revenue_ledger
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'BRL';
```

`DEFAULT 'BRL'` cobre rows existentes (zero em ambientes novos, mas necessário caso a aplicação já tenha gravado registros antes da migration). O código sempre seta a coluna explicitamente — o default vira apenas rede de segurança.

### `garage/GarageService.kt` — trecho do `processExit`

```kotlin
val sessionId = requireNotNull(session.id) { "session sem id" }
val closed = sessions.markExited(id = sessionId, exitTime = exitInstant)
if (closed == 0) throw SessionAlreadyExitedException(plate)

spot.occupied = false
events.publishEvent(AddToRevenueEvent(sessionId, exitInstant))
```

`markExited` agora atualiza apenas `exit_time` (parâmetro `amount` removido junto com a coluna). A publicação acontece **depois** de garantir que o `UPDATE` teve sucesso (`closed > 0`) — caso contrário, `SessionAlreadyExitedException` é lançada e o evento nunca chega ao listener.

### `revenue/RevenueService.kt`

```kotlin
@Service
class RevenueService(
    private val sessions: ParkingSessionRepository,
    private val sectors: SectorRepository,
    private val ledger: RevenueLedgerRepository,
    private val pricing: PricingPolicy,
    private val clock: Clock,
) {

    fun revenueFor(date: LocalDate, sector: String): RevenueResponse {
        sectors.findByName(sector) ?: throw SectorNotFoundException(sector)
        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val amount = ledger.sumRevenue(sector, CURRENCY, start, end).setScale(2, RoundingMode.HALF_EVEN)
        return RevenueResponse(amount = amount, currency = CURRENCY, timestamp = clock.instant())
    }

    fun addRevenue(event: AddToRevenueEvent) {
        val session = sessions.findById(event.sessionId).orElse(null)
            ?: error("Sessão ${event.sessionId} referenciada por AddToRevenueEvent não existe")
        val sectorName = requireNotNull(session.sector) { "Sessão ${event.sessionId} sem sector" }
        val multiplier = requireNotNull(session.priceMultiplier) { "Sessão ${event.sessionId} sem price_multiplier" }
        val sector = sectors.findByName(sectorName) ?: throw SectorMissingException(sectorName)
        val seconds = Duration.between(session.entryTime, event.exitTime).seconds
        val amount = pricing.feeFor(seconds, sector.basePrice, multiplier)
        ledger.save(
            RevenueLedgerEntry(
                sessionId = event.sessionId,
                sector = sectorName,
                amount = amount,
                currency = CURRENCY,
                earnedAt = event.exitTime,
                createdAt = clock.instant(),
            ),
        )
    }

    private companion object {
        const val CURRENCY = "BRL"
    }
}
```

`SectorMissingException` é reaproveitada do pacote `garage/` (D2 do [`revenue.md`](./revenue.md) original discutia o porquê de **não** reusar essa exceção no caminho de consulta — aqui o caminho é diferente: a hierarquia `WebhookEventIgnored` faz sentido no `addRevenue`, porque o listener roda **dentro** do dispatch do webhook).

### `revenue/AddToRevenueListener.kt`

```kotlin
@Component
class AddToRevenueListener(
    private val service: RevenueService,
) {
    @EventListener
    fun on(event: AddToRevenueEvent) {
        service.addRevenue(event)
    }
}
```

## 5. Testes

### 5.1. `RevenueServiceTest` (unitário)

Cobertura de ambas as faces do service no mesmo arquivo:

**Leitura (`revenueFor`):**

- `given setor inexistente when revenueFor then lanca SectorNotFoundException e nao consulta ledger`
- `given data 2025-01-01 when revenueFor then consulta sumRevenue com sector currency BRL e janela 2025-01-01T00 00 UTC ate 2025-01-02T00 00 UTC`
- `given setor sem lancamentos no dia when revenueFor then retorna amount 0_00 e currency BRL`
- `given setor com lancamentos no dia somando 123_45 when revenueFor then retorna amount 123_45`
- `given soma retornada com escala 4 (ex 123_4500) when revenueFor then normaliza para escala 2`
- `given clock fixo when revenueFor then timestamp e o instante do clock`
- `given setor valido when revenueFor then currency e BRL`

**Escrita (`addRevenue`):**

- `given session inexistente when addRevenue then lanca IllegalStateException e nao persiste ledger`
- `given session sem sector when addRevenue then lanca IllegalArgumentException`
- `given session sem price multiplier when addRevenue then lanca IllegalArgumentException`
- `given sector da session removido do banco when addRevenue then lanca SectorMissingException`
- `given session 1h multiplier 1_000 basePrice 40_50 when addRevenue then persiste ledger com amount 40_50, sector A, currency BRL, earnedAt do evento e createdAt do clock`
- `given session 1h multiplier 0_900 basePrice 40_50 when addRevenue then amount 36_45`
- `given event com exitTime igual ao entryTime when addRevenue then amount 0_00`

### 5.2. `AddToRevenueListenerTest` (unitário)

Único cenário — listener é glue:

- `given AddToRevenueEvent when on then delega para service com o mesmo evento`

### 5.3. `GarageServiceTest` (modificado)

Removidos os testes que validavam `amountCharged` em `markExited` (atributo deixou de existir; lógica de cálculo migrou para `RevenueService.addRevenue` e está coberta lá). Adicionado:

- `given exit valido when process exit then publica AddToRevenueEvent com sessionId e exitTime`
- `given markExited retorna 0 (sessao ja encerrada) when process exit then lanca SessionAlreadyExitedException, nao libera vaga e nao publica evento`

### 5.4. `RevenueFlowIntegrationTest` (reescrito ponta-a-ponta)

Antes: populava `parking_sessions` direto via repository e consultava `/revenue`.

Agora: simula o fluxo completo via `POST /webhook` (ENTRY → PARKED → EXIT) para múltiplas placas/setores/datas, depois faz `GET /revenue` e valida que o ledger materializou os valores corretos. Cobre simultaneamente:

- A publicação do `AddToRevenueEvent` no `processExit`.
- A execução síncrona do listener (mesmo `mockMvc.perform` que enviou o EXIT já vê o ledger populado).
- O cálculo de tarifa via `PricingPolicy` em contexto real (H2 modo MySQL).
- O filtro por `sector` + `currency` + janela `[start, end)` UTC na agregação.

Cenários:

- `given EXIT em datas e setores distintos when GET revenue then soma apenas o setor e dia consultados via ledger`
- `given nenhum EXIT no dia consultado when GET revenue then retorna 0_00 com currency BRL`
- `given setor inexistente when GET revenue then responde 404`

### 5.5. `WebhookFlowIntegrationTest` (ajustado)

Removidas as asserções sobre `amountCharged` na sessão (campo não existe mais). Os testes seguem validando que o ciclo `ENTRY → PARKED → EXIT` produz o estado esperado em `parking_sessions` e `spots`. A parte financeira ficou coberta em `RevenueFlowIntegrationTest`.

## 6. Riscos e pegadinhas

- **`createdAt` em H2**: caiu aqui no desenvolvimento. Se algum dia uma nova coluna do ledger ganhar default no SQL e não for setada no código, o teste de integração quebra com `NULL not allowed`. Padrão a seguir: **sempre setar explicitamente no app**, `DEFAULT` do MySQL é apenas rede de segurança.

- **L1 cache do JPA com `@Modifying`**: o motivo de `exitTime` viajar no evento. Vale para qualquer outro listener que precise ler campos atualizados por `UPDATE @Modifying` em uma sessão JPA já carregada. Caminho mais seguro: passar pelo payload do evento.

- **Listener throw → rollback do webhook**: qualquer exceção propagada do listener desfaz o `markExited` e a liberação da vaga. Bom para consistência, mas significa que um bug em `addRevenue` derruba `EXIT` na produção. Mitigação: testes pesados em `RevenueServiceTest`; em produção, monitorar taxa de rollback no `processExit`.

- **`UNIQUE(session_id)` no ledger**: dá idempotência forte, mas se um dia precisar de múltiplos lançamentos por sessão (ex.: estorno parcial, ajuste), o constraint vira obstáculo. Plano: quando isso acontecer, drop do `UNIQUE` e introdução de `kind ENUM` (`'CHARGE'`, `'REFUND'`, `'ADJUSTMENT'`) com partial unique index.

- **Multi-currency hoje desabilitado**: a coluna existe, a query filtra, mas a constante é fixa em `BRL`. Trocar para uma propriedade configurável é trivial; o que **não** é trivial é decidir a regra de conversão e a fonte de câmbio — fora de escopo.

- **`PricingPolicy` importado por `revenue/`**: cria uma dependência `revenue → garage`. Justificável: `PricingPolicy` é stateless, está bem nomeado, e move-lo para `domain/` ou `pricing/` seria refactor oportunista. Se uma terceira feature precisar de `PricingPolicy`, aí sim vale promover.

## 7. Fora de escopo deste PR

- **Tabela `revenue_daily` materializada**: descrita em `README.md` como próximo passo de produção. YAGNI no escopo atual — o ledger por saída já basta para o volume da avaliação.
- **Conversão de moeda**: schema preparado, lógica deliberadamente codada como `BRL`.
- **Endpoint de receita por período (range)**: contrato continua sendo por dia único.
- **`@Async` ou `AFTER_COMMIT`**: discutido e rejeitado em D3.
- **Outbox pattern**: só faria sentido se mudássemos para `AFTER_COMMIT`.

## 8. Checklist de execução

1. Criar migration `V4__revenue_ledger.sql` (cria tabela + drop `amount_charged`).
2. Criar `domain/RevenueLedgerEntry.kt` e `domain/RevenueLedgerRepository.kt`.
3. Remover `amount_charged` de `ParkingSession.kt` e o parâmetro `amount` de `ParkingSessionRepository.markExited`.
4. Criar `revenue/AddToRevenueEvent.kt`.
5. Modificar `garage/GarageService.processExit` para publicar o evento após `markExited`.
6. Adicionar `revenue/AddToRevenueListener.kt` (delega ao service).
7. Modificar `revenue/RevenueService.kt`:
   - injetar `ParkingSessionRepository`, `RevenueLedgerRepository`, `PricingPolicy`;
   - implementar `addRevenue(event)` calculando via `PricingPolicy` e gravando o ledger;
   - apontar `revenueFor` para `RevenueLedgerRepository.sumRevenue`.
8. Criar migration `V5__revenue_ledger_currency.sql` e adicionar campo `currency` na entidade.
9. Atualizar `RevenueLedgerRepository.sumRevenue` para receber `currency`.
10. Adicionar `companion object { const val CURRENCY = "BRL" }` ao `RevenueService` e usar em ambas as faces.
11. Atualizar `RevenueServiceTest` (read + write coverage), criar `AddToRevenueListenerTest`, ajustar `GarageServiceTest` e `WebhookFlowIntegrationTest`, reescrever `RevenueFlowIntegrationTest` ponta-a-ponta.
12. `./gradlew clean build` verde (Flyway aplicando V1→V5 em MySQL via `docker compose up -d mysql`; testes H2 com `ddl-auto: create-drop`).
13. Sanidade manual: `docker compose --profile app up -d --build`, aguardar `active_vehicles` crescer no `/status` do simulador, depois `curl GET /revenue` retornando `amount > 0` com `currency: "BRL"`.
