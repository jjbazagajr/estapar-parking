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

### 1. Subir MySQL e simulador

```bash
docker compose up -d
```

Sobe dois containers:

| Serviço | Porta host | Descrição |
|---|---|---|
| `estapar-mysql` | `3306` | MySQL 8.4 com banco `estapar` (user/pass: `estapar`/`estapar`) |
| `estapar-garage-sim` | `8081` | Simulador que dispara eventos para `http://host.docker.internal:3003/webhook` |

### 2. Rodar a aplicação

```bash
./gradlew bootRun
```

A aplicação:

1. Aplica migrations Flyway (`db/migration`).
2. Chama `GET http://localhost:8081/garage` e persiste setores/vagas (`GarageBootstrap`).
3. Fica ouvindo em **`:3003`** (webhook + REST).

Se o simulador não estiver de pé, a app sobe mesmo assim — só haverá log de aviso e o webhook falhará até a configuração existir.

### 3. Build / testes

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
├── config/        infra (RestClient, properties, OpenAPI)
├── domain/        entidades JPA + repositórios
├── simulator/     cliente HTTP do simulador + bootstrap inicial
├── webhook/       POST /webhook + handlers de evento (ENTRY, PARKED, EXIT)
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

Resposta: `200 OK` com corpo vazio.

### `GET /revenue`

```json
// request
{ "date": "2025-01-01", "sector": "A" }

// response
{ "amount": 0.00, "currency": "BRL", "timestamp": "2025-01-01T12:00:00.000Z" }
```

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

## Governança e uso de IA

Diretrizes para qualquer assistente de IA (e para humanos editando o código) ficam em [`CLAUDE.md`](CLAUDE.md), com regras inegociáveis:

1. **Zero comentários no código** — nomes autoexplicativos; o "porquê" vai em commits, PRs ou `docs/`.
2. **PR mínimo e focado** — uma feature por PR; bumps de lib em PR próprio; sem refactor oportunista.
3. **Testes `given_when_then`** obrigatórios para toda nova funcionalidade.
4. **Confirmar antes de mexer** em `build.gradle`, dependências ou plugins.

`docs/contexto.md` e `docs/arquitetura.md` são a fonte da verdade — se algo aqui ou no `CLAUDE.md` conflitar, eles vencem.
