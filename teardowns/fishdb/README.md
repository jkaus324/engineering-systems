<div align="center">

# 🔩 Teardown 01 — LinkedIn FishDB

**LinkedIn's feed retrieval engine** — how the app, the moment you open it, picks the few thousand
relevant posts out of a huge pool, *fast*.

<sub>A Rust engine that replaced the legacy Java "FollowFeed," hitting 40 ms p99 while cutting feed
hardware in half.</sub>

[![Part of: Under the Hood](https://img.shields.io/badge/part%20of-Under%20the%20Hood-f5a623?style=flat-square)](../../README.md)
[![Prototype: Java 21+](https://img.shields.io/badge/prototype-Java%2021+-007396?style=flat-square&logo=openjdk&logoColor=white)](./code)
[![Method: reconstruction](https://img.shields.io/badge/method-honest%20reconstruction-blue?style=flat-square)](#-source--honesty)

</div>

---

This folder is the **code + deep-dive home** for the teardown. The narrative blog post lives in the
repo's [`_posts/`](../../_posts/) (and on the [published blog](https://jkaus324.github.io/engineering-systems)).

## 📂 Contents

| File | What it is |
|------|------------|
| 🧠 [`DESIGN.md`](./DESIGN.md) | The deep-dive design doc — problem → requirements → HLD → ingestion & retrieval flows (with sequence diagrams) → decisions & rationale. |
| 💻 [`code/`](./code/) | A small, runnable Java prototype of the core retrieval mechanism. See [`code/README.md`](./code/README.md). |

## 🚀 Run the prototype

```bash
cd code
javac fishdb/*.java
java fishdb.Main        # scripted walkthrough — one query, narrated end to end
java fishdb.Main -i     # INTERACTIVE — type your own interest terms, watch retrieval live
```

JDK 21+, no external dependencies.

## 💡 The one-paragraph idea

LinkedIn is a *graph* platform, so its feed is **graph-anchored retrieval**. Posts are stored as
**timeline records** `(actor, verb, object)` and indexed in an **inverted index** where the *actor is
itself a search term* — so when you post, nothing is fanned out; your post is filed under your name
and **readers pull it** at query time. A **broker** scatters each viewer's 300+-term query across 48
shards (16 replicas each, chosen via load balancer + circuit breaker), each shard returns its top-K
via a heap, and the broker does a **k-way merge** into ~3,000 candidates — which a separate ranking
system then orders.

## 🧾 Source & honesty

Reconstructed from [LinkedIn Engineering — *FishDB: A generic retrieval engine for scaling LinkedIn's
feed*](https://www.linkedin.com/blog/engineering/infrastructure/fishdb-a-generic-retrieval-engine-for-scaling-linkedins-feed).
Not LinkedIn's source code; not affiliated with LinkedIn. Quantitative claims trace to the article;
the circuit-breaker pairing and priority-queue mechanics are standard-practice reconstruction (flagged
in [`DESIGN.md`](./DESIGN.md)).
