# MemLayer Product Vision

## Target Users
Companies building agentic systems with long-running agents that need persistent memory.

## Core Differentiator
Immutable database with temporal queries enabling:
- Knowledge graph modeling
- Temporal reasoning (`as-of` queries)
- Audit trails and debugging for users

## Core Operations

### Retain
Store incoming memories with entity recognition:

1. **Extract** entities from incoming content
2. **Match** against existing entities in the knowledge graph
3. **Decide** operation per entity: `create | update | noop`

### Recall
Semantic search finds entry point, then graph traversal provides context. Supports temporal queries.

### Reflect
Query-scoped consolidation of a graph subset:
- Create missing layers (e.g., Concepts for orphaned Facts)
- Draw conclusions and create new memories
- Reorganize relationships between nodes

### Forget
Permanent removal of entities from the knowledge graph.

**Purpose**: Data lifecycle management, privacy compliance (GDPR right-to-be-forgotten), and explicit user-controlled deletion. Separated from Retain to avoid ambiguity in LLM-driven deletion decisions.

**Implications**:
- Cascading removal: all memory versions, embeddings, relationships, and closure entries
- Permanent: no soft-delete, entity is fully purged
- Auditable: caller explicitly requests deletion with entity_id
- Caller-driven: the agent decides what to forget, MemLayer executes

## Semantic Layers

4 layers from abstract to specific (0-3 in API):

| Layer | Name    | Description |
|-------|---------|-------------|
| 0     | Domain  | Abstract categories (e.g., "risk management") |
| 1     | Concept | Grouped knowledge (e.g., "financial instruments") |
| 2     | Fact    | Specific knowledge (e.g., "worked on instrument X") |
| 3     | Episode | Time-bound specific events |

**Purpose**: Specific memories may not recall well via semantic search alone. Nesting under higher-level memories enables:
- Navigate up to abstract concepts
- Drill down to specific details
- Better recall via graph traversal

## Knowledge Graph

- Relationships are flexible, not prescribed
- Could expose relationship creation through API
- Agents might define their own relationship types
- Graph is ever-evolving, imitating human memory

## Temporal Queries

- **Transaction time**: When the system recorded the fact (tracked by Datahike)
- Enables: "What did the agent know at time T?" queries via `as-of`
- Full audit trail through Datahike transaction history

## Memory Namespace

Each agent operates in an isolated memory namespace. This enables:
- Independent knowledge graphs per agent
- No cross-contamination between agents
- Clean testing/debugging per agent

## LLM Integration

LLMs are used for:
- **Entity extraction**: Identify entities in incoming content
- **Embedding generation**: Vector representations for semantic search
- **Layer classification**: Determine appropriate semantic layer
- **Relationship inference**: Suggest connections between memories
- **Reflect operations**: Analyze graph subset, create missing layers, draw conclusions
