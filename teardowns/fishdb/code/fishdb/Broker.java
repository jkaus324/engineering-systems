package fishdb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Scatter-gather across shards.
 *
 * <p>The real corpus is far too large for one host, so FishDB splits it across 48
 * partitions and a broker fans each query out to all of them in parallel, then merges
 * the partial results. We do the same in miniature: partition the corpus across N
 * shards, each with its OWN inverted index, query them concurrently, then gather + merge.
 *
 * <p>Note what stays the same as the single-index case: each shard runs the exact
 * retrieval from {@link InvertedIndex} on its slice. Partitioning does not change the
 * algorithm — it just runs many small copies of it at once.
 */
public final class Broker {

    /** One partition: a slice of the corpus plus an index over just that slice. */
    public static final class Shard {
        final int id;
        final Map<Integer, Post> posts;     // ID -> Post (a tiny "forward index")
        final InvertedIndex index;          // inverted index over only this shard's posts

        Shard(int id, Map<Integer, Post> posts, InvertedIndex index) {
            this.id = id;
            this.posts = posts;
            this.index = index;
        }

        int size() {
            return posts.size();
        }
    }

    private final List<Shard> shards;

    private Broker(List<Shard> shards) {
        this.shards = shards;
    }

    /**
     * Splits posts across {@code n} shards by author, mirroring FishDB's choice to
     * partition the feed by actor ID. Hashing the author keeps each author's posts
     * together on one shard (as FishDB does) and makes the demo deterministic.
     */
    public static Broker partition(List<Post> posts, int n) {
        List<List<Post>> buckets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            buckets.add(new ArrayList<>());
        }
        for (Post p : posts) {
            int s = Math.floorMod(p.author().hashCode(), n);
            buckets.get(s).add(p);
        }
        List<Shard> shards = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<Integer, Post> forward = new HashMap<>();
            for (Post p : buckets.get(i)) {
                forward.put(p.id(), p);
            }
            shards.add(new Shard(i, forward, InvertedIndex.build(buckets.get(i))));
        }
        return new Broker(shards);
    }

    /** The merged outcome of a scatter-gather: candidate IDs, the Posts, and total entries touched. */
    public record Gathered(List<Integer> candidateIds, List<Post> posts, int entriesTouched) {
    }

    /**
     * The heart of the demo: SCATTER the query to every shard (each on its own task),
     * GATHER the partial candidate lists, then MERGE into a single candidate set.
     *
     * <p>Uses a virtual-thread executor (Java 21) so each shard query runs concurrently
     * without us hand-managing a thread pool — the closest idiomatic Java analogue to
     * firing one lightweight worker per partition.
     */
    public Gathered scatterGather(List<String> terms) {
        List<InvertedIndex.Result> partials = new ArrayList<>();
        int entriesTouched = 0;

        // ── SCATTER ── submit one retrieval task per partition; they run concurrently.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<InvertedIndex.Result>> futures = new ArrayList<>();
            for (Shard sh : shards) {
                Callable<InvertedIndex.Result> task = () -> sh.index.retrieve(terms);
                futures.add(pool.submit(task));
            }
            // ── GATHER ── collect each partition's partial result (this is the barrier).
            for (Future<InvertedIndex.Result> f : futures) {
                InvertedIndex.Result r = f.get();
                partials.add(r);
                entriesTouched += r.entriesTouched();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("scatter-gather interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("a shard query failed", e.getCause());
        }

        // ── MERGE ── k-way union of the shards' partial lists (same primitive as merging
        // posting lists within a single index — lifted to "across shards").
        List<List<Integer>> lists = new ArrayList<>();
        for (InvertedIndex.Result r : partials) {
            lists.add(r.candidates());
        }
        List<Integer> mergedIds = InvertedIndex.unionSorted(lists);

        // Resolve IDs to Post records via each shard's forward index.
        Map<Integer, Post> byId = new HashMap<>();
        for (Shard sh : shards) {
            byId.putAll(sh.posts);
        }
        List<Post> mergedPosts = new ArrayList<>();
        for (int id : mergedIds) {
            mergedPosts.add(byId.get(id));
        }
        return new Gathered(mergedIds, mergedPosts, entriesTouched);
    }

    /** Posts-per-shard, purely for printing the partition layout so the scatter is visible. */
    public List<Integer> perShardCounts() {
        List<Integer> counts = new ArrayList<>();
        for (Shard sh : shards) {
            counts.add(sh.size());
        }
        return counts;
    }

    public int shardCount() {
        return shards.size();
    }
}
