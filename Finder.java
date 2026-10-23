package com.emcfall;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Port of earthmc_fall_finder.py. Rules modelled:
 *  - a mayor offline 42 days loses the town at the next new day (10:00 UTC = 13:00 GMT+3)
 *  - it goes to the active councillor who joined the town first, else the most active
 *    resident (approximated by most recent login), else the town falls
 *  - a nation is inactive when every one of its towns is going to fall
 */
public final class Finder {
    private Finder() {}

    static final String API = "https://api.earthmc.net/v4";
    static final long HOUR = 3_600_000L;
    static final long DAY = 86_400_000L;
    static final int INACTIVE_DAYS = 42;
    static final int NEWDAY_HOUR_UTC = 10;
    static final int BATCH = 100;
    static final long DELAY_MS = 400;   // stays under the 180 requests/min limit

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public record TownRow(String name, String nation, String mayor, boolean open, boolean falls,
                          String outcome, int residents, int chunks, long balance, long at) {}

    public record NationRow(String name, String king, int towns, int residents, int chunks,
                            long balance, long at) {}

    public record Result(List<TownRow> towns, List<NationRow> nations, long generated, int skipped) {}

    // ---- JSON helpers (null-safe) ----

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static Long lng(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonPrimitive() ? Long.valueOf(Math.round(e.getAsDouble())) : null;
    }

    private static long num(JsonObject o, String k) {
        Long v = lng(o, k);
        return v == null ? 0L : v;
    }

    private static JsonObject template(String... fields) {
        JsonObject t = new JsonObject();
        for (String f : fields) t.addProperty(f, true);
        return t;
    }

    // ---- HTTP ----

