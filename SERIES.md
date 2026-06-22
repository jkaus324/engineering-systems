# 🗺️ Series Plan: Feed Systems Teardowns

> Part of **[Under the Hood](./README.md)** — code-level teardowns of real engineering systems.

A 4-post arc that builds from a single-system teardown to an original synthesis. The point of the
sequencing: each post ships on its own, gets you reps and reader signal, **and** becomes raw material
for the finale. You're not choosing between "one system" and "many systems" — you're sequencing them
so the breadth post lands with a thesis instead of being a survey.

**Voice target throughout:** Bhupesh Kumar's style — ~2,000 words, 10-min read, hook-driven open,
short punchy paragraphs, component-by-component walkthrough, tradeoffs in paired contrasts, a "final
mental model" close. Mobile-first (his readers are on the LinkedIn Android app). Always framed as
*"how X likely works"* — reconstruction, not insider claims.

**Honesty stance (every post):** reconstructed from public engineering blogs/talks; cite sources;
never imply real source code; flag anything inferred.

---

## Post 1 — LinkedIn FishDB *(published — post in `_posts/`, code in `teardowns/fishdb/`)*

**Title:** *How LinkedIn Built Its Feed to Answer You in 40 Milliseconds*

**One-line thesis:** They made the feed faster by searching *less*, not harder.

**Why it's first:** It's nearly done, it's a single clean source (low fact-check risk), and it
teaches the foundational primitive the whole series leans on — the inverted index and
retrieval-vs-ranking. It's also the lowest-risk way to test whether the *format and voice* land
before you invest in bigger research.

**Core teach:** inverted index (cost ∝ matches, not corpus), scatter-gather, why Rust fixed the GC
tail, lambda freshness.

**The hook it plants for later:** FishDB pulls candidates at *read* time and does **no fan-out**.
That's a deliberate choice — and it's the exact axis Post 4 is about. End Post 1 having quietly
established "read-side retrieval."

**Code:** the Java prototype in this repo (inverted index + scatter-gather + ranking), embedded as a
snippet, linked as a gist/repo to run.

---

## Post 2 — Twitter / X: fan-out-on-write

**Title (draft):** *Why Twitter Writes Your Tweet to Millions of Timelines Before You Refresh*

**One-line thesis:** Twitter does the opposite of LinkedIn — it pays at *write* time so reads are
trivial. And that breaks the moment a celebrity tweets.

**Core teach:**
- **Fan-out-on-write:** when you tweet, push it into every follower's precomputed timeline now, so
  their read is a cheap cache lookup later.
- **The celebrity problem:** fanning out to 100M followers per tweet is insane. So the **hybrid** —
  fan out for normal users, fall back to pull-at-read for huge accounts, merge at read time.
- Redis-backed timeline caches; the write-amplification tradeoff.

**Paired-contrast framing:** *"LinkedIn pays on read. Twitter pays on write. Neither is free — they
just moved the bill."*

**Sources to reconstruct from:** Twitter's classic "Timelines at Scale" talk, the Redis/timeline
fan-out write-ups, follow-up engineering posts. (Higher fact-check care — multiple sources, some
dated.)

**Series thread:** explicitly call back to Post 1 — "remember LinkedIn pulled at read with no
fan-out? Twitter is the mirror image." You're now setting up a spectrum.

---

## Post 3 — Instagram / Meta: the blend + ranking pressure

**Title (draft):** *How Instagram Decides What You See Without Pushing or Pulling Purely*

**One-line thesis:** At Meta's scale, pure push and pure pull both lose — the real systems live in
the messy middle, and ranking is where the cost actually moved.

**Core teach:**
- Why a pure model doesn't survive: blended fan-out, aggressive caching, candidate generation +
  heavy ML ranking.
- The shift from "retrieve then maybe rank" to "retrieval *exists to feed* ranking" — candidate
  generation as a first-class stage.
- Connect back to FishDB's retrieval-vs-ranking split (Post 1) — now it's the center of gravity.

**Paired-contrast framing:** *"FishDB's ranking was a downstream step. At Meta scale, ranking is the
product, and retrieval is just feeding it candidates."*

**Series thread:** by end of Post 3 the reader has three data points — read-side (LinkedIn),
write-side (Twitter), blend (Meta). The spectrum is fully populated. Post 4 names it.

---

## Post 4 — The synthesis *(the payoff piece)*

**Title (draft):** *Every Feed You Use Is Answering One Question: Who Pays the Cost — the Writer or
the Reader?*

**One-line thesis:** Fan-out-on-write vs fan-out-on-read isn't four companies' quirks — it's a single
design axis, and where each lands is predictable from their constraints.

**Why this is the original contribution:** This is the post no single engineering blog gives you. The
systems are *evidence*, not the subject. The reader walks away with a **mental model they can apply to
any feed**, not trivia about four companies.

**The spine (one question, four data points):**
- Restate the axis: **who pays — writer or reader?** Push = pay on write, cheap reads. Pull = cheap
  writes, expensive reads.
- Plot each system on it: LinkedIn (pull/read, Post 1), Twitter (push/write, Post 2), Instagram/Meta
  (hybrid, Post 3), and one counter-example for tension.
- **Predict, don't just describe:** show *why* each landed where it did from their constraints
  (follower-graph skew, read/write ratio, freshness needs, memory cost). The reader should be able to
  guess a new system's choice from its constraints.
- The deeper point: the choice is really *"where do you want your scaling pain?"* — there is no free
  option, only a relocated bill.

**Paired-contrast close / final mental model:** *"You can't avoid the cost of a feed. You can only
choose who pays it. Every architecture in this series is one answer to that single question."*

**Format note:** ONE good diagram — the push↔pull spectrum with the systems plotted — does more than
any prose here. This is the post that earns a visual.

---

## Sequencing logic (why this order beats "one big survey")

1. **Ship value early.** Post 1 publishes now; you get reader signal before the heavy research.
2. **Reps before the hard one.** Posts 2–3 build your voice and fact-checking muscle on
   progressively trickier sources, so Post 4 is written by a sharper version of you.
3. **The finale compounds.** Each single teardown is a section you can reference in Post 4 — the
   synthesis costs less *and* reads better because the groundwork is already published and linked.
4. **A series has an arc.** "Three teardowns building to one unifying idea" is a stronger story than
   a 6,000-word survey nobody finishes — and it gives readers a reason to follow you for the next one.

---

## Risk notes

- **Fact-check load rises post-to-post.** LinkedIn = one source. Twitter/Meta = multiple, sometimes
  dated or contradictory. Budget more verification time for Posts 2–4; reconstruction errors creep in
  exactly where sources disagree.
- **Keep the "likely / reconstructed" framing.** It's honest, it's Bhupesh's proven hook, and it
  protects you when sources conflict.
- **Don't let Post 4 become a list.** If you catch yourself writing "and here's company five, and
  company six," stop — the thesis (who pays the cost) is the spine, systems are only evidence for it.
