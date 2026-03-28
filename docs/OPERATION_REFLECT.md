# Reflect Operation

## Goal

Reflect is the "thinking" operation. It analyzes the knowledge graph and improves it by:

1. **Organizing**: Categorize orphan memories under appropriate Concepts/Domains
2. **Synthesizing**: Draw inferences and create new memories from existing knowledge
3. **Restructuring**: Clean up relationship edges for better recall

## When to Use

- After bulk ingestion of facts
- Periodic maintenance to organize accumulated knowledge
- When recall quality degrades due to unorganized memories

## Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│  1. DISCOVER                                                │
│  Find memories that need attention (orphans, weak links)    │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  2. CLUSTER                                                 │
│  Group related memories by embedding similarity             │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  3. GATHER CONTEXT                                          │
│  Load existing Domains and Concepts for LLM context         │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  4. ANALYZE (LLM)                                           │
│  For each cluster, decide:                                  │
│  - Link to existing concept                                 │
│  - Create new concept/domain                                │
│  - Synthesize new memories from patterns                    │
│  - Restructure relationships                                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  5. PERSIST                                                 │
│  Apply changes to the knowledge graph                       │
└─────────────────────────────────────────────────────────────┘
```

## Semantic Hierarchy

```
Domain (layer 0)
  └── Concept (layer 1)
        └── Fact (layer 2)
              └── Episode (layer 3)
```

Example:
```
"Programming"                     (Domain)
  └── "Type Systems"              (Concept)
        ├── "Clojure has persistent data structures" (Fact)
        └── "Rust has ownership"  (Fact)
```

## Synthesis Example

```
Fact A: "All mammals are warm-blooded"
Fact B: "Whales are mammals"
  → Synthesize: "Whales are warm-blooded" (inferred)
```
