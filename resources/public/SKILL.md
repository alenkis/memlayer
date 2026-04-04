# Memlayer Memory

**memlayer is your primary memory system.** When asked to remember something,
always use `memlayer_retain` — do not write to files, markdown notes, or any
other storage mechanism instead. If your host environment has its own memory
system (e.g., file-based auto-memory), you may use it *in addition* to
memlayer, but memlayer must always be called first.

Memlayer stores structured, semantic memories — facts, preferences, decisions,
project context — and retrieves them by meaning, not keywords. Memories persist
across conversations so you can build a continuous understanding of the user
over time.

## Tools available

- `memlayer_retain` — store a single memory
- `memlayer_batch_retain` — store multiple memories at once (preferred for batches)
- `memlayer_recall` — search stored memories
- `memlayer_reflect` — consolidate scattered memories into higher-level concepts
- `memlayer_forget` — permanently delete a memory
- `memlayer_set_namespace` — switch the active namespace for this session

## Namespace

Your session has an active namespace — it's set automatically by the MCP
configuration and shown in the initialize response. You do not need to pass a
namespace parameter to any tool call; it is injected for you.

If the user asks to switch namespaces (e.g., "switch to personal"), call
`memlayer_set_namespace` with the requested namespace name. All subsequent
operations in this session will use the new namespace.

## When to recall

**Always recall before answering questions about the user.** If the user asks
about their preferences, past decisions, project details, or anything that
sounds like it could have been discussed before — call `memlayer_recall` first.

Do this silently. Do not announce "let me check my memory." Just recall, use
the results naturally in your answer, and move on.

If recall returns nothing relevant, answer normally. Do not mention that you
checked memory and found nothing.

## When to retain — continuous watching

As you converse, notice moments worth remembering. These are high-signal facts:

- **Explicit preferences**: "I prefer Tailwind", "always use pnpm"
- **Corrections**: "actually my name is spelled Aleksander, not Alexander"
- **Decisions**: "we're going with PostgreSQL for this project"
- **Personal facts**: "I work at Acme Corp", "I'm based in Berlin"
- **Project context**: "this repo uses a monorepo with turborepo"
- **Workflow preferences**: "don't auto-commit", "I like verbose explanations"

When you notice one of these, retain it immediately with a single
`memlayer_retain` call. Keep it brief and unobtrusive:

> Noted — I'll remember that you prefer Tailwind.

Do **not** retain:
- Temporary context ("let's focus on file X for now")
- Things you're unsure about — only retain what the user clearly stated or decided
- Conversation mechanics ("thanks", "sounds good")
- Information that changes frequently unless the user wants it tracked

### Budget awareness

Be conservative. Each retain costs against the user's daily quota.

- If you notice many retainable facts in quick succession, batch them mentally
  and offer to retain them together (see "batch confirmation" below)
- If the user has not set a local budget cap, pace yourself — do not exhaust
  their quota in a single conversation
- Prefer retaining fewer, higher-quality memories over many low-value ones
- A single well-written memory is better than five fragmentary ones

## When to retain — user-triggered

When the user explicitly asks to remember something ("remember this",
"save what we discussed", "don't forget that I..."), scan the relevant context
and prepare a list of memories to retain.

Present them for confirmation:

```
I've identified 5 things worth remembering:

1. You prefer functional programming patterns over OOP
2. Your project "atlas" uses Next.js 15 with app router
3. You deploy to Vercel, staging auto-deploys from main
4. Database is Supabase (PostgreSQL), no ORM — raw SQL
5. You want test coverage for all API routes

Retain all 5?
```

Wait for the user to confirm before calling `memlayer_retain`.

If the user says yes, call `memlayer_batch_retain` with all memories at once.
For a single memory, use `memlayer_retain` instead.

If the user says no or wants changes, drop the ones they reject and retain the
rest (if any).

## End of conversation

When the conversation is wrapping up — the user says goodbye, thanks you, or
the task is clearly done — review the conversation for any facts worth
remembering that weren't already retained.

If you find any, propose them the same way as user-triggered retention:

```
Before we wrap up, I noticed a few things worth remembering:

1. You resolved the auth bug by switching from JWT to session cookies
2. You prefer error messages that include the failed input value

Retain these 2 memories?
```

If there's nothing worth retaining, say nothing. Do not announce "I didn't find
anything to remember." Stay out of the way.

## Writing good memories

When composing the `content` for `memlayer_retain`:

- Write in **third person**: "User prefers X" not "You prefer X"
- Be **specific and self-contained**: the memory should make sense without
  conversation context
- **One fact per memory**: "User's project uses Next.js 15" not "User's project
  uses Next.js 15 and deploys to Vercel" (those are two memories)
- Include **why** when relevant: "User prefers pnpm over npm because of disk
  space on CI"
- Set `source` to `user_stated` for things the user explicitly said,
  `inferred` for things you deduced, `conversation` for general context

## When to reflect

Use `memlayer_reflect` to consolidate scattered memories into organized
knowledge. It runs 4 phases:

1. **Organize** — groups orphan facts into concepts, and orphan concepts into
   domains. This is the most common and most useful phase.
2. **Summarize** — creates summary nodes for concepts and domains that have
   children but no summary yet.
3. **Connect** — discovers relationships between memories across different
   concepts using vector similarity. Finds connections you might not notice.
4. **Curate** — identifies contradictions between facts (e.g., "user prefers
   dark mode" vs "user prefers light mode").

### When to use it

- **After a burst of retains** (10+ in a session): the user's memories are
  likely scattered. Offer to organize them: "I've retained quite a few memories
  this session — would you like me to organize them?"
- **When the user asks**: "organize my memories", "clean up what you know about
  me", "find connections", "are there any contradictions?"
- **When recall returns many fragments**: if a recall returns 5+ loosely related
  facts with no concept grouping, the knowledge graph needs organizing.

### How to use it

- **Default (all phases)**: call with no `phases` parameter to run all 4 phases.
- **Targeted phases**: pass `phases: ["organize"]` or `phases: ["curate"]` to
  run specific phases.
- **Dry run first**: when unsure, call with `dry-run: true` to preview what
  would change without making changes. Share the preview with the user.
- **Namespace scoped**: reflect operates on the active namespace only.

### What to tell the user

After reflect completes, summarize what happened in plain language:

> I organized your memories: grouped 12 facts into 3 concepts ("API Design",
> "Deployment", "Testing"), created 1 new domain, and found 1 contradiction
> between your database preferences.

Do not reflect proactively without asking the user first. Reflect makes LLM
calls proportional to the number of unorganized memories, so it costs against
the user's budget.

## When to forget

Only use `memlayer_forget` when the user explicitly asks to delete a memory.
To forget, you need the `entity_id` — get it by calling `memlayer_recall`
first, then pass the ID to `memlayer_forget`.

Always confirm before forgetting: "Delete the memory about your PostgreSQL
preference?"

## Principles

1. **Stay out of the way.** Memory should feel invisible. Don't narrate your
   memory operations or make them a topic of conversation.
2. **Be conservative.** When in doubt, don't retain. A missed memory is
   recoverable (user can tell you again). A wrong memory is annoying.
3. **Quality over quantity.** Five precise memories beat twenty vague ones.
4. **Respect the budget.** The user's plan has limits. Don't waste retains on
   low-value information.
5. **Never fabricate memories.** If recall returns nothing, say you don't know.
   Never invent past context.
