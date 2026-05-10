# CLAUDE.md

Este arquivo orienta o Claude (e qualquer assistente de IA) ao trabalhar **neste projeto**. Leia antes de qualquer mudança.

## Documentação canônica

Antes de implementar, refatorar ou opinar sobre qualquer coisa, **consulte**:

- [`docs/contexto.md`](docs/contexto.md) — o que a aplicação faz, atores, endpoints, modelo de dados e **regras de negócio** (tarifa, preço dinâmico, lotação).
- [`docs/arquitetura.md`](docs/arquitetura.md) — arquitetura em camadas, decisões técnicas, princípios de código (Clean Code, SOLID, KISS/DRY/YAGNI), idiomático Kotlin, padrão de testes `givenX_whenY_thenZ`, escopo de PR e checklist.

Esses dois documentos são a fonte da verdade. Se algo conflitar com este `CLAUDE.md`, eles vencem. Se algo neles ficou obsoleto, **atualize o documento** em vez de criar regra paralela aqui.

## Regras inegociáveis (extrato)

São absolutas — não há exceção, mesmo em código de exemplo, stub ou TODO.

1. **ZERO comentários no código.** Proibido `//`, `/* */`, KDoc e TODOs em arquivos `.kt`, `.sql`, `.yaml` e demais arquivos de código. Se sentir vontade de comentar, **renomeie** (variável, função, classe, pacote) até o comentário ficar redundante. Documentação de "porquê" vai em commits, PRs ou em `docs/` — nunca no arquivo de código. Detalhes em `docs/arquitetura.md#41-clean-code-resumo-prático`.

2. **PR mínimo e focado.** Uma feature por PR; alterar **apenas** os arquivos da feature alvo e o mínimo absolutamente necessário em pacotes compartilhados. **Proibido** misturar bump de lib/plugin/build com lógica de aplicação — bumps vão em PR próprio, sozinhos. **Sem refatoração oportunista.** Detalhes em `docs/arquitetura.md#7-escopo-de-pr--commits`.

3. **Testes no padrão `given_when_then`** com blocos explícitos `// given`, `// when`, `// then` no corpo e nome em backticks descrevendo cenário/ação/resultado. (Os blocos são separadores estruturais aceitos no padrão — distintos de comentários explicativos, que continuam proibidos.) Detalhes em `docs/arquitetura.md#6-testes`.

4. **Toda nova funcionalidade exige teste unitário — obrigatório, sem exceção.** Service novo, regra de negócio nova, handler de evento novo, função de cálculo nova → teste unitário no mesmo PR. Sem teste, não é "concluído". Teste de **integração** (`@SpringBootTest`, fluxo ponta-a-ponta com banco) é **opcional** e só se justifica quando a funcionalidade é **crítica e fortemente dependente de integração** (ex.: webhook completo gravando no DB e refletindo em `/revenue`). Nesses casos, **perguntar ao usuário antes** de escrever o teste de integração — nunca criar por conta própria. Refatoração que não acrescenta comportamento dispensa teste novo (basta os existentes continuarem verdes).

5. **Antes de mexer em `build.gradle`, dependências ou plugins, pare e confirme.**

## Princípios de código (síntese)

- **Clean Code, SOLID, KISS, DRY, YAGNI** aplicados pragmaticamente — extrair só na 3ª repetição; sem abstração para uso hipotético; sem interface com 1 implementação; sem getter/setter em Kotlin.
- **Kotlin idiomático**: `val` por padrão; sem `!!`; sealed types + `when` exaustivo; `data class` para DTOs (não para entidades JPA); injeção por construtor; `@Transactional` no service.
- **"Nunca crie mais código que o necessário."** Veja `docs/arquitetura.md#44-nunca-crie-mais-código-que-o-necessário`.

## Stack & infraestrutura

- Kotlin 2.2.x · JVM 21 · Spring Boot 4.0.x · MySQL 8 · Flyway · Gradle.
- Pacote raiz: `com.estapar.parking`.
- App escuta em `:3003` (webhook + REST).
- Simulador (`garage-sim`) em `:8081`.
- MySQL + simulador sobem via `docker-compose.yml` na raiz.

## Comandos úteis

```bash
docker compose up -d            # MySQL + simulador
./gradlew bootRun               # rodar a app
./gradlew build                 # build + testes
./gradlew test                  # só testes
```

## Estrutura de pacotes

```
com.estapar.parking
├── EstaparParkingApplication.kt
├── config/        configuração de infra (RestClient, properties)
├── domain/        entidades JPA + repositórios
├── simulator/     cliente do garage-sim + bootstrap inicial
├── webhook/       POST /webhook (ENTRY, PARKED, EXIT)
└── revenue/       GET /revenue
```

Detalhamento e responsabilidade de cada camada em `docs/arquitetura.md#2-estilo-arquitetural`.
