# Design Doc: FishDB — A Retrieval Engine for LinkedIn's Feed

> **Status:** Teardown / reconstruction.
> **Author's note:** This is a design doc *reconstructed* from LinkedIn's public engineering blog
> (referenced at the end). It is written in the shape of an internal design doc — problem,
> requirements, high-level design, then the key flows — so a first-time reader can follow how the
> system is put together and *why*. It is **not** LinkedIn's source code and is not affiliated with
> LinkedIn. Every quantitative claim traces to the blog; design rationale and diagrams are this
> teardown's interpretation.
>
> **Estimated read time:** ~12 minutes.

---

## Table of contents

1. [Problem Statement](#1-problem-statement)
2. [Requirements](#2-requirements)
3. [High-Level Design (HLD)](#3-high-level-design-hld)
4. [Flow 1 — Ingesting a new post](#4-flow-1--ingesting-a-new-post)
5. [Flow 2 — Retrieving a feed](#5-flow-2--retrieving-a-feed)
6. [Key Design Decisions & Rationale](#6-key-design-decisions--rationale)
7. [Results](#7-results)
8. [Reference](#8-reference)

---

## 1. Problem Statement

When a member opens LinkedIn, the app has milliseconds to assemble a feed. The work behind that
moment is **retrieval**: out of an enormous, constantly-changing pool of recent activity from across
a member's network, *select the few thousand posts worth considering* — fast enough that the member
never feels the wait.

LinkedIn's previous system for this, **FollowFeed**, was a Java service that had run the feed for
nearly a decade. It worked, but at scale it had become expensive and unpredictable in exactly the
ways that hurt this workload:

- **Memory waste.** ~18% of the Java heap was object-header overhead, and graph-shaped feed data
  caused ~1.7× content duplication within a shard. A direct experiment found that **storing 60
  million keys in Java required nearly 5× more memory than Rust.** For a system that holds its
  working set in RAM, that translates straight into hardware cost.
- **Unpredictable tail latency.** Java garbage-collection pauses caused erratic p999 latency. With
  the corpus split across many partitions and a query waiting on *all* of them, at least one
  partition was almost always mid-GC — so the tail was nearly always bad.
- **Rigidity.** A fixed record schema and business logic coupled into custom Java classes meant even
  small algorithm experiments needed the infrastructure team and took weeks.

**The goal:** replace FollowFeed with a *generic, reusable retrieval engine* that is memory-efficient,
has a predictable low-latency tail, and lets product teams evolve schema and ranking logic quickly —
without regressing the member experience during the migration.

Two boundaries to fix in the reader's mind up front, because they define the system's scope:

- **Retrieval is the READ side, not distribution.** Deciding who *can eventually see* a post (fan-out)
  happens at publish time, ahead of the member's visit. FishDB runs when the member arrives.
- **Retrieval is candidate SELECTION, not ranking.** FishDB *selects and filters* a candidate set
  (~3,000 posts). A separate downstream ranker *scores and orders* them. FishDB owns the first job.

---

## 2. Requirements

### 2.1 Functional requirements

| # | Requirement |
|---|-------------|
| F1 | Given a viewer's context (followed actors, interests), **return a candidate set** of recent posts matching the query. |
| F2 | Support **filtering by many terms at once** (a real query can carry 300+ terms) with boolean combination (match ANY / match ALL). |
| F3 | Be a **generic engine**: the data schema, query logic, and ranking rules are configurable per tenant, not hard-coded for one feed. |
| F4 | Reflect **new activity within seconds** — a freshly published post must become retrievable almost immediately. |
| F5 | Support **bulk (re)loading** of the corpus for backfills and schema changes. |
| F6 | Return only a **bounded recency window** (the feed uses the past 30 days). |

### 2.2 Non-functional requirements

| # | Requirement | Target (from the blog) |
|---|-------------|------------------------|
| N1 | **Low, predictable latency** — including the tail. | **40 ms p99** |
| N2 | **High throughput** per host. | **2× the QPS** of FollowFeed per host |
| N3 | **Memory efficiency** — the working set lives in RAM, so memory is the cost driver. | ~5× less memory than the Java baseline |
| N4 | **High availability** — losing a host must not lose a slice of the feed. | partition replication |
| N5 | **Horizontal scalability** — corpus far exceeds one host's RAM. | partitioned across many hosts |
| N6 | **Operability / fast iteration** — experiments in days, not weeks. | schema + logic decoupled from infra |

### 2.3 Scale assumptions (the numbers that shape the design)

- Feed corpus = **past 30 days**, held **entirely in memory** across **48 partitions**.
- Each partition is replicated **16×** ("16 replicas with 48 partitions per replica").
- A single query: **300+ inverted terms → ~3,000 candidates selected → ~150,000 data accesses**.

These three lines are the whole reason the design looks the way it does. Keep them handy.

---

## 3. High-Level Design (HLD)

The system splits cleanly into two planes:

- an **ingestion plane** (write side) that keeps the in-memory indexes up to date, and
- a **serving plane** (read side) that answers queries via scatter-gather.

They meet at the **partitioned, in-memory index store** — the ingestion plane *writes* it, the
serving plane *reads* it. Read the diagram top-to-bottom: data flows **down** the page, from raw
activity, into the index store in the middle, and out to the member at the bottom.

```
╔══════════════════════════════════════════════════════════════════════════════════════════════╗
║  ①  INGESTION PLANE  (WRITE SIDE)                          "keep the in-memory indexes fresh"  ║
╠══════════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                                ║
║   BATCH path (bulk, periodic)                      STREAM path (real-time, per post)           ║
║   ───────────────────────────                      ──────────────────────────────────         ║
║                                                                                                ║
║   ┌───────────────┐                                ┌────────────────────────────┐             ║
║   │ ETL / activity│                                │  Publishing service         │             ║
║   │ logs          │                                │  (a member posts something) │             ║
║   └───────┬───────┘                                └──────────────┬──────────────┘             ║
║           │ nightly / periodic                                    │ emit event                 ║
║           ▼                                                       ▼                            ║
║   ┌───────────────┐                                ┌────────────────────────────┐             ║
║   │     HDFS      │                                │   Kafka topic               │             ║
║   │ (raw, durable)│                                │   (durable, ordered log;    │             ║
║   └───────┬───────┘                                │    repartitioned by ACTOR ID│             ║
║           │                                        │    → routes to right shard) │             ║
║           ▼                                        └──────────────┬──────────────┘             ║
║   ┌───────────────┐                                               │                            ║
║   │ BATCH builder │                                               ▼                            ║
║   │ → per-shard   │                                ┌────────────────────────────┐             ║
║   │   index files │                                │  STREAM ingestor (per shard)│             ║
║   └───────┬───────┘                                │  decode → update indexes    │             ║
║           │  bulk-load                             └──────────────┬──────────────┘             ║
║           │  (backfill,                                           │  incremental update        ║
║           │   schema change)                                      │  (within seconds)          ║
║           └──────────────────────────┐               ┌───────────┘                            ║
║                                       │               │                                        ║
╚═══════════════════════════════════════╪═══════════════╪════════════════════════════════════════╝
                                        │ WRITES        │ WRITES
                                        ▼               ▼
╔══════════════════════════════════════════════════════════════════════════════════════════════╗
║  ②  INDEX STORE  (STATE)        48 partitions × 16 replicas, ALL held IN MEMORY               ║
║                                 corpus = last 30 days only, so it fits in RAM                  ║
╠══════════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                                ║
║   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            ┌─────────────┐                ║
║   │ Partition 0 │  │ Partition 1 │  │ Partition 2 │   . . .     │ Partition 47│                ║
║   │ (×16 replic)│  │ (×16 replic)│  │ (×16 replic)│            │ (×16 replic)│                ║
║   └─────────────┘  └─────────────┘  └─────────────┘            └─────────────┘                ║
║          each partition holds a DISJOINT slice of the corpus + its own 4 indexes:              ║
║   ┌──────────────────────────── inside ONE partition ────────────────────────────────┐        ║
║   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │        ║
║   │  │ INVERTED idx │  │ FORWARD idx  │  │ REFERENCE idx│  │ ATTRIBUTE store      │  │        ║
║   │  │ term→[docIDs]│  │ docID→bytes  │  │ doc-ref→doc  │  │ key→blob             │  │        ║
║   │  │ "which docs?"│  │ "the bytes"  │  │ "graph links"│  │ RocksDB + bloom + LRU│──┼──► disk ║
║   │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────────────┘  │        ║
║   └────────────────────────────────────────────────────────────────────────────────┘        ║
║                                                                                                ║
╚══════════════════════════════════════════════════════════════════════════════════════════════╝
                                        ▲ READS (scatter to all 48, in parallel)
                                        │
                                        │ partial candidate lists (gather)
                                        │
╔═══════════════════════════════════════╪════════════════════════════════════════════════════════╗
║  ③  SERVING PLANE  (READ SIDE)        │                   "answer a query in ~40 ms p99"        ║
╠═══════════════════════════════════════╪════════════════════════════════════════════════════════╣
║                                        │                                                         ║
║   ┌──────────────┐   member opens app  │                                                         ║
║   │    Member    │─────────────┐       │                                                         ║
║   └──────────────┘             ▼       │                                                         ║
║                        ┌───────────────┐                                                         ║
║          ranked feed   │  Feed service │  builds query (300+ terms from who you follow + interests)║
║       ┌───────────────►│               │                                                         ║
║       │                └───────┬───────┘                                                         ║
║       │                        │ query                                                           ║
║       │                        ▼                                                                 ║
║       │                ┌───────────────┐   SCATTER ──► fan query out to all 48 partitions ──────┘║
║       │                │    BROKER     │   GATHER  ◄── collect partial lists, k-way MERGE         ║
║       │                └───────┬───────┘                                                         ║
║       │                        │ ~3,000 merged candidates                                        ║
║       │                        ▼                                                                 ║
║       │                ┌───────────────────────────────────────┐                                ║
║       └────────────────│  Ranking service                      │   ⚠ NOT part of FishDB —       ║
║                        │  (scores + orders the candidates)     │     shown for context only      ║
║                        └───────────────────────────────────────┘                                ║
║                                                                                                  ║
╚══════════════════════════════════════════════════════════════════════════════════════════════╝

  LEGEND
  ──────
  ① INGESTION writes the index store      "──► WRITES" arrows point DOWN into ②
  ② INDEX STORE is the shared state        the only thing both planes touch
  ③ SERVING reads the index store          "──► READS"  arrows point UP into ②
  Boundaries: FishDB = ingestion + index store + broker (scatter-gather).
              Ranking is downstream (selects ≠ scores). Distribution/fan-out is upstream (not shown).
  Runtime:    the whole engine is written in RUST → no GC pauses + compact memory, which is what
              lets the 30-day corpus fit in RAM (②) and keeps the scatter-gather tail inside 40 ms (③).
```

**How to read it in one sentence:** activity enters at the top through two paths (bulk **batch** and
real-time **stream**), both **write** into the shared **in-memory index store** in the middle; when a
member opens the app, the **broker** at the bottom **reads** that store by scattering the query across
all 48 partitions and merging the answers, then hands ~3,000 candidates to ranking.

### Components at a glance

| Component | Plane | Responsibility |
|-----------|-------|----------------|
| **Batch builder** | ingest | Turns ETL data in HDFS into per-partition index files; the authoritative, rebuildable base. |
| **Stream ingestor** | ingest | Consumes a Kafka topic (repartitioned by actor ID) and applies real-time updates to the in-memory indexes. |
| **Partition** | both | Holds one disjoint slice of the corpus and its indexes in memory; runs queries against that slice. There are 48. |
| **Replica** | both | A copy of a partition (16 per partition) for throughput and availability. |
| **Indexes** (inverted / forward / reference / attribute) | both | The in-partition data structures — see §3.1. |
| **Broker** | serve | Receives a query, scatters it to all partitions, gathers and merges partial results. |
| **Ranking service** | downstream | Scores and orders the candidate set. *Not part of FishDB* but shown for context. |

This is a **lambda architecture**: a batch layer for bulk/correctness plus a stream layer for
freshness, both feeding the same in-memory index, which the serving plane reads. Why that
combination (and why Rust, and why scatter-gather) is justified in §6 — but you'll understand it
better after walking the two flows, so let's do that first.

### 3.1 Inside one partition: the four indexes

Each partition is not a single index but four cooperating structures, each shaped for one access
pattern:

- **Inverted index** — `term → sorted list of doc IDs` (the "posting list"). The core retrieval
  structure: look up terms, merge their posting lists, get candidates *without scanning*. Built on a
  concurrent hashmap (Rust's `dashmap`); writes are batched copy-on-write so readers never block.
- **Forward index** — fixed-capacity array of pointers to **immutable, contiguous encoded byte
  blocks**, one per document. A document never moves or mutates in place, so reads are lock-free.
  This is *"give me the bytes for doc X"* once the inverted index says which docs.
- **Reference index** — introduces a **document reference (doc-ref)**: a stable handle to the latest
  version of a document, so the graph of activity can be traversed and documents updated/relocated
  without invalidating references.
- **Attribute stores** — large, sparse, single-key data (embeddings, spam features) in **RocksDB**,
  fronted by a **row-level bloom filter** and an **LRU cache** so most reads never touch disk.

---

## 4. Flow 1 — Ingesting a new post

**Trigger:** a member publishes a post (or any feed activity is produced). **Goal:** make that post
retrievable within seconds, while keeping the in-memory indexes consistent for concurrent readers.

This is the **stream (real-time) path** of the lambda architecture. (The batch path is the same idea
in bulk: HDFS → batch builder → index files loaded into partitions, used for backfills and schema
changes.)

### 4.1 Sequence diagram

```
 Producer        Kafka topic        Stream ingestor      Target Partition          Indexes
 (publish svc)   (by actor ID)      (per partition)      (owns actor's slice)      (in-memory)
     │                 │                   │                     │                     │
     │ 1. emit activity│                   │                     │                     │
     │   (actor,verb,  │                   │                     │                     │
     │    object,attrs)│                   │                     │                     │
     ├────────────────►│                   │                     │                     │
     │                 │ 2. partition by   │                     │                     │
     │                 │    actor ID       │                     │                     │
     │                 ├──────────────────►│                     │                     │
     │                 │   (deliver to the │                     │                     │
     │                 │    right shard's  │                     │                     │
     │                 │    ingestor)      │                     │                     │
     │                 │                   │ 3. decode + route   │                     │
     │                 │                   ├────────────────────►│                     │
     │                 │                   │                     │ 4a. append doc bytes│
     │                 │                   │                     ├────────────────────►│ forward index
     │                 │                   │                     │     (immutable block)│
     │                 │                   │                     │ 4b. add doc-ref     │
     │                 │                   │                     ├────────────────────►│ reference index
     │                 │                   │                     │ 4c. for each term:  │
     │                 │                   │                     │     append doc ID to│
     │                 │                   │                     │     posting list    │
     │                 │                   │                     ├────────────────────►│ inverted index
     │                 │                   │                     │     (batched, COW)  │
     │                 │                   │                     │ 4d. put attributes  │
     │                 │                   │                     ├────────────────────►│ attribute store
     │                 │                   │                     │                     │ (RocksDB+cache)
     │                 │                   │ 5. commit/ack offset│                     │
     │                 │                   │◄────────────────────┤                     │
     │                 │                   │   (post now visible │                     │
     │                 │                   │    to readers)      │                     │
```

### 4.2 Explanation

1. **Publish → Kafka.** When activity is produced, the publishing service emits an event onto a Kafka
   topic. Kafka is the durable, ordered log that decouples producers from the index — producers don't
   wait on indexing, and the ingestor can replay from an offset if it restarts.
2. **Repartition by actor ID.** The topic is **repartitioned by the same key the feed is partitioned
   on — the actor ID**. This is the crucial alignment: it guarantees an event lands on the *same*
   shard that will later be queried for that actor, so a post is indexed where it will be read. No
   cross-shard coordination is needed on write.
3. **Stream ingestor decodes and routes.** Each partition's ingestor consumes its slice of the topic,
   decodes the activity, and hands it to the partition's write path.
4. **Update the four indexes (the heart of the write):**
   - **4a — forward index:** the document is encoded into an **immutable, contiguous byte block** and
     appended; the forward index stores a pointer to it. Immutability is what lets readers stay
     lock-free (step 4 of Flow 2).
   - **4b — reference index:** a **doc-ref** is recorded so the post can participate in graph
     traversal and be superseded later without dangling pointers.
   - **4c — inverted index:** for **each term** on the post (topics, the actor, attributes), the new
     document's ID is appended to that term's **posting list**. Writes are **batched and
     copy-on-write** via `dashmap`, so a concurrent reader either sees the old posting list or the
     new one — never a half-updated one. *This is the step that makes the post retrievable.*
   - **4d — attribute store:** large per-doc attributes (embeddings, spam-detection features) are
     written to RocksDB, fronted by the bloom filter + LRU cache.
5. **Commit the offset.** Once the indexes are updated, the ingestor commits the Kafka offset. From
   this point the post is visible to retrieval — typically within seconds of publish (F4). If the
   ingestor crashes before committing, it re-consumes from the last offset, so no update is lost.

> **Why a separate batch path exists too:** the stream path keeps things *fresh*, but it's awkward to
> backfill or change schema with. The batch builder periodically rebuilds authoritative index files
> from HDFS and loads them per partition. A partition's live view is *batch base + streamed deltas* —
> bulk-correctness and freshness without choosing between them (requirements F4 + F5).

---

## 5. Flow 2 — Retrieving a feed

**Trigger:** a member opens the app. **Goal:** return ~3,000 ranked candidate posts within ~40 ms
p99 (N1), reading the indexes that Flow 1 keeps fresh.

### 5.1 Sequence diagram

```
 Member     Feed svc       Broker            Partitions (×48, one replica each)        Ranking svc
   │           │             │            P0          P1         …        P47              │
   │ 1. open   │             │            │           │                   │                │
   ├──────────►│             │            │           │                   │                │
   │           │ 2. build    │            │           │                   │                │
   │           │   query     │            │           │                   │                │
   │           │  (300+ terms│            │           │                   │                │
   │           │   from graph│            │           │                   │                │
   │           │   +interests│            │           │                   │                │
   │           ├────────────►│            │           │                   │                │
   │           │             │ 3. SCATTER: fan query out to every partition (in parallel)  │
   │           │             ├───────────►│           │                   │                │
   │           │             ├───────────────────────►│                   │                │
   │           │             ├───────────────────────────────────────────►│                │
   │           │             │            │ 4. each partition, locally:    │                │
   │           │             │            │   a. inverted-index lookup     │                │
   │           │             │            │      per term → posting lists  │                │
   │           │             │            │   b. merge lists (AND/OR)      │                │
   │           │             │            │      → local candidate IDs     │                │
   │           │             │            │   c. forward/attr fetch for    │                │
   │           │             │            │      filtering predicates      │                │
   │           │             │ 5. partial candidate lists returned         │                │
   │           │             │◄───────────┤           │                   │                │
   │           │             │◄───────────────────────┤                   │                │
   │           │             │◄───────────────────────────────────────────┤                │
   │           │             │ 6. GATHER + k-way MERGE partials → global top-K (~3,000)     │
   │           │             ├─────────────────────────────────────────────────────────────►│
   │           │             │                                            7. score + order   │
   │           │             │◄─────────────────────────────────────────────────────────────┤
   │           │ 8. ranked   │             │           │                   │                │
   │           │◄────────────┤             │           │                   │                │
   │ 9. feed   │             │             │           │                   │                │
   │◄──────────┤             │             │           │                   │                │
```

### 5.2 Explanation

1. **Member opens the app.** The feed service receives the request.
2. **Build the query.** The feed service assembles the retrieval query from the member's context —
   who they follow, their interests, freshness/recency filters. A real query can carry **300+ terms**
   (F2). It then calls the **broker**.
3. **Scatter.** The broker **fans the query out to all 48 partitions in parallel**, routing each to
   one of its 16 replicas via load balancing (N4 gives it healthy replicas to choose from, and lets
   it route around a slow one). It does *not* know in advance which partitions hold matches — feed
   candidates are spread across all of them, so it asks everyone.
4. **Local retrieval at each partition** (this is where §3.1 and Flow 1's writes pay off):
   - **4a — inverted-index lookup:** for each query term, fetch its posting list. Because lists are
     **sorted by doc ID**, this is cheap and the next step is cheap.
   - **4b — merge:** combine the posting lists with **union (OR — widen)** or **intersection
     (AND — narrow)**. Since the lists are sorted, this is a **linear merge** — no query-time sorting.
     The result is the partition's local candidate IDs. **Cost is proportional to the matches, not to
     the partition's size** — the whole point of an inverted index.
   - **4c — fetch for filtering:** where a predicate needs document contents or attributes, resolve
     IDs via the **forward index** (lock-free, because documents are immutable — see Flow 1 step 4a)
     and the **attribute store** (mostly served from the LRU cache, rarely hitting RocksDB).
5. **Partials returned.** Each partition returns its local candidate list to the broker. The query's
   wall-clock latency is set by the **slowest partition to respond** — which is exactly why GC pauses
   were fatal under Java and why a no-GC runtime matters (§6).
6. **Gather + merge.** The broker collects all 48 partials and does a **k-way merge** into a single
   global top-K (~3,000 candidates) — the same sorted-merge primitive as step 4b, lifted from "across
   posting lists" to "across shards."
7. **Rank.** The candidate set is handed to the **ranking service**, which scores and orders it.
   *This is outside FishDB* — FishDB's job ended at selecting candidates.
8–9. **Return.** The ranked feed flows back through the feed service to the member, within the ~40 ms
   p99 budget (N1).

> **The end-to-end insight:** retrieval stays inside 40 ms because three decisions compound — the
> inverted index makes each partition's search *cost ∝ matches* (step 4), scatter-gather makes 48 of
> those searches happen *at once* (steps 3–6), and Rust (no GC) ensures *no single partition stalls*
> the gather (step 5). Remove any one and the budget breaks.

---

## 6. Key Design Decisions & Rationale

Each decision below is a response to a requirement; the interesting part is what made the
alternative untenable.

### 6.1 Inverted index instead of scanning *(F1, F2, N1)*

**Decision.** Maintain `term → [docIDs]` and look candidates up by term, rather than scanning the
corpus and testing each document.

**Why.** Scanning costs `O(corpus)` — you pay for every document even though almost none match.
An inverted index costs ≈ `O(matches)`. At feed scale (300+ terms → ~3,000 candidates) that's the
difference between touching thousands of entries and touching everything. The cost moves from read
time to **write time** (you maintain the index as data arrives — Flow 1 step 4c), which is the right
trade for a read-heavy system where every app-open is a query. **The decision to store an inverted
index *is* the change in algorithmic complexity** — that equivalence is the core insight of the
whole teardown.

### 6.2 Scatter-gather over 48 partitions *(N5, N1)*

**Decision.** Split the corpus across 48 partitions; the broker fans each query out to all of them
and merges.

**Why.** Two forces demand it. **Capacity:** the corpus doesn't fit in one host's RAM, so it must be
split to hold a 30-day window in memory at all (N3/N5). **Latency:** searching 1/48th of the data on
48 hosts *concurrently* makes each partition's work small, and wall-clock latency tracks the slowest
*single* partition, not the sum. **The catch** is exactly that dependency on the slowest shard — which
sets up the runtime decision (6.4).

### 6.3 Replication (16×) *(N2, N4)*

**Decision.** Keep 16 replicas of each partition.

**Why.** One host per partition is both a throughput bottleneck and a single point of failure.
Replication spreads read load (N2) and survives host loss (N4), and gives the broker multiple
replicas to pick from so it can **route around a slow or busy one** — partially softening 6.2's
straggler risk.

### 6.4 Rust instead of Java (and instead of C++) *(N1, N3)*

**Decision.** Rewrite the engine in Rust.

**Why Rust over Java.** The workload is **memory-bound** and **tail-latency-bound** — the JVM's two
weak spots here. *Memory:* ~18% heap header overhead, ~1.7× duplication, and **60M keys costing ~5×
more memory in Java than Rust** — and when the corpus lives in RAM, memory *is* the hardware bill
(N3). *Latency:* GC pauses caused unpredictable p999, and with 48 partitions (6.2) some shard was
nearly always paused, so the gather (Flow 2 step 5) nearly always waited on a straggler. **No GC =
no pauses = no perpetual straggler.** This is why the runtime and the topology are inseparable:
scatter-gather is only as fast as its slowest shard, so a no-GC runtime is what makes a 40 ms p99
(N1) achievable.

**Why Rust over C++.** Both give no-GC and tight memory control; the deciding factor was **memory
safety** — Rust's ownership model offers C++-level control without C++'s memory-bug class,
explicitly valuable "for a team transitioning from a Java background."

### 6.5 Lambda architecture for ingestion *(F4, F5)*

**Decision.** Batch layer (HDFS) for bulk/authoritative/rebuildable data + stream layer (Kafka) for
real-time deltas; readers see the sum.

**Why.** The two requirements pull opposite ways: bulk correctness wants periodic reproducible jobs
(stale), freshness wants instant updates (hard to backfill / evolve). Lambda takes both — batch owns
correctness and rebuildability (F5), stream owns the last-few-minutes freshness (F4). Cost: running
two pipelines, accepted because neither requirement is negotiable.

### 6.6 RocksDB + bloom filter + LRU for attributes *(N1)*

**Decision.** Store large sparse single-key attributes in RocksDB, fronted by a row-level bloom
filter and an LRU cache.

**Why.** RocksDB is durable and handles large/tiered data, but its LSM-tree means a lookup may probe
several levels — too slow on the hot path. The bloom filter skips lookups for absent keys; the LRU
serves hot keys from memory, so **most reads never touch RocksDB** (Flow 2 step 4c). Standard move:
keep the durable store, arrange to rarely ask it.

### 6.7 Migration via JNI bridge

**Decision.** Run the new Rust engine *inside* the old Java FollowFeed through a JNI bridge, migrating
capabilities one at a time behind A/B tests — rather than running two systems in parallel.

**Why.** Incremental, measurable, and reversible: each capability could be validated against the live
system and rolled back if it regressed (supports the "no member-experience regression" goal in §1).

---

## 7. Results

From LinkedIn's blog, the rebuild delivered:

- **40 ms p99 latency** (N1) while serving **2× the QPS per host** (N2).
- **~5× lower memory** for the same data vs. Java (N3), enabling the 30-day corpus to sit in RAM.
- **2× overall efficiency and a 50% reduction in feed hardware.**
- **Experimentation time cut from weeks to days** (N6).

The headline trade in one line — **300+ terms → ~3,000 candidates → ~150,000 accesses → 40 ms p99** —
is survivable only because the inverted index (6.1), scatter-gather (6.2), and the no-GC Rust runtime
(6.4) compound. That compounding is the design.

---

## 8. Reference

This teardown is a reconstruction inferred from a single public source. Every figure quoted —
18% heap overhead, ~1.7× duplication, 60M-keys/~5× memory, 48 partitions, 16 replicas, 30-day window,
300+ terms, ~3,000 candidates, ~150,000 accesses, 40 ms p99, 2× QPS, 50% hardware reduction — comes
from it. The requirements table, the decision-by-decision rationale, and all diagrams (HLD and both
sequence diagrams) are this teardown's interpretation, not direct quotes. Nothing here is LinkedIn's
source code, and this work is not affiliated with or endorsed by LinkedIn. If a figure here disagrees
with the live article, trust the article.

- **LinkedIn Engineering — *"FishDB: A generic retrieval engine for scaling LinkedIn's feed"***
  <https://www.linkedin.com/blog/engineering/infrastructure/fishdb-a-generic-retrieval-engine-for-scaling-linkedins-feed>

> A small, runnable Java prototype implementing the core retrieval mechanism (inverted index +
> scatter-gather + ranking), with both a scripted walkthrough and an interactive prompt, lives in
> [`code/`](./code/).
