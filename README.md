# memlayer

Memlayer gives AI agents persistent memory backed by a local knowledge graph. It combines semantic search, temporal queries, and automatic entity extraction behind an [MCP](https://modelcontextprotocol.io/) interface, so any compatible agent can retain and recall information across conversations.

When you tell your agent to remember something, memlayer uses an LLM to extract entities and relationships from the text, stores vector embeddings for semantic search, and builds a knowledge graph linking related concepts together. Everything runs locally on your machine — the only external calls are to LLM APIs (OpenAI for embeddings, Groq for entity extraction and decisions).

## Install

### Homebrew (recommended)

```bash
brew install alenkis/tap/memlayer
```

### Download binary

Grab the latest native binary from [Releases](https://github.com/alenkis/memlayer/releases). It's a single executable, no Java or Clojure required.

### From source (Clojure/Java developers)

If you already have Java 22+ and [Clojure CLI](https://clojure.org/guides/install_clojure):

```bash
git clone https://github.com/memlayer/memlayer.git
cd memlayer
bb uberjar                    # builds target/memlayer.jar
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -cp target/memlayer.jar memlayer.local
```

Or use [babashka](https://babashka.org/) for the full dev experience: `bb server`.

## Setup

Memlayer needs two API keys for LLM operations:

```bash
export OPENAI_API_KEY=sk-...   # embeddings (~$0.02/1M tokens)
export GROQ_API_KEY=gsk_...    # extraction & decisions (~$0.59/1M tokens)
```

Or create a `.env` file — memlayer automatically loads it from the current directory:

```
OPENAI_API_KEY=sk-...
GROQ_API_KEY=gsk_...
```

You can also point to an env file explicitly:

```bash
memlayer --env-file ~/.memlayer/.env server
```

In terms of cost, a typical retain operation runs about $0.001 and recall is essentially free at ~$0.00001. With moderate usage (say 50 retains and 200 recalls a day), you'd be looking at roughly $1.50/month.

## Usage

### Connect to Claude Code

```bash
claude mcp add memlayer -- memlayer
```

That's it. When Claude first calls a memlayer tool, the MCP process automatically starts the server in the background. You don't need to manage the server yourself.

The dashboard is available at http://localhost:8090 while the server is running.

| Tool | What it does |
|------|-------------|
| `memlayer_retain` | Store a memory — extracts entities, deduplicates, builds graph |
| `memlayer_recall` | Semantic search + graph traversal to find relevant memories |
| `memlayer_reflect` | Consolidate scattered facts into organized concepts |
| `memlayer_forget` | Permanently delete an entity and all its data |

Try telling your agent: *"Remember that our API uses pagination with cursor tokens"* — then later ask *"How does our API handle pagination?"*

### Connect to Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "memlayer": {
      "command": "memlayer",
      "args": []
    }
  }
}
```

### Namespaces

Namespaces let you keep separate memory spaces — for different projects, clients, or contexts. By default, everything goes into the `default` namespace.

**Set a namespace when registering the MCP server:**

```bash
claude mcp add memlayer-work -- memlayer --namespace work
claude mcp add memlayer-personal -- memlayer --namespace personal
```

All tool calls from that session are automatically scoped to the configured namespace. The agent can't accidentally read or write to a different one.

**Switch namespace mid-session:** Tell your agent *"switch to the personal namespace"* and it will call `memlayer_set_namespace`. All subsequent operations in that session use the new namespace.

**Multiple agents, separate memories:** Register each agent with its own namespace. They share the same server and database, but their memories don't overlap.

```bash
# Claude Code gets "work" memories
claude mcp add memlayer -- memlayer --namespace work

# Codex gets "codex" memories
# (in your Codex MCP config, pass --namespace codex)
```

### Start the server manually

You can also start the server manually if you prefer:

```bash
memlayer server                # HTTP API + dashboard on port 8090
memlayer server --port 9090    # use a different port
```

You can also set the port via environment variable: `MEMLAYER_PORT=9090 memlayer server`. CLI flags take precedence over environment variables.

### Server lifecycle

The server follows the Gradle/Watchman daemon pattern:

- **Auto-start**: The first MCP client that connects starts the server automatically if it's not already running.
- **Shared**: Multiple MCP clients (e.g., multiple Claude Code sessions) share the same server and database. The dashboard always shows up-to-date data.
- **Idle timeout**: The server shuts itself down after 30 minutes of inactivity. Any API request or dashboard interaction resets the timer. Override with `MEMLAYER_IDLE_TIMEOUT_MINUTES`.
- **Crash recovery**: If the server crashes, the next MCP tool call automatically restarts it.

State is stored at `~/.memlayer/` — PID file, database, and vector index all live there.

### HTTP API

All endpoints are under `/api/v1/`:

```bash
# Store a memory
curl -X POST http://localhost:8090/api/v1/retain \
  -H "Content-Type: application/json" \
  -d '{"content": "The deploy pipeline uses GitHub Actions", "source": "ops-chat"}'

# Search memories
curl -X POST http://localhost:8090/api/v1/recall \
  -H "Content-Type: application/json" \
  -d '{"query": "how do we deploy?"}'

# Temporal query — what did we know last Tuesday?
curl -X POST http://localhost:8090/api/v1/recall \
  -H "Content-Type: application/json" \
  -d '{"query": "deployment process", "as-of": "2026-03-24T00:00:00Z"}'
```

## Key concepts

### Knowledge graph with semantic layers

Memories are organized into a hierarchy of domains, concepts, facts, and episodes. When you retain something, memlayer classifies it and places it within this structure automatically:

```
Domain ("Programming")
├── Concept ("Type Systems")
│   ├── Fact ("Clojure uses persistent data structures")
│   └── Episode ("Discussed monads on 2026-03-15")
└── Summary (synthesized overview)
```

### Temporal queries

Every change is recorded as an immutable transaction, which means you can use `as-of` to query the state of the knowledge graph at any point in time. This is useful for debugging ("what did the agent know when it made that decision?") and for audit trails.

### Namespace isolation

Each agent or project can have its own namespace with an independent knowledge graph. Namespaces don't share data, so you can run memlayer for multiple projects without them interfering with each other.

### LLM-driven entity extraction

When you retain a memory, an LLM analyzes the content and decides whether to create new facts, update existing ones, or link related concepts together. You write natural language; memlayer takes care of structuring it into the graph.

## Storage

All data lives locally at `~/.memlayer/`:

| Path | Contents |
|------|----------|
| `~/.memlayer/db` | Datahike database (memories, relationships) |
| `~/.memlayer/vectors` | Proximum vector index (embeddings) |

You can override these paths with the `DATAHIKE_PATH` and `PROXIMUM_PATH` environment variables.

## Configuration

Settings live in environment variables, `.env`, or CLI flags. CLI flags take precedence over environment variables.

| Variable | CLI flag | Default | Description |
|----------|----------|---------|-------------|
| `MEMLAYER_PORT` | `--port` | `8090` | HTTP server port |
| `OPENAI_API_KEY` | — | — | Required. For embeddings |
| `GROQ_API_KEY` | — | — | Required. For extraction and decisions |
| `OPENAI_EMBEDDING_MODEL` | — | `text-embedding-3-small` | Embedding model |
| `GROQ_MODEL` | — | `llama-3.3-70b-versatile` | LLM model |
| `DATAHIKE_PATH` | — | `~/.memlayer/db` | Database location |
| `PROXIMUM_PATH` | — | `~/.memlayer/vectors` | Vector index location |

## Using as a Clojure library

Add memlayer to your `deps.edn`:

```clojure
;; From Clojars (versioned releases)
{:deps {dev.memlayer/core {:mvn/version "0.1.0"}}}

;; Or from git (bleeding edge)
{:deps {io.github.memlayer/memlayer {:git/url "https://github.com/memlayer/memlayer.git"
                                     :git/sha "..."}}}
```

Proximum requires Java 22+ with incubator vector support:

```clojure
:aliases {:your-app {:jvm-opts ["--add-modules" "jdk.incubator.vector"
                                "--enable-native-access=ALL-UNNAMED"]}}
```

### System initialization

All operations take a `deps` map. Build it from the config:

```clojure
(require '[memlayer.config :as config])
(require '[memlayer.persistence.datahike :as datahike])
(require '[memlayer.persistence.proximum :as proximum])
(require '[memlayer.provider.openai :as openai])
(require '[memlayer.provider.groq :as groq])

(def cfg (config/load-config))

(def deps
  {:db                 (datahike/->DatahikeEntityStore
                         (datahike/create-connection! (:datahike cfg)))
   :vector-index       (atom (proximum/->ProximumVectorStore
                               (proximum/create-index! (:proximum cfg))
                               (:proximum cfg)))
   :embedding-provider (openai/create-client (:openai cfg))
   :chat-provider      (groq/create-client (:groq cfg))
   :prompts            (:prompts cfg)
   :tuning             (:tuning cfg)})
```

### Operations

**Retain** — store memories via the async retention flow:

```clojure
(require '[memlayer.operations.flow.retention-flow :as flow])

(def retain-flow (flow/start-standalone! deps cfg))

(flow/submit! retain-flow {:items     [{:content "Project uses PostgreSQL 16"
                                        :source  "architecture-review"}]
                           :namespace "my-project"})
```

**Recall** — semantic search over memories:

```clojure
(require '[memlayer.operations.recall :as recall])

(recall/recall! deps {:query     "what database do we use?"
                      :namespace "my-project"})
```

**Reflect** — organize and connect knowledge:

```clojure
(require '[memlayer.operations.reflect :as reflect])

(reflect/reflect! deps {:namespace "my-project"})
```

**Forget** — remove memories:

```clojure
(require '[memlayer.operations.forget :as forget])

(forget/forget! deps {:memory-id "..."})  ; retracted, preserved in history
(forget/evict!  deps {:memory-id "..."})  ; permanent removal (GDPR)
```

### Shutdown

```clojure
(flow/stop-standalone! retain-flow)
(datahike.api/release (:conn (:db deps)))
```

## Running the JAR directly

If you prefer the JVM over the native binary (e.g., for debugging or profiling):

```bash
bb uberjar   # or: clojure -T:build uberjar
java --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -cp target/memlayer.jar memlayer.local                       # HTTP server
java --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -cp target/memlayer.jar memlayer.local --port 9090           # custom port
java --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -cp target/memlayer.jar memlayer.mcp.server                  # MCP server
java --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -cp target/memlayer.jar memlayer.mcp.server --namespace work # MCP with namespace
```

Requires Java 22+ (for vector operations). Install via `brew install openjdk` or [Adoptium](https://adoptium.net/).

## Development

For contributors working on memlayer itself.

### Prerequisites

- Java 22+ (`brew install openjdk`)
- [Clojure CLI](https://clojure.org/guides/install_clojure) (`brew install clojure/tools/clojure`)
- [Babashka](https://babashka.org/) (`brew install borkdude/brew/babashka`)
- Node.js (for dashboard and e2e tests)

### Commands

| Command | Description |
|---------|-------------|
| `bb server` | Start HTTP API + bundled dashboard |
| `bb dev` | Start API + dashboard hot-reload + CSS watcher |
| `bb mcp` | Start MCP stdio server |
| `bb test` | Run unit tests |
| `bb check` | Build + lint + format check + unit tests |
| `bb test-full` | Full suite including integration and e2e (expensive) |
| `bb uberjar` | Build distributable JAR |
| `bb native-image` | Build GraalVM native binary |
| `bb fmt` | Format all Clojure files |
| `bb tasks` | See all available tasks |

## License

AGPL-3.0 — see [LICENSE](LICENSE) for details.
