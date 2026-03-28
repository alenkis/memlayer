# memlayer

Memlayer gives AI agents persistent memory backed by a local knowledge graph. It combines semantic search, temporal queries, and automatic entity extraction behind an [MCP](https://modelcontextprotocol.io/) interface, so any compatible agent can retain and recall information across conversations.

When you tell your agent to remember something, memlayer uses an LLM to extract entities and relationships from the text, stores vector embeddings for semantic search, and builds a knowledge graph linking related concepts together. Everything runs locally on your machine — the only external calls are to LLM APIs (OpenAI for embeddings, Groq for entity extraction and decisions).

## Install

### Homebrew (recommended)

```bash
brew install memlayer/tap/memlayer
```

### Download binary

Grab the latest native binary from [Releases](https://github.com/memlayer/memlayer/releases). It's a single executable, no Java or Clojure required.

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

Or create a `.env` file in the directory you run memlayer from:

```
OPENAI_API_KEY=sk-...
GROQ_API_KEY=gsk_...
```

In terms of cost, a typical retain operation runs about $0.001 and recall is essentially free at ~$0.00001. With moderate usage (say 50 retains and 200 recalls a day), you'd be looking at roughly $1.50/month.

## Usage

### Start the server

```bash
memlayer server    # HTTP API + dashboard on port 8090
```

Open http://localhost:8090 to see the dashboard where you can browse memories, visualize the knowledge graph, and test operations in the playground.

To use a different port: `MEMLAYER_PORT=9090 memlayer server`

### Connect to Claude Code

```bash
claude mcp add memlayer -- memlayer
```

This registers memlayer as an MCP server, giving your agent access to these tools:

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

Settings live in environment variables or `.env`:

| Variable | Default | Description |
|----------|---------|-------------|
| `MEMLAYER_PORT` | `8090` | HTTP server port |
| `OPENAI_API_KEY` | — | Required. For embeddings |
| `GROQ_API_KEY` | — | Required. For extraction and decisions |
| `OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding model |
| `GROQ_MODEL` | `llama-3.3-70b-versatile` | LLM model |
| `DATAHIKE_PATH` | `~/.memlayer/db` | Database location |
| `PROXIMUM_PATH` | `~/.memlayer/vectors` | Vector index location |

## Using as a Clojure library

Add the core library to your `deps.edn`:

```clojure
{:deps {io.github.memlayer/memlayer {:git/url "https://github.com/memlayer/memlayer.git"
                                     :git/sha "..."}}}
```

Then use the operations directly:

```clojure
(require '[memlayer.operations.retain :as retain])
(require '[memlayer.operations.recall :as recall])

;; Store a memory
(retain/retain! system {:content "Project uses PostgreSQL 16"
                        :source  "architecture-review"
                        :namespace "my-project"})

;; Search memories
(recall/recall system {:query "what database do we use?"
                       :namespace "my-project"})
```

All operations live under `memlayer.operations.*` namespaces. See `deps.edn` for the full dependency list.

## Running the JAR directly

If you prefer the JVM over the native binary (e.g., for debugging or profiling):

```bash
bb uberjar   # or: clojure -T:build uberjar
java --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -cp target/memlayer.jar memlayer.local          # HTTP server
java --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -cp target/memlayer.jar memlayer.mcp.server      # MCP server
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
