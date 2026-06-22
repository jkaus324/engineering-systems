# `code/` — Illustrative FishDB retrieval prototype (Java)

> ⚠️ **This is an ILLUSTRATIVE prototype.** It mirrors the *concepts* of LinkedIn's FishDB
> retrieval engine so you can run them and see how they fit together. It is **not** FishDB's real
> implementation, contains none of LinkedIn's source code, and is not affiliated with or endorsed
> by LinkedIn. For the full explanation and citations, see [`../DESIGN.md`](../DESIGN.md).
>
> Note: FishDB itself is written in Rust (that choice is a big part of the story — see DESIGN.md).
> This *teaching* prototype is in Java purely for readability; it is not meant to reproduce
> FishDB's memory or latency characteristics.

## Run it

```bash
cd code
javac fishdb/*.java
```

Then either run the **scripted walkthrough** (runs one query end to end and exits):

```bash
java fishdb.Main
```

…or the **interactive prompt**, where *you* type interest terms and watch retrieval happen live:

```bash
java fishdb.Main -i
```

JDK 21+ (uses records and the virtual-thread executor). No external dependencies — standard
library only.

### Interactive mode

```
fishdb> rust databases          # OR-query: posts matching ANY term (bare words → topic:rust ...)
fishdb> and rust databases      # AND-query: posts matching ALL terms
fishdb> author:alice            # authors are searchable terms too
fishdb> terms                   # list every searchable term + its posting list
fishdb> posts                   # list the sample corpus
fishdb> shards                  # show how posts are partitioned across shards
fishdb> help                    # full command list
fishdb> quit                    # leave
```

Each query prints the posting lists it consulted, the candidates retrieved, the
**index-skipped-vs-full-scan** contrast, the scatter-gather result across shards, and the final
ranked feed — so you can *see* every stage of the pipeline respond to your input.

## What it demonstrates

| Step | FishDB concept | Where in the code |
|------|----------------|-------------------|
| 1 | **Inverted index** (`term → [postIDs]`) | `InvertedIndex.build` |
| 2 | **Retrieval without scanning** (cost ∝ matches, not corpus) | `InvertedIndex.retrieve`; contrast printed in `Main` |
| 3 | **Scatter-gather** across shards, run concurrently | `Broker.partition`, `Broker.scatterGather` |
| 4 | **Ranking** (score + sort) — the step *after* retrieval | `Ranker.rank` |

## Files (`fishdb/` package)

- `Post.java` — one post (an immutable `record`, echoing FishDB's immutable documents).
- `Corpus.java` — the tiny 16-post sample corpus (read it; it's the whole dataset).
- `InvertedIndex.java` — the inverted index and the sorted-list union/intersection merges.
- `Broker.java` — partitioning + the broker's concurrent scatter-gather-merge (virtual threads).
- `Ranker.java` — a deliberately trivial scoring + sort, to show retrieval ≠ ranking.
- `Repl.java` — the interactive prompt (`-i`): parses commands and narrates each query stage.
- `Main.java` — entry point; scripted walkthrough by default, REPL with `-i`.

## What it deliberately leaves out

Everything that makes FishDB a *production* system rather than a teaching toy: the forward/
reference indexes and attribute stores, RocksDB + bloom filter + LRU, the lambda (batch + stream)
ingestion pipeline, copy-on-write concurrent writes, skiplist posting lists, the query language and
Volcano iterator engine, real network RPC between broker and partitions, replication, and a real
ranking model. Those are described — with citations — in [`../DESIGN.md`](../DESIGN.md).
