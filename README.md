# Estapar Parking

Backend para gestão de uma garagem da Estapar: recebe eventos de entrada, estacionamento e saída via webhook do simulador, mantém o estado das vagas e expõe a receita acumulada por setor.

## Stack

- Kotlin 2.2.x · JVM 21
- Spring Boot 4.0.x (WebMVC, Data JPA, Validation)
- MySQL 8 + Flyway
- Gradle (Groovy DSL)
- Springdoc OpenAPI 3.0.x (Swagger UI)
- Simulador externo: `cfontes0estapar/garage-sim:1.0.0`

## Pré-requisitos

- JDK 21
- Docker + Docker Compose
- (Opcional) `gh` CLI para interações com o repositório

## Subindo o projeto

Dois caminhos. O modo recomendado é o Docker Compose — **um comando**, ordem garantida pelo `depends_on`, sem precisar coordenar timing entre simulador e app. O modo IDE existe para debug/hot-reload e exige a ordem `mysql → simulador → app` com a app subindo o mais rápido possível em seguida.

### ▶️ Modo recomendado: tudo no Docker Compose

```bash
docker compose --profile app up -d --build
```

Pronto. Sobe `mysql` → `garage-sim` → `app` na ordem certa (`depends_on: mysql healthy + garage-sim started`). A imagem multi-stage da app sobe em ~3s, então o primeiro `ENTRY` do simulador (5s após ele iniciar) já encontra o webhook respondendo — o problema de "scheduler do simulador desistir" praticamente desaparece nesse fluxo.

A app no container usa `mysql:3306` e `http://garage-sim:3000` via rede interna do compose — sem `host.docker.internal`. As portas no host continuam sendo `3003` (app), `3306` (MySQL) e `8081` (simulador).

Sanity check após ~30s:

```bash
curl -s http://localhost:8081/status   # active_vehicles deve crescer
```

Se ficar em zero, recrie só o simulador: `docker compose restart garage-sim`.

#### Build standalone (sem compose)

```bash
docker build -t estapar-parking:dev .
docker run --rm -p 3003:3003 \
  -e SPRING_DATASOURCE_URL='jdbc:mysql://host.docker.internal:3306/estapar?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true' \
  -e ESTAPAR_SIMULATOR_BASE_URL='http://host.docker.internal:8081' \
  estapar-parking:dev
```

Os testes **não** rodam dentro do build da imagem (alguns são `@SpringBootTest` integrados que dependem do banco) — rodar testes é responsabilidade do CI antes de empacotar.

### Modo alternativo: rodar pela IDE (`bootRun`)

Útil para debug e hot-reload. **A ordem importa**: o simulador dispara o primeiro `ENTRY` ~5s após subir e não retoma o scheduler se o webhook estiver offline naquele momento. Suba **MySQL → simulador → app**, **disparando o `bootRun` imediatamente em seguida** para minimizar a janela em que o webhook poderia chegar antes da app estar pronta.

#### 1. Subir o MySQL

```bash
docker compose up -d mysql
```

Container `estapar-mysql` (porta `3306`, banco `estapar`, user/pass: `estapar`/`estapar`).

#### 2. Subir o simulador

```bash
docker compose up -d garage-sim
```

Container `estapar-garage-sim` (porta host `8081`), dispara eventos para `http://host.docker.internal:3003/webhook` a cada 5s.

#### 3. Rodar a aplicação (imediatamente em seguida)

```bash
./gradlew bootRun
```

A aplicação:

1. Aplica migrations Flyway (`db/migration`).
2. Chama `GET http://localhost:8081/garage` e persiste setores/vagas (`GarageBootstrap`).
3. Fica ouvindo em **`:3003`** (webhook + REST).

O `bootRun` leva ~20–30s no cold start; o simulador já está disparando após 5s. Se `active_vehicles` continuar em zero após a app subir, recrie o simulador: `docker compose restart garage-sim`.

### Build / testes

```bash
./gradlew build       # build + testes
./gradlew test        # apenas testes
```

## Contexto (resumo)

A garagem tem **um único grupo de cancelas na entrada**. Setores são divisões **lógicas** (não físicas) — cada um com `basePrice`, capacidade, janela de funcionamento e ocupação própria.

