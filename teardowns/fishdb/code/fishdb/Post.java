package fishdb;

import java.util.List;

/**
 * One item in the corpus — the smallest unit FishDB would retrieve.
 *
 * <p>In the real system a document carries far more (actor, verb, object, encoded
 * attribute blocks...). Here we keep just what the retrieval demo needs: an ID, an
 * author, the topics it is indexed under, and an engagement signal so the ranking
 * step has something to sort on.
 *
 * <p>A {@code record} is used because a Post is immutable data — which also echoes
 * FishDB's forward index, where documents are stored as immutable encoded blocks.
 */
public record Post(int id, String author, List<String> terms, int likes, String headline) {
}
