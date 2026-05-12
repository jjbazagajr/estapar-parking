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

A ordem importa: o simulador dispara o primeiro `ENTRY` ~5s após subir e **não retoma o scheduler** se o webhook estiver offline naquele momento. Suba o simulador **por último**, com a app já ouvindo em `:3003`.

### 1. Subir o MySQL

```bash
docker compose up -d mysql
```

Sobe o container `estapar-mysql` (porta `3306`, banco `estapar`, user/pass: `estapar`/`estapar`).

### 2. Rodar a aplicação

```bash
./gradlew bootRun
```

A aplicação:

1. Aplica migrations Flyway (`db/migration`).
2. Chama `GET http://localhost:8081/garage` e persiste setores/vagas (`GarageBootstrap`). Se o simulador ainda não estiver de pé, sobe mesmo assim — só haverá log de aviso e o bootstrap roda quando o simulador aparecer (ou na próxima subida da app).
3. Fica ouvindo em **`:3003`** (webhook + REST).

Aguarde a linha `Started EstaparParkingApplication` no log antes do próximo passo.

### 3. Subir o simulador

```bash
docker compose up -d garage-sim
```

Sobe o container `estapar-garage-sim` (porta host `8081`), que dispara eventos para `http://host.docker.internal:3003/webhook` a cada 5s. Conferir com:

```bash
curl -s http://localhost:8081/status
```

`active_vehicles` deve crescer ao longo do tempo. Se ficar em zero, recrie o container: `docker compose restart garage-sim`.

### 4. Build / testes

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
├── domain/        entidades JPA + repositórios
├── simulator/     cliente HTTP do simulador + bootstrap inicial
├── garage/        regras de negócio (ENTRY/PARKED/EXIT) + PricingPolicy
├── webhook/       POST /webhook (transporte; delega ao garage)
└── revenue/       GET /revenue + cálculo de faturamento
```

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

## Documentação canônica

Antes de implementar ou opinar, consulte:

- [`docs/contexto.md`](docs/contexto.md) — o que a aplicação faz, atores, endpoints, modelo de dados e regras de negócio.
- [`docs/arquitetura.md`](docs/arquitetura.md) — arquitetura em camadas, decisões técnicas, princípios de código (Clean Code, SOLID, KISS/DRY/YAGNI), Kotlin idiomático, padrão de testes e checklist de PR.
- [`docs/garage-simulator.md`](docs/garage-simulator.md) — contrato do simulador externo.
- [`docs/features/`](docs/features) — um doc por feature (`entry`, `parked`, `exit`, `revenue`) com decisões de design, riscos e checklist de execução.

## Governança e uso de IA

Diretrizes para qualquer assistente de IA (e para humanos editando o código) ficam em [`CLAUDE.md`](CLAUDE.md), com regras inegociáveis:

1. **Zero comentários no código** — nomes autoexplicativos; o "porquê" vai em commits, PRs ou `docs/`.
2. **PR mínimo e focado** — uma feature por PR; bumps de lib em PR próprio; sem refactor oportunista.
3. **Testes `given_when_then`** obrigatórios para toda nova funcionalidade.
4. **Confirmar antes de mexer** em `build.gradle`, dependências ou plugins.

`docs/contexto.md` e `docs/arquitetura.md` são a fonte da verdade — se algo aqui ou no `CLAUDE.md` conflitar, eles vencem.