A aplicação não controla cancelas: ela é a **fonte da verdade** sobre vagas e faturamento, atualizada pelos eventos do simulador.

Ciclo de uma sessão:

```
ENTRY (cancela) → PARKED (vaga) → EXIT (cancela, calcula valor)
```

Regras-chave (detalhes em [`docs/contexto.md`](docs/contexto.md)):

- **Tarifa**: primeiros 30 min grátis; após isso, valor por hora arredondada para cima, **inclusive a primeira hora**.
- **Preço dinâmico** capturado no `ENTRY` (em função da lotação do setor) e congelado na sessão: `< 25%` → 0,90; `< 50%` → 1,00; `< 75%` → 1,10; `< 100%` → 1,25.
- **100% de ocupação** fecha o setor: novos `ENTRY` para esse setor são rejeitados até alguém sair.

## Arquitetura

**Layered + package-by-feature.** Cada feature tem seu pacote (controller + service + DTOs); o domínio JPA fica em `domain/`.

```
com.estapar.parking
├── EstaparParkingApplication.kt
├── config/        infra (RestClient, Clock, properties, OpenAPI)
├── domain/        entidades JPA + repositórios + services de contexto + exceções de domínio
├── simulator/     cliente HTTP do simulador + bootstrap inicial
├── garage/        orquestra ENTRY/PARKED/EXIT + PricingPolicy
├── webhook/       POST /webhook (transporte; delega ao garage)
└── revenue/       GET /revenue + AddToRevenueListener (consome evento do EXIT)
```

