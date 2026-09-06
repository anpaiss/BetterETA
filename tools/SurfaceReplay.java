import com.anpaiss.bettereta.OsmWay;
import com.anpaiss.bettereta.RouteWaypoint;
import com.anpaiss.bettereta.SlippyTiles;
import com.anpaiss.bettereta.Surface;
import com.anpaiss.bettereta.SurfaceTagClassifier;
import com.anpaiss.bettereta.SurfaceWayStore;
import kotlin.Pair;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.Way;
import org.mapsforge.map.reader.MapFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.*;

/**
 * Rifà sul PC la classificazione del fondo che l'estensione fa sul Karoo, con
 * le STESSE classi compilate dell'app (SurfaceTagClassifier, SurfaceWayStore,
 * SlippyTiles) e la stessa libreria Mapsforge sulla stessa mappa offline.
 * Input: una traccia GPS (CSV con lat,lon nelle colonne 2-3, es. da
 * tools/fit_to_track.py) e il file .map del Karoo.
 *
 * Stampa: km per classe di fondo lungo la traccia, i tratti contigui con
 * la loro classe, e quante way per classe e per livello mtb_difficulty.
 *
 * Compilazione (JDK 17, dopo un assembleDebug):
 *   javac -cp "<classes>;<kotlin-stdlib>;<mapsforge-core>;<mapsforge-map>;<mapsforge-map-reader>" -d out tools/SurfaceReplay.java
 *   java  -cp "out;<stesso classpath>" SurfaceReplay italy.map track.csv
 * dove <classes> = app/build/tmp/kotlin-classes/debug
 */
public class SurfaceReplay {

    static final int ZOOM = 16;

    public static void main(String[] args) throws Exception {
        File mapFile = new File(args[0]);
        List<RouteWaypoint> track = readTrack(args[1]);
        System.out.printf("traccia: %d punti%n", track.size());

        // Sottocampiona a ~100 m come sampleChain dell'app (per i tile e per il corridoio)
        List<RouteWaypoint> samples = subsample(track, 100.0);
        LinkedHashSet<SlippyTiles.Tile> tiles = new LinkedHashSet<>();
        for (RouteWaypoint p : samples) tiles.add(SlippyTiles.INSTANCE.tileFor(p.getLat(), p.getLon(), ZOOM));
        System.out.printf("campioni: %d, tile a zoom %d: %d%n", samples.size(), ZOOM, tiles.size());

        // Lettura Mapsforge: way con highway, classificate con la regola dell'app
        MapFile reader = new MapFile(mapFile);
        LinkedHashMap<Long, OsmWay> ways = new LinkedHashMap<>();
        Map<Long, String> wayInfo = new HashMap<>();   // id → "highway/mtb_difficulty/surface" per i tratti
        Map<Surface, Integer> waysPerClass = new EnumMap<>(Surface.class);
        Map<String, Integer> mtbLevels = new TreeMap<>();
        Set<String> tagKeys = new TreeSet<>();
        long t0 = System.currentTimeMillis();
        for (SlippyTiles.Tile t : tiles) {
            MapReadResult r = reader.readMapData(new Tile(t.getX(), t.getY(), (byte) ZOOM, 256));
            if (r == null) continue;
            for (Way w : r.ways) {
                Map<String, String> tags = new HashMap<>();
                for (Tag tag : w.tags) tags.put(tag.key, tag.value);
                if (!tags.containsKey("highway")) continue;
                tagKeys.addAll(tags.keySet());
                Surface s = SurfaceTagClassifier.INSTANCE.classify(tags);
                if (s == null) continue;
                if (w.latLongs.length == 0 || w.latLongs[0].length < 2) continue;
                List<Pair<Double, Double>> pts = new ArrayList<>();
                for (org.mapsforge.core.model.LatLong ll : w.latLongs[0]) pts.add(new Pair<>(ll.latitude, ll.longitude));
                long id = geometryId(pts);
                if (ways.containsKey(id)) continue;
                ways.put(id, new OsmWay(id, s, pts));
                waysPerClass.merge(s, 1, Integer::sum);
                String d = tags.getOrDefault("mtb_difficulty", "-");
                wayInfo.put(id, tags.get("highway") + " mtb=" + d + " surface=" + tags.getOrDefault("surface", "-") + " tracktype=" + tags.getOrDefault("tracktype", "-"));
                mtbLevels.merge("mtb_difficulty=" + d, 1, Integer::sum);
            }
        }
        reader.close();
        System.out.printf("way con highway classificate: %d in %d ms%n", ways.size(), System.currentTimeMillis() - t0);
        System.out.println("  per classe: " + waysPerClass);
        System.out.println("  per livello: " + mtbLevels);
        System.out.println("  chiavi tag viste: " + tagKeys);

        // Stessa cache/match dell'app
        SurfaceWayStore store = new SurfaceWayStore(SurfaceWayStore.MAX_WAY_POINTS);
        List<Pair<Double, Double>> run = new ArrayList<>();
        for (RouteWaypoint p : track) run.add(new Pair<>(p.getLat(), p.getLon()));
        store.add(new SurfaceWayStore.Batch(1L, List.of(run), new ArrayList<>(ways.values())));
        SurfaceWayStore.Match m = store.match(track);
        List<Surface> surf = m.getSurfaces();
        List<Long> keys = m.getWayKeys();

        // Km per classe e tratti contigui
        double[] km = new double[Surface.values().length];
        List<String> stretches = new ArrayList<>();
        double cum = 0, stretchStart = 0;
        Surface cur = surf.get(0);
        Map<String, Integer> infoCount = new HashMap<>();
        for (int i = 0; i < track.size() - 1; i++) {
            double d = dist(track.get(i), track.get(i + 1));
            Surface s = surf.get(i);
            km[s.ordinal()] += d;
            if (s != cur) {
                if (cum - stretchStart >= 200) stretches.add(String.format("  km %5.1f - %5.1f  %-7s (%.1f km)  %s", stretchStart / 1000, cum / 1000, cur, (cum - stretchStart) / 1000, dominant(infoCount)));
                cur = s; stretchStart = cum; infoCount = new HashMap<>();
            }
            long k = keys.get(i);
            if (k != 0) infoCount.merge(wayInfo.getOrDefault(k / 2, "?"), 1, Integer::sum);
            cum += d;
        }
        stretches.add(String.format("  km %5.1f - %5.1f  %-7s (%.1f km)  %s", stretchStart / 1000, cum / 1000, cur, (cum - stretchStart) / 1000, dominant(infoCount)));
        System.out.printf("%ntotale %.1f km%n", cum / 1000);
        for (Surface s : Surface.values()) System.out.printf("  %-7s %5.1f km  %3.0f%%%n", s, km[s.ordinal()] / 1000, 100 * km[s.ordinal()] / cum);
        System.out.println("tratti (>= 200 m):");
        stretches.forEach(System.out::println);
    }

