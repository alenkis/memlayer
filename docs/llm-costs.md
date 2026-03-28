# Expected LLM Costs per Operation

Memlayer uses two LLM providers. This document estimates per-operation costs
based on current pricing (as of March 2026).

## Models Used

| Provider | Model | Use | Pricing |
|----------|-------|-----|---------|
| OpenAI | `text-embedding-3-small` | Embeddings (1536 dim) | $0.02 / 1M tokens |
| Groq | `llama-3.3-70b-versatile` | Extraction, decisions, categorization | $0.59 / 1M input, $0.79 / 1M output |

Both models are configurable via `OPENAI_EMBEDDING_MODEL` and `GROQ_MODEL` env vars.

## Per-Operation Breakdown

### Retain (single item)

Stores one piece of content. Runs 3 LLM phases:

| Phase | Provider | Typical Tokens | Est. Cost |
|-------|----------|---------------|-----------|
| Extraction (content -> facts) | Groq | ~500 in, ~200 out | $0.00045 |
| Embedding (per extracted fact, ~2 facts avg) | OpenAI | ~100 per fact | $0.000004 |
| Decision (per fact, ~2 facts avg) | Groq | ~300 in, ~50 out per fact | $0.00043 |
| **Total per retain** | | | **~$0.001** |

A typical retain call costs **under $0.001** (a tenth of a cent).

### Batch Retain (N items)

Processes multiple items in a single Groq extraction call, then individual
embeddings and decisions. More efficient than N separate retains.

| Phase | Provider | Typical Tokens | Est. Cost |
|-------|----------|---------------|-----------|
| Batch extraction (all items) | Groq | ~300*N in, ~150*N out | ~$0.0003*N |
| Embedding (per extracted fact) | OpenAI | ~100 per fact | ~$0.000002 per fact |
| Decision (per extracted fact) | Groq | ~300 in, ~50 out per fact | ~$0.0002 per fact |

For 10 items producing ~20 facts: **~$0.007** total.

### Recall (search)

Single embedding call to vectorize the query.

| Phase | Provider | Typical Tokens | Est. Cost |
|-------|----------|---------------|-----------|
| Query embedding | OpenAI | ~50 | $0.000001 |

Recall is **essentially free** — under $0.00001 per query.

### Reflect (consolidation)

Groups orphan facts into concept hierarchies.

| Phase | Provider | Typical Tokens | Est. Cost |
|-------|----------|---------------|-----------|
| Query embedding (if query filter used) | OpenAI | ~50 | $0.000001 |
| Categorization (per batch of 15 facts) | Groq | ~1500 in, ~500 out | $0.001 |
| Concept embedding (per new concept) | OpenAI | ~100 | $0.000002 |

Per reflect with 15 orphan facts: **~$0.001**.

### Forget

No LLM calls. **$0.00**.

## Monthly Cost Estimates

| Usage Pattern | Retains/day | Recalls/day | Est. Monthly |
|---------------|-------------|-------------|-------------|
| Light (personal notes) | 10 | 50 | ~$0.30 |
| Moderate (daily agent) | 50 | 200 | ~$1.50 |
| Heavy (continuous agent) | 200 | 1000 | ~$6.00 |

These estimates assume default models. Using different models will change costs.

## Tracking Usage

Every API response includes a `usage` field with token counts:

```json
{
  "usage": {
    "extraction": {"prompt_tokens": 480, "completion_tokens": 190, "total_tokens": 670},
    "embedding":  {"prompt_tokens": 95, "completion_tokens": 0, "total_tokens": 95},
    "decision":   {"prompt_tokens": 310, "completion_tokens": 45, "total_tokens": 355},
    "total_tokens": 1120
  }
}
```

Use these counts with your provider's pricing page to calculate exact costs.

## Configuration

| Env Variable | Default | Effect on Cost |
|-------------|---------|---------------|
| `OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` | Larger models cost more |
| `GROQ_MODEL` | `llama-3.3-70b-versatile` | Smaller models cost less |
| `RETAIN_CONTEXT_LIMIT` | 10 | More context = more decision tokens |
| `REFLECT_BATCH_SIZE` | 15 | Larger batches = fewer Groq calls |
