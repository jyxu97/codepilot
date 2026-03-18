# CodePilot

An autonomous multi-agent platform for automated Java bug repair, powered by Claude and the CodeAct paradigm.

## Architecture

```
                         ┌─────────────────────────────────────────────┐
                         │            Orchestrator (Java/Spring Boot)  │
                         │                                             │
  POST /jobs ──────────► │  JobController ──► JobService               │
                         │                      │                      │
                         │               StepScheduler (4 workers)     │
                         │                      │                      │
                         │         ┌────────────┼────────────┐         │
                         │         ▼            ▼            ▼         │
                         │     AgentLoop   AgentLoop    AgentLoop      │
                         │         │            │            │         │
                         │         ▼            ▼            ▼         │
                         │              ClaudeClient                   │
                         │          (Anthropic Messages API)           │
                         └──────────────────┬──────────────────────────┘
                                            │  HTTP
                         ┌──────────────────▼──────────────────────────┐
                         │            Executor (Python/FastAPI)         │
                         │                                             │
                         │  /workspace/create    /workspace/run_code   │
                         │  /workspace/snapshot  /workspace/restore    │
                         │                                             │
                         │         ┌─── Three-Layer Sandbox ───┐       │
                         │         │ 1. AST import validator   │       │
                         │         │ 2. Restricted builtins    │       │
                         │         │ 3. K8s pod isolation      │       │
                         │         └───────────────────────────┘       │
                         └─────────────────────────────────────────────┘
                                            │
                         ┌──────────────────▼──────────────────────────┐
                         │            PostgreSQL 16                     │
                         │  Jobs, Steps, Conversation History          │
                         └─────────────────────────────────────────────┘

  ── 6-Stage Pipeline ──────────────────────────────────────────────────
  REPO_MAPPER → PLANNER → IMPLEMENTER → TESTER → REVIEWER → FINALIZER
```

Each agent stage runs a multi-turn conversation with Claude. Agents emit executable Python code (CodeAct style) instead of JSON tool calls, enabling self-correction through direct observation of execution results.

## Tech Stack

| Layer         | Technology                                    |
|---------------|-----------------------------------------------|
| Control plane | Java 21, Spring Boot 3.4, Spring Data JPA     |
| Execution plane | Python 3.13, FastAPI, Pydantic              |
| Database      | PostgreSQL 16, Flyway migrations              |
| LLM           | Claude (Anthropic Messages API, no SDK)       |
| Infra         | Docker, Kubernetes (kustomize), HPA           |
| Observability | Logback (JSON), Micrometer, Prometheus        |

## Project Structure

```
CodePilot/
├── orchestrator/                # Java Spring Boot control plane
│   ├── src/main/java/.../
│   │   ├── api/                 #   REST controllers & DTOs
│   │   ├── service/             #   JobService, StepScheduler
│   │   ├── agent/               #   AgentLoop, response parser, system prompts
│   │   ├── claude/              #   Claude API client (raw HttpClient)
│   │   ├── skill/               #   Skill interface & implementations
│   │   ├── executor/            #   WorkspaceClient (HTTP → executor)
│   │   ├── model/               #   JPA entities (Job, Step, AgentRole)
│   │   └── repository/          #   Spring Data repositories
│   ├── src/main/resources/
│   │   └── db/migration/        #   Flyway SQL migrations (V1–V5)
│   ├── docker-compose.yml       #   Local dev: postgres + executor + orchestrator
│   ├── Dockerfile               #   Multi-stage build (Maven → JRE)
│   └── pom.xml
├── executor/                    # Python FastAPI execution plane
│   ├── api/routes.py            #   Workspace & code execution endpoints
│   ├── sandbox/
│   │   ├── runner.py            #   Restricted execution engine
│   │   ├── tools.py             #   Injected tool functions
│   │   └── validator.py         #   AST-based import allowlist
│   ├── workspace/manager.py     #   Workspace lifecycle (create/snapshot/restore)
│   ├── Dockerfile
│   └── requirements.txt
├── k8s/                         # Kubernetes manifests (kustomize)
│   ├── kustomization.yaml
│   ├── orchestrator.yaml        #   Deployment + LoadBalancer Service
│   ├── executor.yaml            #   Deployment + ClusterIP Service + PVC
│   ├── postgres.yaml            #   StatefulSet
│   ├── networkpolicy.yaml       #   Egress restrictions
│   ├── rbac.yaml                #   Service account restrictions
│   └── hpa.yaml                 #   Executor autoscaler (1–4 replicas)
├── benchmark/                   # Evaluation suite
│   ├── smoke_test.py            #   End-to-end validation (21 checks)
│   ├── evaluate.py              #   Benchmark runner
│   └── tasks/                   #   Bug reproduction configs
└── CodePilot-design.md          # Design document
```

## Quick Start

**Prerequisites:** Docker and Docker Compose.

1. Set your Anthropic API key in `orchestrator/.env`:

   ```
   ANTHROPIC_API_KEY=sk-ant-...
   ```

2. Start all services:

   ```bash
   cd orchestrator
   docker compose up -d
   ```

   This starts PostgreSQL, the executor, and the orchestrator. Health checks ensure correct startup order.

3. Submit a repair job:

   ```bash
   curl -X POST http://localhost:8080/jobs \
     -H "Content-Type: application/json" \
     -d '{
       "repoUrl": "https://github.com/yvie97/commons-lang.git",
       "gitRef": "93f53a58604264ae105e2327a2b8713b84b296bb",
       "taskDescription": "Fix ArrayUtils.subarray integer overflow when endIndex > Integer.MAX_VALUE",
       "failingTest": "org.apache.commons.lang3.ArrayUtilsTest#testSubarrayInt"
     }'
   ```

4. Poll job status:

   ```bash
   curl http://localhost:8080/jobs/{id}
   curl http://localhost:8080/jobs/{id}/steps
   curl http://localhost:8080/jobs/{id}/report
   ```

## Kubernetes Deployment

**Prerequisites:** minikube and kubectl.

1. Start a cluster:

   ```bash
   minikube start
   ```

2. Build images inside minikube:

   ```bash
   eval $(minikube docker-env)
   docker build -t codepilot-orchestrator:latest orchestrator/
   docker build -t codepilot-executor:latest executor/
   ```

3. Edit `k8s/secret.yaml` with your base64-encoded Anthropic API key.

4. Deploy:

   ```bash
   kubectl apply -k k8s/
   ```

5. Access the service:

   ```bash
   minikube service orchestrator -n codepilot
   ```

## Smoke Test

The smoke test validates the full stack end-to-end (21 checks) without spending LLM credits:

```bash
cd benchmark
python3 smoke_test.py
```

To target a custom deployment:

```bash
python3 smoke_test.py \
  --orchestrator http://<host>:8080 \
  --executor http://<host>:8001
```

The test covers: service health, workspace lifecycle, sandbox code execution, tool reliability (write → read → diff → patch), and Maven build.