    private static JsonElement request(String method, String path, String body) throws Exception {
        for (int attempt = 0; attempt < 6; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "emcfall-mod/1.0");
            if (body != null) {
                b.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                b.GET();
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429) {
                Thread.sleep(5000L * (attempt + 1));
                continue;
            }
            if (r.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + r.statusCode() + " from " + path);
            }
            return JsonParser.parseString(r.body());
        }
        throw new IOException("Rate limited for too long, try again later");
    }

    private static List<String> uuids(JsonElement index) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : index.getAsJsonArray()) {
            String u = str(e.getAsJsonObject(), "uuid");
            if (u != null) out.add(u);
        }
        return out;
    }

    private static List<JsonObject> postBatches(String path, List<String> ids, JsonObject template,
                                                Consumer<String> progress, String label) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        int batches = ids.size() / BATCH + 1;
        int n = 0;
        for (int i = 0; i < ids.size(); i += BATCH) {
            JsonObject body = new JsonObject();
            JsonArray q = new JsonArray();
            for (String s : ids.subList(i, Math.min(ids.size(), i + BATCH))) q.add(s);
            body.add("query", q);
            body.add("template", template);
            for (JsonElement e : request("POST", path, body.toString()).getAsJsonArray()) {
                out.add(e.getAsJsonObject());
            }
            n++;
            if (progress != null && batches > 20 && n % 50 == 0) {
                progress.accept(label + " " + n + "/" + batches + "...");
            }
            Thread.sleep(DELAY_MS);
        }
        return out;
    }

    // ---- logic ----

    /** First new day (10:00 UTC) at or after the given time. */
    static long nextNewDay(long ms) {
        ZonedDateTime dt = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC);
        ZonedDateTime day = dt.withHour(NEWDAY_HOUR_UTC).withMinute(0).withSecond(0).withNano(0);
        if (day.isBefore(dt)) day = day.plusDays(1);
        return day.toInstant().toEpochMilli();
    }

    public static Result scan(int hours, Consumer<String> progress) throws Exception {
        long now = System.currentTimeMillis();
        long window = hours * HOUR;
        long inactive = INACTIVE_DAYS * DAY;

        progress.accept("Fetching towns...");
        List<String> townIds = uuids(request("GET", "/towns", null));
        List<JsonObject> all = postBatches("/towns", townIds,
                template("name", "uuid", "mayor", "nation", "residents", "ranks", "status", "stats"),
                progress, "Towns");

        Set<String> ruined = new HashSet<>();
        List<JsonObject> towns = new ArrayList<>();
        for (JsonObject t : all) {
            if (bool(obj(t, "status"), "isRuined")) ruined.add(str(t, "uuid"));
            else towns.add(t);
        }

        Set<String> playerSet = new TreeSet<>();
        for (JsonObject t : towns) {
            for (JsonElement e : arr(t, "residents")) {
                String u = str(e.getAsJsonObject(), "uuid");
                if (u != null) playerSet.add(u);
            }
        }
        List<String> playerIds = new ArrayList<>(playerSet);
        progress.accept("Fetching " + playerIds.size() + " players (about "
                + (playerIds.size() / BATCH + 1) + " requests, a few minutes)...");
        List<JsonObject> players = postBatches("/players", playerIds,
                template("uuid", "timestamps", "status"), progress, "Players");

        Map<String, Long> last = new HashMap<>();
        Map<String, Long> joined = new HashMap<>();
        for (JsonObject p : players) {
            String id = str(p, "uuid");
            JsonObject st = obj(p, "status");
            JsonObject ts = obj(p, "timestamps");
            if (id == null || bool(st, "isNPC")) continue;
            Long lo = lng(ts, "lastOnline");
            if (bool(st, "isOnline")) lo = now;
            if (lo != null) last.put(id, lo);
            Long j = lng(ts, "joinedTownAt");
            if (j != null) joined.put(id, j);
        }

        Map<String, Long> townFall = new HashMap<>();   // absent = won't fall / unknown
        List<TownRow> rows = new ArrayList<>();
        int skipped = 0;

        for (JsonObject t : towns) {
            String tid = str(t, "uuid");
            JsonArray res = arr(t, "residents");
            String mayorId = str(obj(t, "mayor"), "uuid");

            Map<String, Long> known = new HashMap<>();
            Map<String, String> names = new HashMap<>();
            Map<String, String> byName = new HashMap<>();
            for (JsonElement e : res) {
                JsonObject r = e.getAsJsonObject();
                String u = str(r, "uuid");
                String n = str(r, "name");
                if (u == null) continue;
                names.put(u, n == null ? u : n);
                if (n != null) byName.put(n, u);
                Long l = last.get(u);
                if (l != null) known.put(u, l);
            }
            // opted-out players are hidden by the API, so we can't be sure about this town
            if (res.size() == 0 || known.size() < res.size() || mayorId == null
                    || !known.containsKey(mayorId)) {
                skipped++;
                continue;
            }

            long mayorAt = nextNewDay(known.get(mayorId) + inactive);
            if (mayorAt <= now) continue;   // past the deadline but still mayor: probably Premium
            townFall.put(tid, nextNewDay(Collections.max(known.values()) + inactive));
            if (mayorAt > now + window) continue;

            Set<String> councillors = new HashSet<>();
            for (JsonElement e : arr(obj(t, "ranks"), "Councillor")) {
                String u = null;
                if (e.isJsonObject()) {
                    JsonObject c = e.getAsJsonObject();
                    u = str(c, "uuid");
                    if (u == null) u = byName.get(str(c, "name"));
                } else if (e.isJsonPrimitive()) {
                    String s = e.getAsString();
                    u = byName.containsKey(s) ? byName.get(s) : s;
                }
                if (u != null && names.containsKey(u)) councillors.add(u);
            }

            String bestC = null;
            long bestJoin = Long.MAX_VALUE;
            String bestA = null;
            long bestLast = Long.MIN_VALUE;
            for (Map.Entry<String, Long> en : known.entrySet()) {
                String u = en.getKey();
                if (u.equals(mayorId)) continue;
                if (en.getValue() + inactive <= mayorAt) continue;   // inactive by then
                if (councillors.contains(u)) {
                    long j = joined.getOrDefault(u, Long.MAX_VALUE - 1);
                    if (bestC == null || j < bestJoin) {
                        bestC = u;
                        bestJoin = j;
                    }
                }
                if (en.getValue() > bestLast) {
                    bestLast = en.getValue();
                    bestA = u;
                }
            }

            boolean falls = false;
            String outcome;
            if (bestC != null) outcome = "To " + names.get(bestC) + " (councillor)";
            else if (bestA != null) outcome = "To " + names.get(bestA) + " (most active)";
            else {
                falls = true;
                outcome = "Falls";
            }

            JsonObject stats = obj(t, "stats");
            rows.add(new TownRow(
                    str(t, "name"),
                    str(obj(t, "nation"), "name"),
                    str(obj(t, "mayor"), "name"),
                    bool(obj(t, "status"), "isOpen"),
                    falls, outcome, res.size(),
                    (int) num(stats, "numTownBlocks"),
                    num(stats, "balance"),
                    mayorAt));
        }
        rows.sort((a, b) -> Long.compare(a.at(), b.at()));

        progress.accept("Fetching nations...");
        List<String> nationIds = uuids(request("GET", "/nations", null));
        List<JsonObject> nations = postBatches("/nations", nationIds,
                template("name", "uuid", "king", "towns", "stats"), null, "Nations");
        List<NationRow> nationRows = new ArrayList<>();
        for (JsonObject n : nations) {
            List<String> tl = new ArrayList<>();
            for (JsonElement e : arr(n, "towns")) {
                String u = str(e.getAsJsonObject(), "uuid");
                if (u != null && !ruined.contains(u)) tl.add(u);
            }
            if (tl.isEmpty()) continue;
            long at = 0;
            boolean ok = true;
            for (String u : tl) {
                Long f = townFall.get(u);
                if (f == null || f <= now) {   // a town that won't fall keeps the nation alive
                    ok = false;
                    break;
                }
                at = Math.max(at, f);
            }
            if (!ok || at > now + window) continue;
            JsonObject st = obj(n, "stats");
            nationRows.add(new NationRow(
                    str(n, "name"),
                    str(obj(n, "king"), "name"),
                    (int) (num(st, "numTowns") > 0 ? num(st, "numTowns") : tl.size()),
                    (int) num(st, "numResidents"),
                    (int) num(st, "numTownBlocks"),
                    num(st, "balance"),
                    at));
        }
        nationRows.sort((a, b) -> Long.compare(a.at(), b.at()));

        return new Result(rows, nationRows, now, skipped);
    }
}
