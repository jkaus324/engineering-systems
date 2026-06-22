---
title: "How LinkedIn Built Its Feed to Answer You in 40 Milliseconds"
description: >-
  A reconstruction of how LinkedIn's feed retrieval engine (FishDB) likely works —
  graph-anchored retrieval, scatter-gather over 48 shards, and the one-writer-per-shard
  rule that makes reads lock-free.
date: 2026-06-22 09:00:00 +0530
categories: [Teardowns, Feed Systems]
tags: [linkedin, system-design, retrieval, indexing, scatter-gather, rust]
mermaid: true
toc: true
---

*A reconstruction of how LinkedIn's feed retrieval engine (FishDB) likely works, inferred from their
public engineering blog. Not their source code.*

> 📄 **Original source:** [FishDB: A generic retrieval engine for scaling LinkedIn's feed](https://www.linkedin.com/blog/engineering/infrastructure/fishdb-a-generic-retrieval-engine-for-scaling-linkedins-feed)
{: .prompt-info }

## Three "feeds," three completely different machines

**YouTube is a watch-time recommendation engine.** It doesn't really care who you follow. It cares
what will keep you watching. The core question is *"what video maximizes the minutes you stay?"* —
that's a recommendation/ranking problem driven by a giant ML model over your watch history.

**Instagram (Explore) is a visual discovery engine.** The whole point of Explore is showing you
things from people you *don't* follow. The core question is *"what will this user find interesting
that they haven't seen?"* — that's discovery over a visual-similarity and engagement space.

**LinkedIn is a professional graph platform.** This is the one people miss. LinkedIn's feed is
anchored to your *connections* — the graph of who you follow, who you work with, what your network is
talking about. The core question is *"what's relevant from the people and topics connected to you,
right now?"*

Read those three again. They are different questions. And different questions demand different
machines:

> YouTube optimizes **watch time**. Instagram optimizes **discovery**. LinkedIn optimizes **graph
> relevance**.
{: .prompt-info }

Lumping them as "feed systems" flattens that. The interesting engineering is exactly in the part the
word "feed" erases.

LinkedIn's version of the problem has a specific shape: you have a graph, the viewer has a set of
connections and interests, and when they open the app you need to pull *recent, relevant activity
from across that graph* — fast. That's not "recommend a video." That's not "discover a stranger's
photo." It's **graph-anchored retrieval**, and it's its own beast.

So let's build that beast.

## What the system actually has to do

Strip it down and there are only two moments that matter.

**Moment one — someone posts.** A new piece of activity is created. The system has to accept it and,
crucially, *file it away in a form that makes it cheap to find later.* The work you do here is what
makes the next moment fast or slow.

**Moment two — someone opens the app.** Now you have milliseconds to look across a huge pool of
recent activity and pull out the few thousand items relevant to this viewer's graph.

Notice the relationship between them. Moment two is only cheap if Moment one did its job well. **You
pay a little on the way in so you can pay almost nothing on the way out.**

That single idea splits the whole system into two subsystems:

1. **Retrieval** — the read path. Take a query, find candidates, merge, return.
2. **Ingestion** — the write path. Accept posts, index them, keep them fresh.

We'll start with **retrieval** — it's the moment that has to be fast, and seeing what it *needs*
makes the ingestion choices that feed it obvious. But first, the big picture.

## The big picture

There's an **entry point** (ingestion, on the left) and a **retrieval point** (on the right). They
never talk to each other directly — they meet only at the **sharded index store** in the middle.
Ingestion writes it; retrieval reads it.

{% include fishdb-architecture.html %}

A few things to lock in from this picture before we zoom in:

- The broker does **not** talk to one database. It talks to **48 shards**, and each shard has **16
  replicas** — so the same slice of data exists in 16 places for throughput and safety.
- *Which* replica the broker hits for a given shard is decided by a **load balancer paired with a
  circuit breaker**: spread the load, and route *around* any replica that's slow or unhealthy.
  *(LinkedIn's blog describes internal load balancing / service discovery; the circuit-breaker pairing
  is the standard way these systems avoid waiting on a sick replica — I'm reconstructing that part.)*
- Ingestion has **two techniques at once** — an **instant** path for freshness and a **batch** path
  for bulk. We'll see why you need both.
- Inside ingestion, each shard has **one indexer and one writer — a strict 1:1**. That single-writer
  rule is doing more work than it looks. We'll get there.

Now let's break the machine in half and study each side.

## System 1 — Retrieval (the read path)

This is the moment that has to be fast: a query comes in, fans out across the shards, and comes back
merged. (That fan-out-then-merge pattern has a name — **scatter-gather** — and it shapes everything
below.) Watching what retrieval *needs* is also the best way to understand the ingestion choices that
feed it — so we start here. (The *how* — especially the priority-queue mechanics — is where it gets
satisfying.)

### The query is a big OR over your graph

When you open the app, the feed service builds a query out of your context: the people you follow,
your interests, recency filters. In practice that's **300+ terms** in a single query — `author:alice`,
`author:bob`, `topic:rust`, `topic:databases`, and so on.

Retrieval, at its core, is: *fetch the posting list for each of those 300 terms, and combine them.*
Combine with **OR** to widen ("anything from anyone I follow"), or **AND** to narrow ("posts that are
both from my network *and* about databases").

Because every posting list is kept **sorted by document ID** (a *posting list* is just the list of
document IDs that contain a given term), combining them is a **linear merge** — walk the lists with
cursors (a cursor is a pointer that remembers your position in each list), no sorting at query time.
That sorted-list invariant is quietly doing a lot of work.

```java
// OR-retrieval: union the posting lists of all the viewer's terms.
// Each posting list is already sorted by docID, so the union stays sorted too.
List<Integer> orRetrieve(List<String> terms, InvertedIndex index) {
    Set<Integer> union = new TreeSet<>();        // sorted + de-duplicated
    for (String term : terms) {
        union.addAll(index.posting(term));       // "anything from anyone I follow"
    }
    return new ArrayList<>(union);
}
// AND would be the same loop with retainAll instead of addAll — narrowing, not widening.
```

> Two structures make this work, and ingestion (next section) is what builds them: an **inverted
> index** (`term → sorted document IDs`) to find *which* documents match, and a **forward index**
> (`document ID → bytes`) to fetch their *contents*. Retrieval is a two-phase operation — find, then
> fetch — so it leans on both.
{: .prompt-info }

### Inside one shard: a priority queue picks the top candidates

Each shard runs that term-merge over its own slice and ends up with a pile of matching documents —
potentially far more than the broker wants back. The shard doesn't return all of them. It returns its
**top-K**, scored by recency/relevance.

How do you efficiently keep the top-K out of a large stream of matches? A **priority queue** — a
**heap**, the data structure that always keeps its smallest (or largest) element ready to peek at.
Here we use a **min-heap**, so the *weakest* candidate is always the one sitting at the top, ready to
be evicted.

```java
// Keep only the best K candidates out of a stream of matches.
// A min-heap of size K means its smallest (weakest) item is always on top.
PriorityQueue<Candidate> topK =
    new PriorityQueue<>(Comparator.comparingDouble(Candidate::score)); // min-heap by score

for (Candidate c : matchesInThisShard) {
    topK.offer(c);                 // O(log K) insert
    if (topK.size() > K) {
        topK.poll();               // over capacity → drop the current weakest
    }
}
// What's left in the heap is this shard's top-K candidates.
```

The beauty of a size-K min-heap: you never sort the whole pile. You keep only the best K seen so far,
each insert is cheap (`O(log K)` — logarithmic in K, i.e. the work barely grows as K grows), and the
weakest candidate falls out the bottom automatically. Each shard does this independently, in parallel
with all the others.

> 💻 **Code point:** per-shard top-K selection with a bounded min-heap. Short, and it's the "aha" of
> why you don't sort everything. *(Snippet in the repo / gist.)*
{: .prompt-info }

### The broker: merging the priority queues

Now the broker has up to 48 small sorted lists coming back — one top-K per shard. Its job is to merge
them into a single global top-K.

This is a **k-way merge** (merging K already-sorted streams into one sorted stream), and again the
tool is a **priority queue**:

```java
// Merge K sorted shard lists into one global top-K. Each "cursor" remembers
// which shard a candidate came from and where to read the shard's next item.
record Cursor(Candidate item, int shard, int next) {}

// Max-heap: the best candidate across all shards' current front-runners sits on top.
PriorityQueue<Cursor> heap =
    new PriorityQueue<>(Comparator.comparingDouble((Cursor c) -> c.item().score()).reversed());

for (int s = 0; s < shardLists.size(); s++) {        // seed with each shard's head item
    if (!shardLists.get(s).isEmpty()) {
        heap.offer(new Cursor(shardLists.get(s).get(0), s, 1));
    }
}

List<Candidate> globalTopK = new ArrayList<>();
while (!heap.isEmpty() && globalTopK.size() < K) {
    Cursor c = heap.poll();                          // pop the global best
    globalTopK.add(c.item());                        // it's the next item in the feed
    List<Candidate> list = shardLists.get(c.shard());
    if (c.next() < list.size()) {                    // advance only THAT shard's cursor
        heap.offer(new Cursor(list.get(c.next()), c.shard(), c.next() + 1));
    }
}
```

The heap always holds the current front-runner from each shard, so popping the global maximum is
cheap, and you only ever look at as many items as you actually need. You're merging 48 already-sorted
streams — the same merge primitive used *inside* a shard, now lifted to *across* shards.

This is the part the diagram was pointing at: the broker fans out (**scatter**), each shard returns a
priority-queue-selected top-K, and the broker **merges those priority queues** into the answer.

> 💻 **Code point:** the broker's k-way merge across shard results. Pairs naturally with the per-shard
> heap above. *(Snippet in the repo / gist.)*
{: .prompt-info }

### Picking a replica: load balancer + circuit breaker

When the broker scatters to "shard 7," it has 16 replicas to choose from. It needs to pick one — and
pick *well*, because of a property that defines scatter-gather:

> The broker can't return until the **slowest** shard answers. One slow replica drags the whole query.
{: .prompt-warning }

So replica selection isn't an afterthought; it's a latency lever. A **load balancer** spreads requests
across the 16 replicas so none gets hot. A **circuit breaker** watches replica health and *takes a
sick one out of rotation* — if a replica is timing out or erroring, the breaker "trips" and the broker
stops sending it traffic until it recovers, routing to a healthy sibling instead.

Together they make sure the broker waits on *fast, healthy* replicas, not whichever one happens to be
struggling. *(The blog mentions internal load balancing / service discovery; the circuit-breaker
framing is standard practice for this exact problem, reconstructed here.)*

### Fetch the content, then hand off to ranking

Once the broker has the merged candidate IDs, it resolves them to actual content via the **forward
index** (lock-free, thanks to the single-writer rule we'll see in ingestion next), applies any final
filters, and ends up with ~3,000 candidates.

Then it stops. Retrieval is done.

The candidates go to a **separate ranking system** that scores and orders them. That's a different
machine with a different job — *retrieval selects, ranking sorts* — and it's deliberately not part of
this engine.

> 💻 **Code point:** a trivial score-and-sort ranking step, just to make the retrieval/ranking
> boundary tangible. *(Snippet in the repo / gist.)*
{: .prompt-info }

## System 2 — Ingestion (the write path)

Retrieval was fast because the data was already sitting in exactly the right shape. *That* is
ingestion's whole job: take a post and leave it in the index store so retrieval can find it instantly.
Three things make this interesting — *what* a post is, *how* it arrives (two ways), and *who's allowed
to write it* (exactly one).

### The timeline record: what a "post" actually is

LinkedIn doesn't store "a post" as a blob of UI. It stores a **timeline record**, and the shape is
beautifully simple:

```text
(actor, verb, object) + metadata

e.g.  (alice,  shared,  article#123)
      (bob,    liked,   post#456)
```

An **actor** did a **verb** to an **object**. That's the atom of the feed. "Alice shared an article."
"Bob commented on a post." Everything in your feed is a timeline record.

Why does this matter so much? Because of one move that defines the entire LinkedIn approach:

**The actor becomes a search term.**

In the index, `author:alice` is a term just like `topic:rust` is. Its posting list — the list of
records pointing at it — *is Alice's timeline.* Her recent activity is literally the value behind her
key.

That has a stunning consequence, and it answers the question everyone asks:

> **"When I post, how does it get into my followers' feeds?"**
>
> It doesn't. Nothing gets sent anywhere.
>
> Your post lands in *your* shard and gets indexed under `author:you`. It just sits there. When a
> follower opens the app, their query asks for `author:you` along with everyone else they follow.
> Retrieval finds your post sitting in your shard and pulls it in.
>
> The post doesn't travel to readers. **Readers come and get it.** (Twitter does the opposite — it
> pushes your tweet into millions of timelines on write. Hold that thought; it's a whole post on its
> own.)
{: .prompt-tip }

That's why ingestion is "cheap." There's no fan-out to a million followers. You write the record
once, in one place, indexed under its actor.

```java
// A timeline record: an actor did a verb to an object, plus metadata.
record TimelineRecord(String actor, String verb, String object, long createdAt) {}

// Indexing one record: the actor ID becomes a term, just like any topic.
void index(TimelineRecord r, int docId, InvertedIndex index) {
    index.add("author:" + r.actor(), docId);   // "author:alice" → ... , docId   (Alice's timeline)
    // (topics from r.object()'s attributes would be added as terms here too.)
}
// So the posting list behind "author:alice" literally *is* Alice's recent activity.
```

> 💻 **Code point:** the timeline-record struct and how an actor turns into a posting-list key. Small,
> and it makes the "actor is a term" idea concrete. *(Snippet in the repo / gist.)*
{: .prompt-info }

### Two ways in: instant and batch (and why you need both)

A post has to show up in seconds — a feed missing the last five minutes feels broken. But you also
can't rebuild a giant in-memory index from scratch every time someone likes something.

These two needs fight each other:

> Freshness wants *tiny, instant* updates. Bulk correctness wants *big, periodic* rebuilds. You can't
> get both from one pipeline.
{: .prompt-warning }

So FishDB runs **both** — this is a **lambda architecture** (the pattern of pairing a slow-but-complete
batch layer with a fast-but-approximate streaming layer, then serving the combination).

**The instant path (stream).** New activity flows through a **Kafka** topic. Kafka is a durable,
ordered log — producers drop events in and move on; the indexer reads them at its own pace and can
replay from where it left off if it restarts. The clever bit: the Kafka topic is partitioned by the
*same key the feed is sharded on — the actor ID.* So a new post is delivered to the *exact shard* that
will later be queried for that actor. No cross-shard coordination on write.

**The batch path (bulk).** Periodically, the authoritative version of the data is rebuilt in bulk
from **HDFS**.

### What HDFS is, and why it's here at all

HDFS — the **Hadoop Distributed File System** — is a storage system that spreads enormous files
across a cluster of machines, splitting each file into blocks and replicating those blocks so nothing
is lost if a machine dies. It's built for *throughput on huge datasets*, not for low-latency single
lookups. Think "the warehouse," not "the front desk."

Why does the feed need a warehouse?

- **It's the source of truth.** The in-memory index is a fast *projection* of the data. HDFS holds
  the durable, complete copy the projection is built from.
- **It makes rebuilds and backfills possible.** Need to change the index schema? Need to recover from
  a bad deploy? You don't surgically migrate live memory — you regenerate the index files from HDFS
  and reload them. The batch path is your "rebuild from scratch" button.
- **It handles bulk economically.** Loading 30 days of history into a fresh shard is a bulk job, and
  bulk jobs are exactly what HDFS + a batch builder are good at.

So the division of labor is clean: **batch (HDFS) gives you correct and rebuildable; stream (Kafka)
gives you fresh.** Each shard's live view is *batch base + streamed deltas.* You never have to choose
between "correct" and "up to date."

### One writer per shard — the rule that buys lock-free reads

Here's the detail that's easy to skim past and shouldn't be.

Each shard has **one indexer and one writer.** Not a pool of writers. One. A strict 1:1.

Why deliberately limit yourself to a single writer when you could parallelize?

Because of what it does for the *readers.* Retrieval is brutally read-heavy — every app-open hits
these shards. If writers and readers had to take locks against each other, every read would pay a
synchronization cost, and under load that cost explodes.

The single-writer rule sidesteps it. With exactly one writer per shard:

- The **forward index** stores documents as *immutable, contiguous bytes*. Once written, a document
  never changes in place. Readers can read it with **no locks at all** — there's no second writer who
  could be mutating it underneath them. (A single *atomic counter* — a number the writer can bump in
  one all-or-nothing step — marks how many documents are valid; readers just read up to it.)
- The **inverted index** is updated with *batched, copy-on-write* semantics. *Copy-on-write* means the
  writer never edits the live list in place — it builds a fresh copy with the changes, then swaps it in.
  So a reader either sees the old posting list or the new one — never a half-updated one — because the
  lone writer swaps in the new version atomically (in one indivisible step, so no reader can catch it
  mid-swap).

That's the trade: **give up write parallelism (which you barely need — writes are rare) to make reads
lock-free (which you need constantly).** One writer in, thousands of readers out, nobody blocking
anybody.

### Both indexes get built — and you need both

While ingesting, the writer maintains *two* indexes per shard. People ask why both. Here's the split:

- **Inverted index** — `term → sorted list of document IDs`. Answers *"which documents match this
  term?"* This is the thing that lets you find candidates without scanning.
- **Forward index** — `document ID → the actual bytes`. Answers *"give me the content of document
  X."*

You need both because — as we just saw — retrieval is a **two-phase** operation. First you find
*which* documents match (inverted). Then you fetch their *contents* to filter and return them
(forward). One index can't do both jobs efficiently — a `term → docs` map can't hand you a document's
bytes, and a `docID → bytes` map can't tell you which docs match a term without scanning everything.

> 💻 **Code point:** building the inverted index from a stream of timeline records (`term →
> postings`), and the forward index as an ID-keyed store. This is the heart of ingestion. *(Snippet
> in the repo / gist.)*
{: .prompt-info }

## Why this whole design holds together

Step back and the pieces lock into each other:

- A query is a **needle-in-haystack** problem, so you use an **inverted index** to jump to matches
  instead of scanning. *(Cost scales with matches, not corpus.)*
- One machine can't hold the haystack, so you **shard** it 48 ways and **scatter-gather**. *(48 small
  searches at once, not one giant one.)*
- A scatter-gather waits on its slowest shard, so you **replicate** each shard and use a **load
  balancer + circuit breaker** to always hit a healthy replica.
- You need both correct-and-rebuildable *and* fresh, so you run **batch (HDFS) + stream (Kafka)** —
  lambda architecture.
- You need reads to be lock-free under massive load, so you allow **exactly one writer per shard** and
  store documents **immutably**.
- You need both *"which docs?"* and *"the bytes"*, so you keep **two indexes** — inverted and forward.
- And you select top-K efficiently — within a shard and across shards — with **priority queues**.

Every decision is a response to the one before it.

## The final mental model

If you remember one thing, make it this.

**LinkedIn isn't running a feed. It's running graph-anchored retrieval, and it refuses to do work it
can defer.** It doesn't push your post to followers — it files it under your name and waits for
readers to pull it. It doesn't scan the haystack — it indexes the needles. It doesn't sort everything
— it keeps a heap of the best. It doesn't wait on a sick replica — it routes around it.

A feed, built this way, is just a very disciplined answer to two questions: *"file this so it's easy
to find"* and *"find the relevant things fast."* Ingestion owns the first. Retrieval owns the second.
They meet at the shards and nowhere else.

YouTube would build this differently, because it's chasing watch time. Instagram would build it
differently, because it's chasing discovery. LinkedIn built *this* — because it's a graph, not a
feed.

---

*This is a reconstruction inferred from LinkedIn's public engineering blog. Quantitative claims —
48 shards, 16 replicas, 30-day window, 300+ terms, ~3,000 candidates, ~150,000 data accesses, 40ms
p99 (the 99th-percentile latency — 99 out of 100 queries finish within it), ~5× memory vs. Java,
50% hardware cut — come from that article. The lambda/HDFS/Kafka layering,
the timeline record, the single-writer/lock-free model, and both indexes are described there. The
circuit-breaker pairing and the priority-queue mechanics for top-K and merging are standard-practice
reconstruction, flagged inline. Nothing here is LinkedIn's source code, and this isn't affiliated with
LinkedIn.*

*Want the full design-doc treatment — requirements, HLD, and sequence diagrams for the ingestion and
retrieval flows? See the [deep-dive](https://github.com/jkaus324/engineering-systems/blob/main/teardowns/fishdb/DESIGN.md).*

*Runnable code — inverted index, per-shard heap, k-way merge, and a ranking step you can drive
yourself — lives in the [companion repo](https://github.com/jkaus324/engineering-systems/tree/main/teardowns/fishdb)
with the snippets above pulled out as gists. [Links.]*
