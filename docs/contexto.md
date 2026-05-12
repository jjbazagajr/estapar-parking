# Contexto da Aplicação — Estapar Parking

## Visão geral

Sistema backend para gerenciar uma garagem da **Estapar**: controla vagas disponíveis, registra entradas/saídas de veículos e calcula a receita acumulada por setor.

A garagem possui **um único grupo físico de cancelas na entrada**. Os **setores** são divisões **lógicas** (não físicas) usadas para organizar o pool de vagas — cada setor tem seu próprio preço-base, capacidade e janela de funcionamento.

A aplicação **não controla cancelas diretamente**: ela recebe eventos de um simulador externo via webhook e é a fonte da verdade sobre estado de vagas e faturamento.

## Stack

- **Linguagem**: Kotlin 2.2.x (JVM 21)
- **Framework**: Spring Boot 4.0.x (WebMVC, Data JPA, Validation, Flyway)
- **Banco**: MySQL 8.x (provisionado via Docker Compose)
- **Migrations**: Flyway
- **Build**: Gradle (Groovy DSL)
- **Externos**: simulador `cfontes0estapar/garage-sim:1.0.0` em `http://localhost:8081`

## Atores

| Ator | Papel |
|---|---|
| **Simulador (`garage-sim`)** | Expõe `GET /garage` (config) e dispara eventos `POST` para o webhook da aplicação |
| **Aplicação (`estapar-parking`)** | Persiste a config, recebe eventos do webhook, expõe API REST de consulta |
| **MySQL** | Persistência (setores, vagas, sessões de estacionamento) |
| **Cliente da API** | Consulta `GET /revenue` para obter o faturamento por setor/data |

## Boot da aplicação

Ao iniciar:

1. Flyway aplica as migrations em `db/migration` (cria/atualiza schema).
2. `GarageBootstrap` (`ApplicationRunner`) chama `GET {simulator}/garage` e persiste **setores** e **vagas** no banco.
3. A app fica ouvindo na **porta 3003** (webhook + REST).

Se o simulador não responder, a app **continua subindo** com aviso no log; o webhook pode falhar até a config existir.

## Endpoints expostos

### `POST /webhook`

Recebe três tipos de evento, discriminados pelo campo `event_type`:

| `event_type` | Payload | Significado |
|---|---|---|
| `ENTRY` | `license_plate`, `entry_time` | Veículo passou pela cancela de entrada |
| `PARKED` | `license_plate`, `lat`, `lng` | Veículo estacionou em uma vaga específica |
| `EXIT`  | `license_plate`, `exit_time`  | Veículo passou pela cancela de saída |

Resposta: **HTTP 200** (corpo vazio).

### `GET /revenue`

Consulta o faturamento total de um setor em uma data.

Request:
```json
{ "date": "2025-01-01", "sector": "A" }
```

Response:
```json
{ "amount": 0.00, "currency": "BRL", "timestamp": "2025-01-01T12:00:00.000Z" }
```

## Regras de negócio

### Ciclo de vida de uma sessão

```
ENTRY (cancela) ──▶ PARKED (vaga) ──▶ EXIT (cancela)
   │                   │                  │
   │                   │                  └─ libera vaga, calcula valor
   │                   └─ vincula vaga ao veículo, marca occupied=true
   └─ aplica preço dinâmico, registra entrada
```

Uma sessão é identificada pela placa enquanto não há `EXIT`. A **mesma placa só pode ter uma sessão aberta por vez**.

### Tarifa de saída

- **Primeiros 30 minutos**: grátis.
- **Após 30 minutos**: cobra-se uma **tarifa fixa por hora**, **inclusive** a primeira hora.
- A duração é arredondada **para cima** em horas inteiras (ex.: 31min = 1h, 1h05 = 2h).
- Valor base = `basePrice` do setor.
- Sobre o valor base aplica-se o **multiplicador de preço dinâmico** registrado **no momento do `ENTRY`**.

### Preço dinâmico (capturado no `ENTRY`)

Faixa de **lotação do setor** no momento da entrada:

| Lotação | Multiplicador |
|---|---|
| < 25% | **0,90** (10% de desconto) |
| < 50% | **1,00** |
| < 75% | **1,10** (+10%) |
| < 100% | **1,25** (+25%) |

O multiplicador é **congelado** na sessão — saídas usam o multiplicador registrado no `ENTRY`, não o atual.

### Lotação 100%

Quando um setor atinge 100% de ocupação, ele **fecha**: novos `ENTRY` para vagas desse setor são **rejeitados** até alguém sair. Como a entrada (cancela) é única, a aplicação decide a alocação por setor após o `PARKED`.

### Janela de funcionamento

Cada setor tem `open_hour`, `close_hour` e, opcionalmente, `duration_limit_minutes`. Eventos fora da janela podem ser rejeitados (regra a confirmar conforme cenário do simulador).

## Modelo de dados (resumo)

| Tabela | Papel |
|---|---|
| `sectors` | Configuração lógica: nome, `base_price`, `max_capacity`, janela de funcionamento, `version` (optimistic lock) |
| `spots` | Vagas físicas vinculadas a um setor: `id`, `sector`, `lat/lng`, `occupied`, `version` |
| `parking_sessions` | Sessão de um veículo: placa, entry/parked/exit, setor, vaga, `price_multiplier`, `version` |
| `revenue_ledger` | Lançamento financeiro por saída: `session_id` (UNIQUE), `sector`, `amount`, `currency`, `earned_at`, `created_at` (append-only, sem `version`) |

A receita é desacoplada da sessão: a cada `EXIT` o `GarageService` publica `AddToRevenueEvent`, e `RevenueService.addRevenue` (listener síncrono na mesma transação) calcula o valor via `PricingPolicy` e grava um lançamento no `revenue_ledger`. A consulta `/revenue` soma `amount` no ledger filtrando por `sector`, `currency` e janela de `earned_at`.

`currency` é gravado no ledger e usado também no filtro/resposta, hoje fixo em `BRL` via constante no `RevenueService` — o schema já está pronto para novas moedas.

## Fora do escopo

- Autenticação/autorização.
- Painel/UI.
- Integração com sistemas de cobrança/pagamento.
- Relatórios diferentes do `/revenue`.
- Cancelamento ou ajuste manual de sessões.
