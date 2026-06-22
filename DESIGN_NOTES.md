# Design Notes — the "Under the Hood" redesign

A short record of what changed in this presentation pass, the reference repos it draws
from, and the rationale. **No technical content was altered** — only structure, framing,
navigation, and the project name.

## The rebrand

- **Old name:** System Teardowns · **New name:** Under the Hood · **slug:** `engineering-systems`.
- **Tagline:** *"What's really happening inside the systems you use every day — reconstructed,
  and rebuilt small enough to run."*
- "Teardown" is kept as the **genre** word for an individual entry ("Teardown 01"); only the
  *project name* changed. The `teardowns/` folder keeps its name (it's the genre word).
- Updated everywhere: `_config.yml` (title/tagline/description, `baseurl: /engineering-systems`),
  root `README.md`, `_tabs/about.md`, the blog post footer links, `CONTRIBUTING.md`,
  `NEW_TEARDOWN.md`, `LICENSE` copyright line, and every `system-teardowns` path/URL.

## Reference repos (patterns borrowed)

| Repo | Pattern adopted here |
|------|----------------------|
| **donnemartin/system-design-primer** | Big scannable index table; "study this in order" framing; heavy use of section anchors. |
| **codecrafters-io/build-your-own-x** | The hero promise as a single verb-driven line; per-entry rows that link straight to runnable material. |
| **trimstray/the-book-of-secret-knowledge** & **awesome-\*** lists | Centered hero, badge row, emoji section anchors, tight visual rhythm, "how to use this repo" block. |
| **public-apis/public-apis** | A clean comparison-style table as the centerpiece of the README. |
| **kamranahmedse/developer-roadmap** | Concept-first storefront — a visitor "gets it" before reading prose. |

## What the redesign does (and deliberately doesn't)

**Does:**
- Rewrites the root `README.md` into a storefront: centered hero, real shields.io badges
  (stars / license / PRs-welcome / made-with-Java + Jekyll), a 5-second concept statement,
  a visual teardowns index, a "two faces" explanation, a run-the-code quickstart, a roadmap,
  and `<details>` blocks for depth-on-demand.
- Gives the per-teardown README and the blog About page a consistent visual language
  (emoji anchors, callouts, tables) with the new brand.
- Adds a Categories/Tags/Archive nav to the Chirpy blog (built-in plugins, no new deps).
- Fixes the broken `pages-deploy.yml` (stray `null` + a stray CloudFormation `Resources:`
  block that would fail the Actions build).

**Doesn't:**
- Touch any technical claim, number, citation, or disclaimer in the post / DESIGN.md / code.
- Rename folders or change the Java prototype (still compiles: `javac fishdb/*.java`).
- Invent stars, testimonials, or fake metrics. Badges render live from GitHub once pushed.

## Markdown constraints respected

GitHub does not render custom CSS / `<style>`. The redesign uses only the inline HTML GitHub
*does* render — `<div align>`, `<img>` (shields.io badges), `<table>`, `<details>`, `<sub>` —
plus Mermaid in fenced ```mermaid blocks (also Chirpy-compatible).

## Left for the human to fill in (clearly marked placeholders)

- `jkaus324` everywhere (README badges/links, `_config.yml`, About, post footer).
- `Your Name` / bio / social links in `_config.yml`, `_tabs/about.md`, and `LICENSE`.
- The published site `url:` in `_config.yml`.
