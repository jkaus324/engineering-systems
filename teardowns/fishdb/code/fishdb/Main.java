package fishdb;

import java.util.List;

/**
 * An ILLUSTRATIVE prototype that mirrors the <em>concepts</em> of LinkedIn's FishDB
 * retrieval engine — NOT its real implementation, and not affiliated with LinkedIn. It
 * exists so a reader can run it and "get" the core idea behind the teardown in
 * ../DESIGN.md.
 *
 * <p>It demonstrates, in order:
 * <ol>
 *   <li>Building an INVERTED INDEX over a small corpus of posts.</li>
 *   <li>RETRIEVAL via the index — fetching candidates for a viewer's interests WITHOUT
 *       scanning every post (it prints candidates touched vs. total).</li>
 *   <li>SCATTER-GATHER — sharding the corpus across N in-memory partitions, querying
 *       them concurrently, then gathering + merging.</li>
 *   <li>A trivial RANKING step (score + sort) to show retrieval vs. ranking.</li>
 * </ol>
 *
 * <p>Two ways to run it:
 * <ul>
 *   <li>{@code java fishdb.Main}      — a scripted walkthrough (runs one query, exits).</li>
 *   <li>{@code java fishdb.Main -i}   — an INTERACTIVE prompt: type your own interest terms and
 *       watch retrieval, scatter-gather, and ranking happen live (see {@link Repl}).</li>
 * </ul>
 *
 * <p>Source for the concepts (cited throughout ../DESIGN.md):
 * https://www.linkedin.com/blog/engineering/infrastructure/fishdb-a-generic-retrieval-engine-for-scaling-linkedins-feed
 */
public final class Main {

    private static final int NUM_SHARDS = 4;

    public static void main(String[] args) {
        // Interactive mode: hand control to the REPL so the user drives the queries.
        for (String a : args) {
            if (a.equals("-i") || a.equals("--interactive")) {
                new Repl(Corpus.posts(), NUM_SHARDS).start();
                return;
            }
        }
        scriptedWalkthrough();
    }

    /** The original guided demo: one hard-coded query, narrated end to end. */
    private static void scriptedWalkthrough() {
        // The viewer's context: the interest terms we want feed candidates for.
        // (In FishDB a real query can carry 300+ terms; we use a handful.)
        List<String> viewerInterests = List.of("topic:rust", "topic:databases");
        List<Post> corpus = Corpus.posts();

        banner("FishDB teardown — illustrative retrieval prototype (NOT real FishDB)");
        System.out.printf("Corpus size: %d posts%n", corpus.size());
        System.out.printf("Viewer interests: %s%n", String.join(", ", viewerInterests));

        // ── 1 & 2: single-index retrieval, and the scan-vs-index contrast ─────────
        section("1+2. Inverted-index retrieval (no full scan)");
        InvertedIndex idx = InvertedIndex.build(corpus);
        InvertedIndex.Result result = idx.retrieve(viewerInterests);

        System.out.println("Posting lists consulted:");
        for (String t : viewerInterests) {
            System.out.printf("    %-18s -> %s%n", t, idx.posting(t));
        }
        System.out.printf("%nCandidates retrieved: %s  (%d posts)%n",
            result.candidates(), result.candidates().size());
        System.out.printf("Posting-list entries touched: %d%n", result.entriesTouched());
        System.out.printf("Full-scan would have examined: %d posts (the entire corpus)%n", corpus.size());
        System.out.printf("-> The index let us ignore %d posts that can't match.%n",
            corpus.size() - result.candidates().size());
        System.out.println("   cost is proportional to matches, not to corpus size -- that's the whole trick.");

        // Show that AND-retrieval (narrowing) uses the same sorted-merge primitive.
        List<Integer> both = InvertedIndex.intersectSorted(
            List.of(idx.posting("topic:rust"), idx.posting("topic:databases")));
        System.out.printf("%n(For contrast, AND of both interests -- posts matching BOTH: %s)%n", both);

        // ── 3: scatter-gather across shards ───────────────────────────────────────
        section("3. Scatter-gather across shards (concurrent)");
        final int numShards = NUM_SHARDS;
        Broker broker = Broker.partition(corpus, numShards);

        System.out.printf("Partitioned corpus across %d shards (by author, like FishDB's actor-ID key):%n", numShards);
        List<Integer> counts = broker.perShardCounts();
        for (int i = 0; i < counts.size(); i++) {
            System.out.printf("    shard %d: %d posts%n", i, counts.get(i));
        }

        Broker.Gathered gathered = broker.scatterGather(viewerInterests);
        System.out.printf("%nScattered query to %d shards in parallel, gathered + merged partials.%n", numShards);
        System.out.printf("Merged candidates: %s  (%d posts)%n",
            gathered.candidateIds(), gathered.candidateIds().size());
        System.out.printf("Total posting-list entries touched across shards: %d%n", gathered.entriesTouched());
        System.out.println("-> Same candidates as the single index -- partitioning changes");
        System.out.println("   the topology, not the algorithm. 4 small searches ran at once.");

        // ── 4: ranking — score + sort the retrieved candidates ────────────────────
        section("4. Ranking (score + sort) -- retrieval is done; now we order");
        List<Ranker.Ranked> ranked = Ranker.rank(gathered.posts(), viewerInterests);
        System.out.printf("Retrieval handed %d candidates to ranking. Final order:%n%n", ranked.size());
        System.out.printf("    %-6s %-7s  %s%n", "score", "id", "headline");
        System.out.printf("    %-6s %-7s  %s%n", "-----", "--", "--------");
        for (Ranker.Ranked r : ranked) {
            System.out.printf("    %-6.0f #%-6d %s%n", r.score(), r.post().id(), r.post().headline());
        }

        System.out.println();
        banner("Done. Retrieval SELECTS candidates; ranking ORDERS them. FishDB does the first.");
    }

    // ── tiny printing helpers ─────────────────────────────────────────────────────

    private static void banner(String s) {
        String line = "=".repeat(s.length() + 2);
        System.out.printf("%n%s%n %s%n%s%n", line, s, line);
    }

    private static void section(String s) {
        String rule = "-".repeat(62);
        System.out.printf("%n%s%n%s%n%s%n", rule, s, rule);
    }
}
