package fishdb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;

/**
 * An interactive read-eval-print loop so a user can drive the prototype by hand:
 * type interest terms and watch retrieval, scatter-gather, and ranking happen live.
 *
 * <p>This is what makes the prototype something you <em>interact with</em> rather than
 * just watch. It is still ILLUSTRATIVE — it mirrors FishDB's concepts, not its code.
 */
public final class Repl {

    private final List<Post> corpus;
    private final InvertedIndex index;
    private final Broker broker;
    private final int numShards;

    public Repl(List<Post> corpus, int numShards) {
        this.corpus = corpus;
        this.numShards = numShards;
        this.index = InvertedIndex.build(corpus);
        this.broker = Broker.partition(corpus, numShards);
    }

    /** Starts the loop. Reads commands from stdin until EOF or "quit". */
    public void start() {
        printWelcome();
        try (Scanner in = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nfishdb> ");
                if (!in.hasNextLine()) {
                    break; // EOF (e.g. piped input ran out) — exit cleanly
                }
                String line = in.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (!handle(line)) {
                    break; // a command asked us to quit
                }
            }
        }
        System.out.println("\nbye.");
    }

    /** Dispatches one command line. Returns false to exit the loop. */
    private boolean handle(String line) {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase();
        List<String> rest = new ArrayList<>(Arrays.asList(parts).subList(1, parts.length));

        switch (cmd) {
            case "help", "?" -> printHelp();
            case "quit", "exit", "q" -> {
                return false;
            }
            case "terms" -> printTerms();
            case "posts" -> printPosts();
            case "shards" -> printShards();
            case "and" -> runQuery(normalize(rest), Mode.AND);
            case "or" -> runQuery(normalize(rest), Mode.OR);
            default -> {
                // No command word: treat the whole line as OR-query terms.
                List<String> terms = normalize(Arrays.asList(parts));
                runQuery(terms, Mode.OR);
            }
        }
        return true;
    }

    private enum Mode { OR, AND }

    /**
     * Runs a query end-to-end and narrates every stage, so the user sees the index
     * lookup, the scan-vs-index contrast, the scatter-gather, and the ranking.
     */
    private void runQuery(List<String> terms, Mode mode) {
        if (terms.isEmpty()) {
            System.out.println("  (no terms given — try: topic:rust topic:databases)");
            return;
        }
        System.out.printf("%nQuery: %s   (%s)%n", String.join(", ", terms), mode);

        // Show the posting list behind each term so the index is visible.
        System.out.println("  posting lists consulted:");
        List<List<Integer>> lists = new ArrayList<>();
        int entriesTouched = 0;
        for (String t : terms) {
            List<Integer> pl = index.posting(t);
            entriesTouched += pl.size();
            lists.add(pl);
            System.out.printf("    %-20s -> %s%n", t, pl.isEmpty() ? "(no such term)" : pl);
        }

        // Retrieval: OR = union (widen), AND = intersection (narrow).
        List<Integer> candidates = (mode == Mode.OR)
            ? InvertedIndex.unionSorted(lists)
            : InvertedIndex.intersectSorted(lists);

        System.out.printf("%n  candidates (%s): %s  (%d posts)%n",
            mode, candidates, candidates.size());
        System.out.printf("  posting-list entries touched: %d   |   full scan would touch: %d posts%n",
            entriesTouched, corpus.size());
        int ignored = corpus.size() - candidates.size();
        System.out.printf("  -> index skipped %d of %d posts. cost ~ matches, not corpus.%n",
            Math.max(ignored, 0), corpus.size());

        // Scatter-gather: confirm the partitioned path returns the same OR result.
        if (mode == Mode.OR) {
            Broker.Gathered g = broker.scatterGather(terms);
            boolean same = g.candidateIds().equals(candidates);
            System.out.printf("  scatter-gather across %d shards -> %s  (%s single-index result)%n",
                numShards, g.candidateIds(), same ? "matches" : "DIFFERS FROM");
        }

        // Ranking: score + sort the candidates.
        List<Post> posts = resolve(candidates);
        List<Ranker.Ranked> ranked = Ranker.rank(posts, terms);
        System.out.println("\n  ranked feed:");
        if (ranked.isEmpty()) {
            System.out.println("    (nothing matched)");
        }
        for (Ranker.Ranked r : ranked) {
            System.out.printf("    %-5.0f #%-3d %s%n", r.score(), r.post().id(), r.post().headline());
        }
    }

    private List<Post> resolve(List<Integer> ids) {
        List<Post> out = new ArrayList<>();
        for (Post p : corpus) {
            if (ids.contains(p.id())) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Lets the user type bare topics ("rust") and turns them into proper terms
     * ("topic:rust"), while leaving already-qualified terms ("author:alice",
     * "topic:rust") untouched. Small nicety so the prompt is pleasant to use.
     */
    private List<String> normalize(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String t : raw) {
            if (t.isBlank()) {
                continue;
            }
            out.add(t.contains(":") ? t : "topic:" + t);
        }
        return out;
    }

    // ── informational commands ────────────────────────────────────────────────

    private void printWelcome() {
        System.out.println("============================================================");
        System.out.println(" FishDB teardown — INTERACTIVE prototype (NOT real FishDB)");
        System.out.println("============================================================");
        System.out.printf(" %d posts, %d shards. Type interest terms and press enter.%n",
            corpus.size(), numShards);
        System.out.println(" e.g.  rust databases        (bare words become topic:rust ...)");
        System.out.println("       and rust databases    (match ALL terms)");
        System.out.println(" Type 'help' for commands, 'terms' to see what's searchable.");
    }

    private void printHelp() {
        System.out.println("""
            commands:
              <terms>            OR-query (union): match ANY term   e.g.  rust systems
              or <terms>         same as above, explicit
              and <terms>        AND-query (intersection): match ALL terms
              terms              list every searchable term in the index
              posts              list the sample corpus
              shards             show how posts are partitioned across shards
              help | ?           this help
              quit | exit | q    leave

            note: bare words like 'rust' are read as 'topic:rust'. Authors are
                  searchable too, e.g.  author:alice""");
    }

    private void printTerms() {
        Set<String> terms = new TreeSet<>();
        for (Post p : corpus) {
            terms.addAll(p.terms());
            terms.add("author:" + p.author());
        }
        System.out.println("  searchable terms:");
        for (String t : terms) {
            System.out.printf("    %-22s -> %s%n", t, index.posting(t));
        }
    }

    private void printPosts() {
        System.out.println("  corpus:");
        for (Post p : corpus) {
            System.out.printf("    #%-3d %-7s likes=%-4d %s%n",
                p.id(), p.author(), p.likes(), p.headline());
            System.out.printf("         terms: %s%n", p.terms());
        }
    }

    private void printShards() {
        System.out.printf("  %d shards (partitioned by author, like FishDB's actor-ID key):%n", numShards);
        List<Integer> counts = broker.perShardCounts();
        for (int i = 0; i < counts.size(); i++) {
            System.out.printf("    shard %d: %d posts%n", i, counts.get(i));
        }
    }
}
