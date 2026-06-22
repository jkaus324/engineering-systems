package fishdb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The inverted index — FishDB's core retrieval trick.
 *
 * <pre>
 *   term -&gt; sorted list of post IDs that contain that term ("posting list")
 * </pre>
 *
 * <p>Retrieval becomes: look up the query's terms, then merge their posting lists.
 * You touch only the posts a term points at — never the whole corpus. That is the
 * entire reason an index beats a scan: cost is proportional to the number of
 * matches, not to the size of the corpus.
 *
 * <p>Posting lists are kept SORTED so that union/intersection are linear merges of
 * sorted runs — the same primitive a search engine uses. (Real FishDB plans to move
 * these arrays to skiplists once lists get long enough to want to skip ahead.)
 */
public final class InvertedIndex {

    /** term -> ascending, duplicate-free list of post IDs. */
    private final Map<String, List<Integer>> postings = new HashMap<>();

    /**
     * Builds an inverted index from a set of posts. This is the "pay at write time so
     * reads are cheap" half of the trade-off.
     */
    public static InvertedIndex build(List<Post> posts) {
        InvertedIndex idx = new InvertedIndex();
        for (Post p : posts) {
            for (String term : p.terms()) {
                idx.add(term, p.id());
            }
            // Index by author too — in FishDB the actor ID is itself a term.
            idx.add("author:" + p.author(), p.id());
        }
        // Keep every posting list sorted; this is what makes the merges below linear.
        for (List<Integer> list : idx.postings.values()) {
            Collections.sort(list);
        }
        return idx;
    }

    private void add(String term, int postId) {
        postings.computeIfAbsent(term, k -> new ArrayList<>()).add(postId);
    }

    /** Returns the (sorted) posting list for a term, or an empty list if absent. */
    public List<Integer> posting(String term) {
        return postings.getOrDefault(term, List.of());
    }

    /** A retrieval result: the candidate IDs plus how many posting-list entries we touched. */
    public record Result(List<Integer> candidates, int entriesTouched) {
    }

    /**
     * Runs OR-retrieval over the given terms: the union of their posting lists. This is
     * "show me posts matching ANY of the viewer's interests" — the candidate-widening
     * step. The {@code entriesTouched} count lets the caller contrast index work against
     * a full scan.
     */
    public Result retrieve(List<String> terms) {
        List<List<Integer>> lists = new ArrayList<>();
        int entriesTouched = 0;
        for (String t : terms) {
            List<Integer> pl = posting(t);
            entriesTouched += pl.size(); // we only ever look at matching posts
            if (!pl.isEmpty()) {
                lists.add(pl);
            }
        }
        return new Result(unionSorted(lists), entriesTouched);
    }

    /**
     * Merges any number of ascending, duplicate-free int lists into one ascending,
     * duplicate-free list. This is the k-way merge that also reappears at the broker
     * when it merges partial results across shards (see {@link Broker}).
     */
    public static List<Integer> unionSorted(List<List<Integer>> lists) {
        Set<Integer> merged = new TreeSet<>(); // sorted + de-duplicated
        for (List<Integer> l : lists) {
            merged.addAll(l);
        }
        return new ArrayList<>(merged);
    }

    /**
     * Returns IDs present in ALL lists — "matches EVERY interest", the candidate-narrowing
     * AND step. Included to show both set operations the retrieval engine supports, even
     * though {@link Main} demos the OR path.
     */
    public static List<Integer> intersectSorted(List<List<Integer>> lists) {
        if (lists.isEmpty()) {
            return List.of();
        }
        Set<Integer> result = new LinkedHashSet<>(lists.get(0));
        for (int i = 1; i < lists.size(); i++) {
            result.retainAll(new HashSet<>(lists.get(i)));
        }
        List<Integer> out = new ArrayList<>(result);
        Collections.sort(out);
        return out;
    }
}
