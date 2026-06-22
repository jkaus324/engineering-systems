package fishdb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ranking — the step AFTER retrieval.
 *
 * <p>Retrieval ({@link InvertedIndex} + {@link Broker}) SELECTS the candidates. Ranking
 * SCORES them and puts them in order. In FishDB these are different stages: FishDB
 * returns ~3,000 candidates, and a downstream ranker scores them. We keep ranking
 * trivial on purpose — a toy score + a sort — so the retrieval/ranking boundary is
 * obvious without pretending to be a real ranking model.
 */
public final class Ranker {

    private Ranker() {
    }

    /** A post paired with the score the ranking step assigned it. */
    public record Ranked(Post post, double score) {
    }

    /**
     * Scores each candidate and returns them in descending score order.
     *
     * <p>The toy scoring function rewards engagement and how many of the viewer's interest
     * terms the post matched — enough to make the ordering meaningful, not so much that it
     * distracts from the point (retrieval already did the hard part of narrowing the corpus
     * down to these few candidates).
     */
    public static List<Ranked> rank(List<Post> candidates, List<String> interests) {
        Set<String> interestSet = new HashSet<>(interests);

        List<Ranked> ranked = new ArrayList<>();
        for (Post p : candidates) {
            long matches = p.terms().stream().filter(interestSet::contains).count();
            // score = engagement signal + a boost per matched interest term.
            double score = p.likes() + matches * 50.0;
            ranked.add(new Ranked(p, score));
        }

        ranked.sort(
            Comparator.comparingDouble(Ranked::score).reversed()     // higher score first
                .thenComparingInt(r -> r.post().id())                // stable tie-break by ID
        );
        return ranked;
    }
}
