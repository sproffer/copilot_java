/**
 * LRU caching class, using apache commons LRUMap
 * compute hits ratio, and reset stats when hits count is too large/overflow
 * save last reset time
 */
import org.apache.commons.collections4.map.LRUMap;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.GregorianCalendar;

public class LRUCaching {
    private static LRUMap<String, String> cache = new LRUMap<String, String>(1000);
    private static long hits = 1;
    private static long misses = 1;
    private static long overflow = Long.MAX_VALUE - 100;  // give some buffer before overflow
    // record lastResetTime for stats reset
    private static java.util.GregorianCalendar  lastResetTime = new java.util.GregorianCalendar();

    public static synchronized void put(String key, String value) {
        cache.put(key, value);
    }
    public static synchronized String get(String key) {
        checkHitsMissesOverflow();
        if (cache.containsKey(key)) {
            hits++;
            return cache.get(key);
        } else {
            misses++;
            return null;
        }
    }
    public static synchronized void remove(String key) {
        cache.remove(key);
    }

    /**
     * Check hits and misses overflow, reset stats if needed, and save reset time.
     */
    private static void checkHitsMissesOverflow() {
        long total = hits + misses;
        if (total >= overflow) {
            resetStats();
        }
    }

    private static synchronized void resetStats() {
        lastResetTime = new java.util.GregorianCalendar();
        hits = 1;
        misses = 1;
    }

    /**
     * output hits ratios and adjust hits misses counts.
     */
    public static synchronized double getHitsRatio() {
        double ratio = (double)hits / (double)(hits + misses);
        return ratio;
    }

    /**
     * return a JSON object for hits ratio, hits, misses, and last reset time.
     */
    public static synchronized  JSONObject getStats() {
        JSONObject stats = new JSONObject();
        stats.put("hits", hits);
        stats.put("misses", misses);
        double hitsRatio = getHitsRatio();
        // get percentage formatted string for hitsRatio, down to 4 decimal points
        String ratioString  = new DecimalFormat("#.0000%").format(hitsRatio);
        stats.put("hitsRatio", ratioString);
        // lastResetTime in local time zone
        stats.put("lastResetTime: ", lastResetTime.getTime().toString());

        return stats;
    }

    /**
     * MAIN to test LRUCaching class, with command line arguments for the number of keys put into the cache and the number of retrievals.
     *
     * @param args argv[0] is the number of keys to put into the cache, argv[1] is the number of retrievals.
     */
    public static void main(String[] args) {
        String arg1 = args[0];
        String arg2 = args[1];
        int numKeys = Integer.parseInt(arg1);
        int numAccess = Integer.parseInt(arg2);
        for (int i = 0; i < numKeys; i++) {
            LRUCaching.put(String.valueOf(i), String.valueOf(i));
        }
        System.out.println("Starting hits stats: " + LRUCaching.getStats().toString(2));
        for (int i = 0; i < numAccess; i++) {
            LRUCaching.get(String.valueOf(i % (numKeys + 100)));  // test some misses
        }
        System.out.println("Ending hits stats: " + LRUCaching.getStats().toString(2));
    }
}