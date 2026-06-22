package fishdb;

import java.util.List;

/**
 * The sample corpus — our tiny stand-in for FishDB's 30-day, in-memory feed window.
 *
 * <p>In FishDB this would be enormous and spread across 48 partitions. Here it is 16
 * posts so you can read the entire dataset and see exactly what the inverted index
 * does to it.
 */
public final class Corpus {

    private Corpus() {
    }

    public static List<Post> posts() {
        return List.of(
            new Post(1, "alice", List.of("topic:rust", "topic:databases"), 120, "Why we moved our index layer to Rust"),
            new Post(2, "bob", List.of("topic:java", "topic:gc"), 30, "Tuning the G1 collector for tail latency"),
            new Post(3, "carol", List.of("topic:rust", "topic:systems"), 200, "Lock-free reads with immutable byte blocks"),
            new Post(4, "dave", List.of("topic:feed", "topic:ranking"), 75, "Retrieval is not ranking: a primer"),
            new Post(5, "alice", List.of("topic:databases", "topic:indexing"), 95, "Inverted indexes for people who skipped search class"),
            new Post(6, "erin", List.of("topic:kafka", "topic:streaming"), 40, "Keeping an in-memory index fresh with Kafka"),
            new Post(7, "frank", List.of("topic:java", "topic:memory"), 22, "Where your Java heap actually goes"),
            new Post(8, "carol", List.of("topic:rust", "topic:databases", "topic:indexing"), 310, "dashmap, posting lists, and copy-on-write"),
            new Post(9, "grace", List.of("topic:feed", "topic:systems"), 60, "Scatter-gather, explained with too many diagrams"),
            new Post(10, "heidi", List.of("topic:ranking", "topic:ml"), 88, "Scoring 3,000 candidates in 40ms"),
            new Post(11, "ivan", List.of("topic:rocksdb", "topic:databases"), 51, "Bloom filters and LRU in front of RocksDB"),
            new Post(12, "bob", List.of("topic:systems", "topic:rust"), 140, "Ownership as a latency feature"),
            new Post(13, "judy", List.of("topic:streaming", "topic:feed"), 33, "Lambda architecture in practice"),
            new Post(14, "ken", List.of("topic:gc", "topic:memory"), 18, "The perpetual straggler problem"),
            new Post(15, "alice", List.of("topic:rust", "topic:ranking"), 175, "From candidate set to ranked feed"),
            new Post(16, "leo", List.of("topic:indexing", "topic:systems"), 64, "Skiplists: when posting lists get long")
        );
    }
}
