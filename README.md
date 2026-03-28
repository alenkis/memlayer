# memlayer

Persistent memory for AI agents. Provides an immutable knowledge graph via MCP.

## Quick Start

### Prerequisites

- Java 22+ ([Adoptium](https://adoptium.net/) or `brew install openjdk`)
- [Clojure CLI](https://clojure.org/guides/install_clojure) (`brew install clojure/tools/clojure`)
- An [OpenAI API key](https://platform.openai.com/api-keys) (for embeddings)
- A [Groq API key](https://console.groq.com/keys) (for LLM operations)

### 1. Environment Setup

You need to provide `OPENAI_API_KEY` and `GROQ_API_KEY`. Choose one of:

**Option A: Plain `.env` file**

```bash
cp .env.example .env
# Edit .env and fill in OPENAI_API_KEY and GROQ_API_KEY
```

**Option B: 1Password CLI**

If you use [1Password CLI](https://developer.1password.com/docs/cli/), secrets are managed via `.env.1p`:

```bash
bb env
```

### 2. Start the Server

```bash
bb server
```

Verify the API is healthy:

```bash
curl http://localhost:8080/health
```

| Service | URL |
|---------|-----|
| API server | http://localhost:8080 |
| Dashboard (CLJS) | http://localhost:3000 (run `bb dashboard-dev`) |

### 3. Connect Your MCP Client

memlayer exposes memory tools via [MCP](https://modelcontextprotocol.io/) over stdio transport.

#### Claude Code

Use the memlayer plugin or add manually:

```bash
claude mcp add memlayer -- bb mcp
```

#### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "memlayer": {
      "command": "bb",
      "args": ["mcp"],
      "cwd": "/path/to/memlayer"
    }
  }
}
```

### Claude Code Plugin (Recommended)

Install the memlayer plugin for proactive memory management:

```bash
export MEMLAYER_API_KEY=mlk_your_key_here

# Install from marketplace
/plugin marketplace add memlayer/claude-plugin
/plugin install memlayer
```

### 4. Try It

Once connected, your AI agent has memory tools:

| Tool | Description |
|------|-------------|
| `memlayer_retain` | Store a memory |
| `memlayer_recall` | Search and retrieve memories |
| `memlayer_reflect` | Consolidate and summarize memories |
| `memlayer_forget` | Delete entities and their data |

Try:
- "Remember that my favorite editor is Emacs"
- "What's my favorite editor?"

### 5. Dashboard

Start the ClojureScript dashboard:

```bash
bb dashboard-deps   # first time only
bb dashboard-dev    # start dev server (port 3000)
bb dashboard-css    # in another terminal: watch Tailwind CSS
```

Features: memory browser, graph visualization, playground, usage stats.

## Local Mode

Run memlayer as a self-contained local process — no cloud services, no auth, file-backed storage at `~/.memlayer/`.

### Prerequisites

- Same as above, plus [GraalVM 25+](https://www.graalvm.org/) for native binary builds

### Run via JVM

```bash
bb local-server
```

### Build Native Binary

```bash
bb local-native-image        # builds GraalVM native binary
./target/memlayer-local      # run it
```

The native binary starts in milliseconds, needs no JVM, and bundles everything (API, MCP, dashboard).

### Build Steps (manual)

| Command | Description |
|---------|-------------|
| `bb local-server` | Run local mode via JVM |
| `bb local-uberjar` | Build JVM uberjar (includes dashboard) |
| `bb local-native-uberjar` | Build uberjar for GraalVM native-image |
| `bb local-native-image` | Build GraalVM native binary from uberjar |

### What's different in local mode

- **No auth** — all endpoints are open (`auth-enabled false`)
- **No Firebase, DynamoDB, or Stratum** — removed from Integrant system
- **File-backed storage** — Datahike at `~/.memlayer/db`, Proximum at `~/.memlayer/vectors`
- **Port 8090** by default (override with `MEMLAYER_PORT`)
- **Still requires** `OPENAI_API_KEY` and `GROQ_API_KEY` for embeddings and LLM operations

## Development

### Prerequisites

- Java 22+ (required for Proximum vector operations)
- Clojure CLI tools
- Node.js (for ClojureScript dashboard and e2e tests)

### Commands

| Command | Description |
|---------|-------------|
| `bb server` | Start HTTP API server |
| `bb mcp` | Start MCP stdio server |
| `bb repl-server` | Start dev REPL |
| `bb test` | Run unit tests |
| `bb check` | Build + lint + test |
| `bb lint` | Run clj-kondo linter |
| `bb dashboard-dev` | CLJS dashboard dev server |
| `bb dashboard-build` | Production CLJS build |
| `bb uberjar` | Build distributable uberjar |
| `bb local-server` | Run local mode (no auth, file-backed) |
| `bb local-native-image` | Build GraalVM native binary |
| `bb tasks` | See all available tasks |

### Testing

```bash
# Unit tests
bb test

# Specific test namespace
bb test-focus memlayer.operations.retain-test

# Integration tests (requires .env with API keys)
bb test-integration

# Playwright e2e (requires running server + .env)
bb test-e2e
```

### Workflow

- Use git worktrees: `git worktree add ../memlayer-<feature> -b <branch>`
- Run `/check-server-version` before E2E tests
- Read `CLAUDE.md` for full coding conventions
