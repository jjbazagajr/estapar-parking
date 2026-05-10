# Estapar Garage Simulator

Simulador de garagem (`cfontes0estapar/garage-sim:1.0.0`) rodando via Docker.

## Como rodar

```bash
docker run -d \
  -p 8081:3000 \
  -e EXTERNAL_API_URL=http://localhost:3003/webhook \
  --name garage-sim \
  cfontes0estapar/garage-sim:1.0.0
```

- O simulador escuta internamente em `3000`.
- A porta do host (`8081`) pode ser alterada conforme necessidade.
- A imagem é `linux/amd64`; em hosts ARM (Apple Silicon) roda via emulação.

## Endpoints HTTP expostos

Base URL: `http://localhost:8081`

| Método | URL completa                       | Descrição                                                                   |
|--------|------------------------------------|-----------------------------------------------------------------------------|
| GET    | `http://localhost:8081/status`     | Estado do simulador (veículos ativos, webhook configurado, intervalos)      |
| GET    | `http://localhost:8081/garage`     | Configuração de setores + lista de 30 vagas (id, setor, lat, lng, occupied) |

### Exemplo `GET /status`

```json
{
  "active_vehicles": 0,
  "client_webhook_url": "http://localhost:3003/webhook",
  "entry_interval_secs": 5,
  "exit_interval_secs": 15
}
```

### Exemplo `GET /garage`

```json
{
  "garage": [
    { "sector": "A", "base_price": 40.5, "max_capacity": 10, "open_hour": "00:00", "close_hour": "23:59", "duration_limit_minutes": 1440 },
    { "sector": "B", "base_price": 4.1,  "max_capacity": 20, "open_hour": "08:00", "close_hour": "23:59", "duration_limit_minutes": 60 }
  ],
  "spots": [
    { "id": 1, "sector": "A", "lat": -23.561684, "lng": -46.655981, "occupied": false }
    // ... 30 vagas no total (10 em A, 20 em B)
  ]
}
```

> Apenas esses dois endpoints HTTP estão expostos. Paths comuns como `/health`, `/api`, `/docs`, `/sessions`, `/events`, `/webhook` retornam **404**.

## Webhook (comunicação principal — outbound)

O simulador envia eventos via **POST** para o `client_webhook_url` configurado (definido pela env `EXTERNAL_API_URL`). Sua aplicação precisa implementar esse endpoint para receber eventos de entrada/saída/parking.

- Intervalo de entrada (default): `5s`
- Intervalo de saída (default): `15s`

## Como alterar o endpoint do webhook

A URL do webhook é definida pela variável de ambiente `EXTERNAL_API_URL` na criação do container. **Não é possível alterá-la em runtime** — é necessário recriar o container.

### Passos

```bash
# 1) parar e remover o container atual
docker stop garage-sim && docker rm garage-sim

# 2) recriar com o novo EXTERNAL_API_URL
docker run -d \
  -p 8081:3000 \
  -e EXTERNAL_API_URL=http://NOVO_HOST:NOVA_PORTA/webhook \
  --name garage-sim \
  cfontes0estapar/garage-sim:1.0.0

# 3) confirmar
curl http://localhost:8081/status
```

O campo `client_webhook_url` em `GET /status` deve refletir o novo valor.

### Observação sobre `localhost` no Docker

Se o webhook estiver rodando no **host** da máquina (não em outro container), `http://localhost:3003/webhook` **não funciona** dentro do container — `localhost` aponta para o próprio container. Use:

- **macOS/Windows**: `http://host.docker.internal:3003/webhook`
- **Linux**: `http://172.17.0.1:3003/webhook` ou rodar com `--network host`

Exemplo (macOS):

```bash
docker run -d \
  -p 8081:3000 \
  -e EXTERNAL_API_URL=http://host.docker.internal:3003/webhook \
  --name garage-sim \
  cfontes0estapar/garage-sim:1.0.0
```

## Comandos úteis

```bash
# logs
docker logs -f garage-sim

# inspecionar config (env vars, portas)
docker inspect garage-sim

# remover
docker stop garage-sim && docker rm garage-sim
```
