# Forget Operation

## Goal

Remove an entity from active use — it will no longer appear in recall results or be accessible through standard queries. This is a logical delete.

## When to Use

- Entity is no longer relevant and should not appear in recall
- Cleaning up test/invalid data

## Data Flow

```
POST /api/v1/forget {entity_id}
       |
       v
+--------------------------------------+
|      DATABASE (Transaction)          |
|  1. Collect memory IDs               |
|  2. Delete relationships             |
|  3. Delete memories (retract)        |
|                                      |
|  All-or-nothing via Datahike tx      |
+------------------+-------------------+
                   |
       +-----------+-----------+
       |                       |
       v                       v
ForgetResponse      +----------------------+
                    | VECTOR STORE (async) |
                    | Delete embeddings    |
                    | from Proximum        |
                    +----------------------+
```

## What Gets Removed

| Data | What | Storage |
|------|------|---------|
| `memories` | All entity's memories | Datahike |
| `relationships` | Links where entity is source or target | Datahike |
| Vector embeddings | Embeddings for each memory | Proximum |

## Guarantees

| Property | Guarantee |
|----------|-----------|
| Atomicity | Datahike transactions are atomic (all-or-nothing) |
| Idempotency | Forgetting non-existent entity succeeds with 0 counts |
| Consistency | Entity will not appear in future recall results |

## Edge Cases

**Non-existent entity**: Returns success with `deleted_count: 0`. This is intentional — makes the operation idempotent.

**Vector store failure**: Logged but doesn't fail the operation. Orphaned embeddings may exist temporarily. This prioritizes data integrity (Datahike is source of truth) over storage efficiency.

**Entity with relationships**: All relationships involving the entity's memories are deleted within the same transaction.
