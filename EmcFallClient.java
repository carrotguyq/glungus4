package com.emcfall;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Client command: /emcfall [options]. Run /emcfall help in game. */
public class EmcFallClient implements ClientModInitializer {
    private static volatile Finder.Result cache;
    private static final AtomicBoolean scanning = new AtomicBoolean(false);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final long MAX_AGE_MS = 60 * 60_000L;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("emcfall")
                        .executes(ctx -> run(ctx.getSource(), ""))
                        .then(ClientCommandManager.argument("options", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "options"))))));
    }

    // ---- options ----

    static final class Opts {
        int hours = 24;
        int top = 10;
        long gold = -1;
        int chunks = -1;
        int residents = -1;
        boolean open, closed, falls, transfer, nations, refresh, help;

        static Opts parse(String raw) {
            Opts o = new Opts();
            Pattern time = Pattern.compile("(\\d+)([hd]?)");
            for (String tok : raw.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
                if (tok.isEmpty()) continue;
                Matcher m = time.matcher(tok);
                if (m.matches()) {
                    long v = Long.parseLong(m.group(1)) * ("d".equals(m.group(2)) ? 24 : 1);
                    o.hours = (int) Math.max(1, Math.min(336, v));
                } else if (tok.contains(":")) {
                    String[] kv = tok.split(":", 2);
                    long v;
                    try {
                        v = Long.parseLong(kv[1]);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Bad number in '" + tok + "'");
                    }
                    switch (kv[0]) {
                        case "gold" -> o.gold = v;
                        case "chunks" -> o.chunks = (int) v;
                        case "res" -> o.residents = (int) v;
                        case "top" -> o.top = (int) Math.max(1, Math.min(40, v));
                        default -> throw new IllegalArgumentException("Unknown option '" + tok + "'");
                    }
                } else {
                    switch (tok) {
                        case "open" -> o.open = true;
                        case "closed" -> o.closed = true;
                        case "falls", "fall" -> o.falls = true;
                        case "transfer", "mayor" -> o.transfer = true;
                        case "nations", "nation" -> o.nations = true;
                        case "refresh" -> o.refresh = true;
                        case "help" -> o.help = true;
                        default -> throw new IllegalArgumentException("Unknown option '" + tok + "'");
                    }
                }
            }
            return o;
        }
    }

    // ---- command ----

    private static Component color(String s, ChatFormatting f) {
        return Component.literal(s).withStyle(f);
    }

    private static int run(FabricClientCommandSource src, String raw) {
        Minecraft mc = Minecraft.getInstance();
        Consumer<Component> say = c -> mc.execute(() -> src.sendFeedback(c));

        Opts o;
        try {
            o = Opts.parse(raw);
        } catch (IllegalArgumentException e) {
            src.sendFeedback(color(e.getMessage() + ". Try /emcfall help", ChatFormatting.RED));
            return 0;
        }
        if (o.help) {
            for (String line : HELP) src.sendFeedback(color(line, ChatFormatting.YELLOW));
            return 1;
        }

        Finder.Result r = cache;
        boolean stale = r == null || System.currentTimeMillis() - r.generated() > MAX_AGE_MS;
        if (o.refresh || stale) {
            if (!scanning.compareAndSet(false, true)) {
                src.sendFeedback(color("A scan is already running, results will appear when it finishes.",
                        ChatFormatting.GRAY));
                return 1;
            }
            src.sendFeedback(color("Scanning EarthMC, this takes a few minutes. Results will appear here.",
                    ChatFormatting.GRAY));
            Thread th = new Thread(() -> {
                try {
                    Finder.Result res = Finder.scan(336, m -> say.accept(color(m, ChatFormatting.DARK_GRAY)));
                    cache = res;
                    show(res, o, say);
                } catch (Exception e) {
                    say.accept(color("Scan failed: " + e, ChatFormatting.RED));
                } finally {
                    scanning.set(false);
                }
            }, "emcfall-scan");
            th.setDaemon(true);
            th.start();
            return 1;
        }
        show(r, o, say);
        return 1;
    }

    private static final String[] HELP = {
            "/emcfall [options]  (first use scans EarthMC, takes a few minutes)",
            "  12 / 12h / 3d   time window (max 14 days, default 24h)",
            "  open / closed   only open or closed towns",
            "  falls / mayor   only towns that fall / only mayor changes",
            "  nations         show inactive nations instead of towns",
            "  gold:500  chunks:20  res:3   minimum gold / chunks / residents",
            "  top:20          how many lines to show (default 10, max 40)",
            "  refresh         rescan now (data older than 1h rescans automatically)",
            "Example: /emcfall 12h open falls gold:500"
    };

    private static String left(long at) {
        double h = (at - System.currentTimeMillis()) / 3_600_000.0;
        if (h <= 0) return "any moment";
        if (h < 48) return String.format(Locale.ENGLISH, "%.1fh", h);
        return (long) (h / 24) + "d " + Math.round(h % 24) + "h";
    }

    private static void show(Finder.Result r, Opts o, Consumer<Component> say) {
        long now = System.currentTimeMillis();
        long limit = now + o.hours * Finder.HOUR;
        long ageMin = (now - r.generated()) / 60_000L;
        List<Component> out = new ArrayList<>();

        if (o.nations) {
            List<Finder.NationRow> rows = new ArrayList<>();
            for (Finder.NationRow n : r.nations()) {
                if (n.at() > limit) continue;
                if (o.gold >= 0 && n.balance() < o.gold) continue;
                if (o.chunks >= 0 && n.chunks() < o.chunks) continue;
                if (o.residents >= 0 && n.residents() < o.residents) continue;
                rows.add(n);
            }
            out.add(color("Nations falling within " + o.hours + "h: " + rows.size()
                    + " (data " + ageMin + " min old)", ChatFormatting.GOLD));
            for (Finder.NationRow n : rows.subList(0, Math.min(rows.size(), o.top))) {
                out.add(color(n.name(), ChatFormatting.AQUA).copy().append(color(
                        " king " + n.king() + " | " + n.towns() + " towns, " + n.residents() + " res, "
                                + n.chunks() + "ch, " + n.balance() + "g | " + left(n.at())
                                + " (" + DATE.format(java.time.Instant.ofEpochMilli(n.at())) + " UTC)",
                        ChatFormatting.WHITE)));
            }
        } else {
            List<Finder.TownRow> rows = new ArrayList<>();
            for (Finder.TownRow t : r.towns()) {
                if (t.at() > limit) continue;
                if (o.open && !t.open()) continue;
                if (o.closed && t.open()) continue;
                if (o.falls && !t.falls()) continue;
                if (o.transfer && t.falls()) continue;
                if (o.gold >= 0 && t.balance() < o.gold) continue;
                if (o.chunks >= 0 && t.chunks() < o.chunks) continue;
                if (o.residents >= 0 && t.residents() < o.residents) continue;
                rows.add(t);
            }
            out.add(color("Towns changing mayor or falling within " + o.hours + "h: " + rows.size()
                    + " (data " + ageMin + " min old)", ChatFormatting.GOLD));
            for (Finder.TownRow t : rows.subList(0, Math.min(rows.size(), o.top))) {
                out.add(color(t.name(), t.open() ? ChatFormatting.GREEN : ChatFormatting.GRAY).copy().append(color(
                        (t.open() ? " [OPEN] " : " ") + t.outcome() + " | " + t.balance() + "g "
                                + t.chunks() + "ch " + t.residents() + "res | " + left(t.at())
                                + " (" + DATE.format(java.time.Instant.ofEpochMilli(t.at())) + " UTC)",
                        ChatFormatting.WHITE)));
            }
        }
        if (out.size() == 1) out.add(color("Nothing matches. Try a longer window or fewer filters.", ChatFormatting.GRAY));
        for (Component c : out) say.accept(c);
    }
}
