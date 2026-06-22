# 🛠️ Setup, Hosting & Contributing

> Part of **[Under the Hood](./README.md)** — code-level teardowns of real engineering systems.

## 🌐 Hosting on GitHub Pages (one-time setup)

This repo is **both** a blog (GitHub Pages, via the Chirpy theme) and a browsable code resource. It
deploys via GitHub Actions — no classic Pages auto-build (Chirpy needs Ruby 3 + plugins outside the
classic safelist, so Actions builds it in the cloud).

### 1. Push this repo to GitHub

Name it `engineering-systems` (a **project site**) or `<username>.github.io` (a **user site**) and set
`_config.yml` accordingly:

| Option | Repo name | Published at | `_config.yml` `baseurl` |
|--------|-----------|--------------|-------------------------|
| **Project site** (default here) | `engineering-systems` | `https://<username>.github.io/engineering-systems` | `"/engineering-systems"` |
| **User site** (cleanest URL) | `<username>.github.io` | `https://<username>.github.io` | `""` |

### 2. Edit the placeholders

- `_config.yml`: `url`, `baseurl`, `github.username`, `social.name`, `social.links`.
- `_tabs/about.md` and the root `README.md`: your name, bio, links, and the blog/repo URLs.
- The post footer and the teardown READMEs: fix the `jkaus324` repo links.

### 3. Enable Pages via Actions

Repo **Settings → Pages → Build and deployment → Source → GitHub Actions.**
Every push to `main` now rebuilds and redeploys automatically.

## Local preview (optional)

> ⚠️ Requires **Ruby 3.x** + Bundler. macOS system Ruby (2.6) is too old — install a newer one
> (`brew install ruby` or `rbenv install 3.3.x`). Not needed to publish; Actions handles that.

```bash
bundle install
bundle exec jekyll serve --livereload   # http://127.0.0.1:4000/engineering-systems
```

## Adding a new teardown

See [`NEW_TEARDOWN.md`](./NEW_TEARDOWN.md) — it's one post + one `teardowns/<name>/` folder, no infra
changes.

## Corrections

These are reconstructions from public material. If a detail disagrees with the original source, trust
the source and open an issue. Suggestions for systems to tear down next are welcome.
