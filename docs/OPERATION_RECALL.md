# RECALL Operation

Uses semantic search (vector similarity) combined with the memory layer (Datahike) to recall relevant memories.

## Goal

Given a natural language query, recall relevant memories by combining:

1. **Semantic search**: Vector similarity to find conceptually related memories
2. **Memory layer (Datahike)**: Structured storage with relationships and hierarchy
3. **Filtering**: Layer, entity, temporal constraints
4. **Graph traversal**: Follow relationships to discover related memories beyond direct semantic matches

## Current Data Flow

```
Query Text
    |
    v
+-------------------------------------+
| 1. Generate Embedding               |
|    openai/embed                      |
|    -> 1536-dim vector (OpenAI)       |
+-------------------------------------+
    |
    v
+-------------------------------------+
| 2. Vector Similarity Search          |
|    proximum/search -> Proximum       |
|    Returns: [(memory-id, score)]     |
|    Limit: recall-limit * 2           |
+-------------------------------------+
    |
    v
+-------------------------------------+
| 3. Fetch Full Memories               |
|    For each result:                  |
|      datahike/get-memory(id)         |
+-------------------------------------+
    |
    v
+-------------------------------------+
| 4. Apply Filters                     |
|    - Minimum score threshold         |
|    - Layer filter (domain/concept/   |
|      fact/episode)                   |
|    - Entity filter                   |
|    - Temporal filter (as-of)         |
+-------------------------------------+
    |
    v
+-------------------------------------+
| 5. Temporal Re-ranking               |
|    Superseded memories (valid-to     |
|    set) get 30% score penalty        |
+-------------------------------------+
    |
    v
+-------------------------------------+
| 6. Fetch Relationships               |
|    For each match:                   |
|      datahike/get-relationships(id)  |
|    (returned as metadata only)       |
+-------------------------------------+
    |
    v
RecallResponse {:memories [...] :usage {...}}
```

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `memlayer.operations.recall` | Core recall logic |
| `memlayer.persistence.datahike` | Memory storage |
| `memlayer.persistence.proximum` | Vector search |
| `memlayer.provider.openai` | Embedding generation |
| `memlayer.api.recall` | HTTP handler |

## Entry Points

- **API**: `POST /api/v1/recall` with `{:query "..." :limit N :threshold F}`
  - Defined in `memlayer.router`
  - Handler in `memlayer.api.recall`

- **Core**: `memlayer.operations.recall/recall`

## Known Issues / TODOs

### 1. No graph traversal (biggest gap)

Current flow:

1. Vector search -> semantic matches
2. Fetch relationships -> returned as metadata only

Problem: We don't **explore** the graph. A semantically similar memory might have relationships pointing to highly relevant memories that wouldn't match the query directly.

Proposed enhancement:

1. Get initial semantic matches
2. Traverse relationships (configurable depth, e.g. 1-2 hops)
3. Score discovered nodes (relationship strength x semantic relevance?)
4. Merge with direct matches, dedupe, re-rank

## Temporal Query Support

Datahike records every transaction. The `as-of` parameter queries the knowledge graph at a prior point in time, returning memories as they existed then.