    static String dominant(Map<String, Integer> counts) {
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }

    static List<RouteWaypoint> readTrack(String path) throws Exception {
        List<RouteWaypoint> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();   // header
            while ((line = br.readLine()) != null) {
                String[] c = line.split(",");
                double alt = c.length > 5 && !c[5].isEmpty() ? Double.parseDouble(c[5]) : 0.0;
                out.add(new RouteWaypoint(Double.parseDouble(c[1]), Double.parseDouble(c[2]), alt));
            }
        }
        return out;
    }

    static List<RouteWaypoint> subsample(List<RouteWaypoint> pts, double spacingM) {
        List<RouteWaypoint> out = new ArrayList<>();
        out.add(pts.get(0));
        double acc = 0;
        for (int i = 1; i < pts.size(); i++) {
            acc += dist(pts.get(i - 1), pts.get(i));
            if (acc >= spacingM) { out.add(pts.get(i)); acc = 0; }
        }
        return out;
    }

    static double dist(RouteWaypoint a, RouteWaypoint b) {
        double dy = (b.getLat() - a.getLat()) * 111320.0;
        double dx = (b.getLon() - a.getLon()) * 111320.0 * Math.cos(Math.toRadians(a.getLat()));
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Stesso hash FNV-1a dell'app (MapsforgeSurfaceSource.geometryId). */
    static long geometryId(List<Pair<Double, Double>> pts) {
        long h = 0xcbf29ce484222325L;
        for (Pair<Double, Double> p : pts) {
            for (long v : new long[]{Math.round(p.getFirst() * 1e6), Math.round(p.getSecond() * 1e6)}) {
                long x = v;
                for (int k = 0; k < 8; k++) { h = (h ^ (x & 0xff)) * 0x100000001b3L; x >>>= 8; }
            }
        }
        long id = h & 0x3FFFFFFFFFFFFFFFL;   // 62 bit: la chiave (way, verso) è id*2+verso
        return id == 0 ? 1 : id;
    }
}
