# AI Processor

Private Spring Boot service for the Retail App's retailer-facing chat. It retrieves approved, read-only retail data through the retail backend and stores its model catalog and conversation history in the dedicated `ai_processor` schema. It does not accept retail JWTs. The retail backend remains responsible for authentication and authorization.

The backend chooses a model per chat request using its `modelId`. Query the signed `GET /internal/v1/models` endpoint to discover enabled models from `ai_processor.ai_models`.

Provider credentials are kept in environment variables. Model IDs, provider names, display names, and enabled status are kept in the database:

- OpenRouter: set `OPENROUTER_API_KEY`; the initial catalog includes `openrouter/free`.
- Gemini: set `GEMINI_API_KEY`; the initial catalog includes `gemini-flash-latest`.
- Groq: set `GROQ_API_KEY`; the catalog includes GPT OSS 20B.
- Cloudflare Workers AI: set `CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN`; the token needs Account > Workers AI > Read permission. The catalog includes `@cf/meta/llama-3.3-70b-instruct-fp8-fast`.

To add or disable a model, insert or update its row in `ai_processor.ai_models`. Keep the model value in the provider's own model-ID format; the catalog `id` is sent by the backend as `modelId`. Do not store provider API keys in this table.

If the model endpoint returns an empty list, verify that Flyway applied V3 and that enabled rows exist in `ai_processor.ai_models`. A provider key is still required to make chat requests to that provider.

The model ID in the list (for example `openrouter/openrouter/free`) must be sent as `modelId` in each `POST /internal/v1/chat` request. Unknown or disabled model IDs are rejected. The processor does not accept provider credentials from the backend request.

Keep provider keys in environment variables or a deployment secret store, never in source files or chat history. Hosted free tiers have changing quotas and data policies; review those terms before sending retail data. Do not include customer contact information in model context.

## Database boundary

The processor stores conversations and messages in a dedicated `ai_processor` schema in the existing `retail` PostgreSQL database, with versioned Flyway migrations. It does not copy products, customers, sales, or other retail source-of-truth records. Current business facts continue to come through the backend's approved, role-checked data interface. Flyway runs at startup and the conversation queries explicitly use `ai_processor`.