Feature services (`GarageService`, `RevenueService`, `GarageBootstrap`) **não tocam repositórios** — falam apenas com os domain services em `domain/` (`SessionService`, `SpotService`, `SectorService`). Regras de transição (`session.park()`, `spot.occupy()`, etc.) vivem nas entidades; concorrência é controlada por `@Version` em `ParkingSession`/`Spot`/`Sector` (optimistic locking) — corridas reais são traduzidas em exceções de domínio pelos services. A saída de um veículo dispara `AddToRevenueEvent` publicado por `GarageService.processExit` e consumido por `RevenueService.addRevenue` (listener síncrono na mesma transação), que calcula a tarifa via `PricingPolicy` e grava um lançamento em `revenue_ledger`. Detalhes em [`docs/arquitetura.md`](docs/arquitetura.md#domain-services-e-regra-de-estado-no-agregado).

| Camada | Responsabilidade |
|---|---|
| Controller | HTTP I/O — bind de DTO, status code; sem regra de negócio |
| Service | Regras de negócio + `@Transactional` |
| Domain | Entidades JPA + repositórios enxutos |
| Config | Beans, properties, integrações externas |

Diagramas e justificativas em [`docs/arquitetura.md`](docs/arquitetura.md).

## API

A aplicação expõe **dois endpoints HTTP** na porta `3003`.

### `POST /webhook`

Discriminado por `event_type`:

| `event_type` | Payload | Significado |
|---|---|---|
| `ENTRY` | `license_plate`, `entry_time` | Veículo passou pela cancela |
| `PARKED` | `license_plate`, `lat`, `lng` | Veículo estacionou em uma vaga |
| `EXIT` | `license_plate`, `exit_time` | Veículo saiu (calcula valor) |

Resposta: **`200 OK` sempre** com corpo vazio. Eventos inválidos (placa sem sessão aberta, vaga já ocupada, replay de `ENTRY`, etc.) são logados e ignorados — o simulador nunca recebe erro pelo webhook.

### `GET /revenue`

```bash
curl -s -X GET http://localhost:3003/revenue \
  -H 'Content-Type: application/json' \
  -d '{"date":"2026-05-11","sector":"A"}'
```

```json
{ "amount": 121.50, "currency": "BRL", "timestamp": "2026-05-11T15:30:00.000Z" }
```

- Sem sessões encerradas no dia: `amount` é `0.00`.
- Setor inexistente: **`404 Not Found`**.
- Janela do dia em UTC (`[date 00:00:00Z, date+1 00:00:00Z)`).

### Spec OpenAPI / Swagger UI

Com a aplicação rodando:

- **Swagger UI**: http://localhost:3003/swagger-ui.html
- **Spec JSON**: http://localhost:3003/v3/api-docs

## Testes

- Padrão `given_when_then` com blocos `// given`, `// when`, `// then` e nome do teste em backticks descrevendo cenário/ação/resultado.
- Toda nova feature exige teste **unitário**.
- Teste de **integração** (`@SpringBootTest`) é opcional — só para fluxos críticos e mediante confirmação prévia.

Detalhes em [`docs/arquitetura.md`](docs/arquitetura.md) (seção 6).

## Limitações conhecidas e melhorias para produção em escala

Esta entrega cumpre o contrato do desafio com decisões pragmáticas para o escopo de avaliação (uma garagem, uma instância, regras de tarifa codadas, processamento síncrono). As seções abaixo inventariam o que mudaria para sustentar produção em alto volume / múltiplas garagens / SLA, **deliberadamente fora de escopo** aqui em respeito ao YAGNI.

### Configuração dinâmica de tarifa

Hoje as faixas de ocupação (`< 25 / 50 / 75 / 100%`) e os multiplicadores (`0,90 / 1,00 / 1,10 / 1,25`) são constantes em `PricingPolicy`. Em produção, *pricing* é decisão comercial — qualquer ajuste exige deploy.

- Tabela `pricing_tiers` com faixa, multiplicador, `valid_from`/`valid_to`, escopo (`sector_id` ou global).
- Histórico de mudanças de `base_price`, `max_capacity`, janelas e tarifas para auditoria.
- Cache da política em memória/Redis com invalidação por evento de mudança.

### Processamento assíncrono via fila

Hoje cada `POST /webhook` processa síncrono dentro de uma transação. Em pico de carga, o p99 do webhook fica refém do MySQL e dos locks pessimistas em `sectors`.

- Webhook vira **dispatcher**: valida payload, publica em **Kafka** particionado por `license_plate` (preserva ordem por veículo) ou **RabbitMQ** com `quorum queue`, devolve `200` imediatamente.
- Consumidores aplicam ENTRY/PARKED/EXIT em paralelo por partição.
- **Outbox pattern** para atomicidade entre `INSERT` da sessão e publicação na fila (sem two-phase commit).
- Retry/dead-letter passa a ser responsabilidade do broker, não do código de aplicação.
- Trade-off: receita em `/revenue` passa a ter consistência eventual (segundos de lag).

### Cache e leitura

- **Redis** para:
  - cache de `sectors` (TTL longo — leitura a cada `parkVehicle`, mudam raramente);
  - **contadores atômicos** (`INCR`/`DECR`) de ocupação por setor, eliminando `countBySectorAndOccupiedTrue` a cada `PARKED`/`EXIT`;
  - dedupe de eventos por `event_id` (idempotência forte ponta-a-ponta).
- **Read replica** dedicado ao `/revenue` — consulta agregada não compete com escrita do webhook no primary.
- **Materialização diária da receita**: a primeira camada de materialização já existe — `revenue_ledger` registra um lançamento por saída (escrito pelo listener de `AddToRevenueEvent`), e `/revenue` soma sobre ele em vez de varrer `parking_sessions`. Em 10M+ saídas/setor/ano ainda vale uma segunda camada — tabela `revenue_daily(sector, currency, date, total)` atualizada por job batch ou trigger no insert do ledger, com `/revenue` consultando direto a agregação.

### Particionamento e arquivamento

`parking_sessions` cresce sem teto. Em produção:

- Particionar por mês via *MySQL native partitioning* (`PARTITION BY RANGE(exit_time)`) ou tabela mensal separada.
- Arquivar sessões encerradas há mais de N meses em storage frio (S3 + Glue/Athena ou MySQL archive engine).
- Manter `revenue_daily` materializada cobrindo o histórico, mesmo após arquivar o detalhe.

### Multi-tenant / múltiplas garagens

O modelo atual assume **uma** garagem implícita. Para a operação real da Estapar:

- `garage_id` em `sectors`, `spots`, `parking_sessions` (com FK e índices compostos).
- Particionamento físico por `garage_id` ou shard por região.
- **Time zone por garagem**: hoje o sistema é UTC ponta-a-ponta; relatórios brasileiros normalmente em `America/Sao_Paulo`. Cada garagem deveria carregar seu `ZoneId`.
- **Janela overnight** (`close_hour < open_hour`, ex.: 22:00–06:00) em `Sector.isOpenAt` — citado nos riscos de `docs/features/entry.md`.

### Observabilidade

- Métricas (Micrometer + Prometheus): contadores por `event_type`, percentis de duração do webhook, taxa de eventos ignorados, ocupação por setor em tempo real.
- Tracing distribuído (OpenTelemetry) — essencial quando o pipeline virar webhook → fila → consumer → banco.
- Structured logging com `trace_id` correlacionando ENTRY → PARKED → EXIT da mesma sessão.
- Dashboards/alarmes para: ocupação ≥ 95% por setor, taxa de `DomainRuleViolation` ignoradas pelo webhook, lag do consumer.

### Resiliência

- Circuit breaker (Resilience4j) entre app e MySQL/Redis para falhar rápido em degradação.
- Retry com backoff exponencial no bootstrap do simulador.
- Health checks separados: liveness (processo vivo) vs readiness (banco + cache disponíveis).
- Graceful shutdown drenando requests em voo antes de sair (Spring Boot já cobre, validar config).

### Segurança

- Autenticação em `/revenue` (operadores da garagem, não público) — OAuth 2.0 ou JWT.
- **Shared secret / mTLS no webhook** — hoje qualquer cliente externo consegue enviar eventos.
- Rate limit por API key em endpoints de consulta (Bucket4j ou gateway).
- Auditoria de acessos administrativos (mudança de tarifa, leitura de receita).

### Qualidade de dados

- Job de cleanup para **sessões zumbis** (`ENTRY` sem `PARKED` nem `EXIT` há mais de N horas) — citado em `docs/features/exit.md`.
- Detecção de anomalias: sessões com duração > 30 dias, placa entrando/saindo em intervalos suspeitos, divergência entre `spots.occupied` agregado e sessões abertas.
- Reconciliação periódica entre estado físico (cancela/sensor) e lógico (sessões), caso a app perca eventos.

### Operação e CI/CD

- Manifests K8s ou Helm chart (o `Dockerfile` da app já está pronto).
- Pipeline CI (build + unit + integration + container build + deploy automático com canary).
- Testes de carga (k6 ou Gatling) com SLO definido — ex.: p99 do webhook < 100ms a 1k rps.
- Backup automatizado e procedimento de DR documentado para o MySQL.

---

A intenção aqui é deixar **explícito** que estas decisões foram conscientes, não esquecimentos: cada item acima é uma escolha de complexidade que não se justifica no escopo da avaliação, mas que entraria em produção real.

## Documentação canônica

Antes de implementar ou opinar, consulte:

- [`docs/contexto.md`](docs/contexto.md) — o que a aplicação faz, atores, endpoints, modelo de dados e regras de negócio.
- [`docs/arquitetura.md`](docs/arquitetura.md) — arquitetura em camadas, decisões técnicas, princípios de código (Clean Code, SOLID, KISS/DRY/YAGNI), Kotlin idiomático, padrão de testes e checklist de PR.
- [`docs/garage-simulator.md`](docs/garage-simulator.md) — contrato do simulador externo.
- [`docs/features/`](docs/features) — um doc por feature (`entry`, `parked`, `exit`, `revenue`, `revenuev2`) com decisões de design, riscos e checklist de execução.

## Governança e uso de IA

Diretrizes para qualquer assistente de IA (e para humanos editando o código) ficam em [`CLAUDE.md`](CLAUDE.md), com regras inegociáveis:

1. **Zero comentários no código** — nomes autoexplicativos; o "porquê" vai em commits, PRs ou `docs/`.
2. **PR mínimo e focado** — uma feature por PR; bumps de lib em PR próprio; sem refactor oportunista.
3. **Testes `given_when_then`** obrigatórios para toda nova funcionalidade.
4. **Confirmar antes de mexer** em `build.gradle`, dependências ou plugins.

`docs/contexto.md` e `docs/arquitetura.md` são a fonte da verdade — se algo aqui ou no `CLAUDE.md` conflitar, eles vencem.
