# Retain Operation

## Goal

Ingest incoming content into the knowledge graph by:
1. Finding relevant existing memories
2. Extracting atomic facts from the content
3. Deciding how each fact integrates with existing knowledge
4. Executing the integration

## Pipeline

```
INPUT: Raw content
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  PHASE 1: Find Relevant Memories                            │
│                                                             │
│  Given incoming content, find the most relevant memories    │
│  from the knowledge graph to inform subsequent phases.      │
│                                                             │
│  1. Generate embedding of incoming content                  │
│  2. Semantic search for top-k similar memories              │
│  3. Graph traversal from anchor points:                     │
│       - Parents (concepts/domains)                          │
│       - Siblings (related facts under same parent)          │
│       - Children (more specific details)                    │
│       - Relationship edges                                  │
│                                                             │
│  Output: Relevant memories with graph relationships         │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  PHASE 2: Extract Atomic Memories                           │
│                                                             │
│  Break raw content into atomic, storable facts.             │
│                                                             │
│  1. LLM receives: raw content + relevant memories           │
│  2. LLM extracts atomic statements (one fact per memory)    │
│  3. LLM classifies layer (fact/episode) and importance      │
│  4. LLM resolves temporal references ("yesterday" → date)   │
│                                                             │
│  Output: Candidate memories with metadata                   │
└─────────────────────────────────────────────────────────────┘
         │
         ▼  (for each candidate)
┌─────────────────────────────────────────────────────────────┐
│  PHASE 3: Match Against Existing                            │
│                                                             │
│  Find if this fact already exists (possibly in diff form).  │
│                                                             │
│  1. Generate embedding for candidate                        │
│  2. Search within relevant neighborhood (scoped)            │
│  3. For high-similarity matches: fetch full memory + history│
│  4. Rank by: similarity × recency × importance              │
│                                                             │
│  Output: Candidate + ranked matches with scores             │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  PHASE 4: Decide Operation                                  │
│                                                             │
│  Determine how to integrate this fact into the graph.       │
│                                                             │
│  LLM decides based on:                                      │
│    - Semantic similarity to existing memories               │
│    - Entity identity (same entity or different?)            │
│    - Temporal relationship (correction? update? new?)       │
│    - Source reliability and recency                         │
│                                                             │
│  Operations:                                                │
│    CREATE  - New fact, no existing equivalent               │
│    UPDATE  - Same entity, newer info supersedes             │
│    MERGE   - Combine with existing (add detail)             │
│    LINK    - Create relationship only                       │
│    NOOP    - Duplicate or irrelevant, skip                  │
│                                                             │
│  Output: Operation + target + content + relationships       │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  PHASE 5: Execute                                           │
│                                                             │
│  Atomically update memory store + vector store.             │
│                                                             │
│  1. Execute decided operation                               │
│  2. Link to parent concept (create if needed)               │
│  3. Create relationships to related memories                │
│                                                             │
│  Output: Created/updated memory + relationships             │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  PHASE 6: Consolidation Check (async)                       │
│                                                             │
│  Maintain graph health after changes.                       │
│                                                             │
│  If thresholds exceeded:                                    │
│    - Too many unlinked facts → concept extraction           │
│    - Too many concepts → domain consolidation               │
│    - Contradictions detected → flag for resolution          │
│                                                             │
│  Output: Queued consolidation tasks                         │
└─────────────────────────────────────────────────────────────┘
```

## Graph Traversal Strategies

Phase 1 traversal from semantic search anchor points is a design decision. Options include:

- **BFS/DFS** with depth limits
- **Weighted traversal** by relationship strength
- **Layer-aware** expansion (up to concepts, down to episodes)
- **Query-type specific** strategies

The strategy affects what context the extraction and decision phases receive.

## Decision Model

The decision (Phase 4) is non-deterministic. The same input can reasonably produce different outcomes:

- "Alice works at Beta" vs existing "Alice works at Acme"
  - Same Alice, job changed → UPDATE
  - Different Alice → CREATE
  - User correcting themselves → UPDATE
  - Different source, months apart → maybe CREATE

We guarantee invariants regardless of which path is taken:

| Operation | Invariants |
|-----------|------------|
| CREATE | New memory ID, both old and new exist |
| UPDATE | Same entity, history preserved, old accessible via temporal query |
| MERGE | Content combined, single memory remains |
| LINK | No content change, relationship created |
| NOOP | No changes |

## Temporal Queries

Every memory change is recorded as a Datahike transaction. UPDATE retracts the old memory and creates a new one; both versions remain accessible through `as-of` queries and the history endpoint.
