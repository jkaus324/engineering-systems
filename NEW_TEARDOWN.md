# ➕ Adding a New Teardown

> Part of **[Under the Hood](./README.md)** — code-level teardowns of real engineering systems.

This repo is built to grow. Each teardown is **one blog post + one self-contained code folder** —
nothing else in the repo needs to change. Here's the checklist.

## 1. Create the code/deep-dive folder

```
teardowns/<name>/
├── README.md     # local hub (copy the structure from teardowns/fishdb/README.md)
├── DESIGN.md     # optional deep-dive design doc
└── code/         # self-contained, runnable prototype + its own README
```

Rules of thumb:
- **Self-contained.** No shared build system across teardowns — clone, `cd`, run. State the language
  + version in the folder's README.
- **Small enough to read in one sitting.** The prototype demonstrates the *core idea*, not a
  production clone.
- **Label it illustrative.** A top comment / README note: mirrors the concepts, not the real system.

## 2. Write the blog post

Create `_posts/YYYY-MM-DD-slug.md` with this front matter:

```yaml
---
title: "Your hook-driven title"
description: "One-line summary for the post list + SEO."
date: 2026-07-01 09:00:00 +0530
categories: [Teardowns, <Category>]
tags: [company, system-design, key-algorithm]
mermaid: true   # only if the post uses Mermaid diagrams
toc: true
---
```

Style guide (what's worked so far):
- **Hook first.** Open with a relatable scenario or a counter-intuitive claim, not a definition.
- **Build the architecture one decision at a time.** Each choice answers a constraint from the
  previous one.
- **Name the algorithm.** The connection between a design choice and why the algorithm stays
  efficient is the most valuable insight.
- **Diagrams = Mermaid**, not wide ASCII (renders responsively on mobile). Keep ASCII only for tiny
  inline things (a data-structure example, short pseudocode ≤ ~40 chars wide).
- **Callout boxes:** add `{: .prompt-tip }` / `.prompt-info` / `.prompt-warning` / `.prompt-danger`
  after a blockquote.
- **Mark code points** with a `💻 **Code point:**` callout and link to the snippet/gist or the
  `teardowns/<name>/code/` folder.
- **Cite the source, frame as reconstruction**, flag anything inferred-beyond-the-source inline, and
  add the disclaimer footer.

## 3. Wire it into the hub

- Add a row to the **📚 The teardowns** table in the root [`README.md`](./README.md).
- If it's part of the planned arc, update [`SERIES.md`](./SERIES.md).

## 4. Ship

Push to `main`. The GitHub Actions workflow rebuilds and redeploys the Pages site automatically. The
new `teardowns/<name>/` folder is immediately browsable on GitHub; the post appears on the blog.

That's it — no per-post repo, no infra changes. The resource just grew.