Configure `AI_PROCESSOR_DB_URL`, `AI_PROCESSOR_DB_USER`, and `AI_PROCESSOR_DB_PASSWORD` (or the neighboring backend's `DB_URL`, `DB_USER`, and `DB_PASSWORD`). The database role needs permission to create the `ai_processor` schema on first run and to use it afterward. Add future schema changes as new files in `src/main/resources/db/migration`; do not edit migrations that have already run in a shared environment.

## Run locally

Requirements: JDK 17 and Maven 3.8+.

```powershell
$env:AI_PROCESSOR_SHARED_SECRET = "use-a-long-random-secret-shared-with-the-retail-backend"
$env:AI_PROCESSOR_DB_URL = "jdbc:postgresql://localhost:5432/retail"
$env:AI_PROCESSOR_DB_USER = "postgres"
$env:AI_PROCESSOR_DB_PASSWORD = "your-local-db-password"
$env:OPENROUTER_API_KEY = "your-openrouter-key"
$env:RETAIL_BACKEND_URL = "http://localhost:8080"
mvn spring-boot:run
```

Alternatively configure `GEMINI_API_KEY`. In Docker Compose, pass provider keys through the deployment secret mechanism and set `RETAIL_BACKEND_URL=http://backend:8080` using the actual Compose service name.

The service listens on port 8090 by default. `GET /actuator/health` is available for health checks. Configure `SERVER_PORT` to change the port. Startup now requires database access for saved conversation history and applies Flyway migrations automatically.

Chat logs share a request ID (`X-Request-Id`) across the backend, processor, and backend data call. INFO records model/operation metadata, status, result counts, and timings. DEBUG adds the full chat request with `actorToken` redacted, the backend data query JSON, and the outbound model JSON body. The model body contains the user question and retrieved retail context; enable DEBUG only when needed and avoid retaining or sharing those logs.

## Backend contract

`POST /internal/v1/chat` accepts JSON and requires internal HMAC headers:

```json
{
  "question": "Which products are low on stock?",
  "modelId": "openrouter/openrouter/free",
  "conversationId": "optional-client-or-backend-generated-id",
  "user": {
    "userId": "backend-user-id",
    "role": "OWNER",
    "actorToken": "short-lived-backend-signed-assertion"
  }
}
```

Successful response (the processor generates the conversation ID for a new chat):

```json
{
  "answer": "...",
  "conversationId": "processor-generated-uuid",
  "modelId": "openrouter/openrouter/free",
  "dataSources": ["inventory"]
}
```

The backend must sign `GET /internal/v1/models` using the same HMAC headers and canonical signing format as the chat POST (empty request body). Use the returned model IDs in the backend's model selector and send the chosen ID with every chat request.

The backend must derive `userId` and `role` from the validated JWT/server-side principal. It must never forward frontend-supplied identity as trusted context. This service accepts `OWNER` and `RECEPTIONIST`; downstream data operations must independently enforce the role rules.

Generate a unique nonce for each call. Hash the exact UTF-8 request body with SHA-256, then sign the UTF-8 bytes of `POST\n/internal/v1/chat\n{unix-epoch-seconds}\n{nonce}\n{lowercase-body-sha256}` with HMAC-SHA256 using `AI_PROCESSOR_SHARED_SECRET`. Send the lowercase hex digest in `X-Internal-Signature`, the timestamp in `X-Internal-Timestamp`, and the nonce in `X-Internal-Nonce`. Requests more than 60 seconds from service time are rejected by default; nonces are retained in process memory for the clock-skew window. Keep the endpoint on a private network and use TLS across network boundaries. Rotate the shared secret in both services together.

The endpoint is synchronous JSON. It does not stream tokens. The backend can later add an SSE endpoint/response contract if streaming is required.

The authenticated backend also exposes `GET /api/chat/conversations` and `GET /api/chat/conversations/{conversationId}` for the current user's saved chats. These are proxied to signed internal processor endpoints; conversation ownership is scoped by backend user ID. The processor sends at most the latest 12 stored messages to the selected model as context on each turn, while retaining the full conversation for history display.

## Approved data interface

For each non-greeting request, the selected chat model first creates a structured read plan from the user's question and recent conversation. The processor accepts only the backend's allowlisted operation names and validated query parameters; general questions use `NONE` and go straight to the answer step. The backend receives the plan, verifies the short-lived actor assertion and current role, and enforces OWNER-only sales and low-stock access. The processor never executes model-generated SQL and does not connect directly to the retail database. Planning and answering each use the selected model, so each non-greeting request makes two model calls.

The provider `ChatEngine`s use retrieved backend results as the only source for current business figures and enforce provider timeouts. Never execute model-generated SQL or write retail data.

## Deployment

Build and run with Docker:

```powershell
docker build -t retail-ai-processor .
docker run --rm -p 8090:8090 -e AI_PROCESSOR_SHARED_SECRET="<shared-secret>" retail-ai-processor
```

No Docker Compose file exists in the provided project. When added to the deployment, configure the retail backend's `AI_PROCESSOR_URL` as `http://ai-processor:8090` on the private Compose network. Do not add Eureka for the initial deployment.

## Retail backend integration

The retail backend exposes authenticated `POST /api/chat`, `GET /api/chat/conversations`, and `GET /api/chat/conversations/{id}` routes and the private HMAC-authenticated `POST /internal/v1/ai-data/query` interface. The frontend should call only the backend routes; processor endpoints must remain private. Configure the backend's `AI_PROCESSOR_URL` as `http://ai-processor:8090` on a Compose network and configure the processor's `RETAIL_BACKEND_URL` using the backend service name. The response is synchronous JSON; streaming is not implemented.


$env:LOGGING_LEVEL_COM_RETAIL_SERVICE_CHATSERVICE = "DEBUG"
$env:LOGGING_LEVEL_COM_RETAIL_AIPROCESSOR = "DEBUG"
