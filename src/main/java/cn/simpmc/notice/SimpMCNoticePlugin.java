package cn.simpmc.notice;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class SimpMCNoticePlugin extends JavaPlugin implements TabExecutor, Listener {

    private static final long MAX_INTERVAL_SECONDS = 31_536_000L;
    private static final TagResolver FORMATTING_TAGS = TagResolver.resolver(
            StandardTags.color(),
            StandardTags.decorations(),
            StandardTags.font(),
            StandardTags.gradient(),
            StandardTags.rainbow(),
            StandardTags.transition(),
            StandardTags.pride(),
            StandardTags.shadowColor(),
            StandardTags.reset(),
            StandardTags.newline());
    private static final MiniMessage MINI_MESSAGE =
            MiniMessage.builder().tags(FORMATTING_TAGS).build();
    private static final PlainTextComponentSerializer PLAIN_TEXT_SERIALIZER =
            PlainTextComponentSerializer.plainText();
    private static final Pattern BUNGEE_HEX =
            Pattern.compile("(?i)&x(?:&[0-9a-f]){6}");
    private static final Pattern AMPERSAND_HEX =
            Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final Pattern LEGACY_CODE =
            Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final Pattern TRAILING_STYLE_BOUNDARY = Pattern.compile(
            "(?i)(?:(?:&|§)r|<reset>|</(?:#[0-9a-f]{6}|[a-z_][a-z0-9_-]*(?::[^>]*)?)>)+$");

    private final AtomicLong scheduleGeneration = new AtomicLong();
    private volatile PluginSettings settings;
    private volatile ScheduledTask announcementTask;
    private volatile long joinWindowStartNanos;

    record Interval(long minimumSeconds, long maximumSeconds) {}

    record TimedJoinMessage(String playerName, long withinSeconds, String message) {
        TimedJoinMessage {
            playerName = playerName == null ? "" : playerName.trim();
            message = message == null ? "" : message;
        }
    }

    record PluginSettings(
            List<String> randomPrefixes,
            List<String> randomMessages,
            List<String> fixedPrefixes,
            boolean announcementsEnabled,
            Interval interval,
            String separator,
            String joinMessage,
            List<TimedJoinMessage> timedJoinMessages,
            String notiUsage,
            String noticeUsage,
            String emptyRandomPrefixes,
            String emptyRandomMessages,
            String emptyFixedPrefixes,
            String reloadSuccess,
            String reloadFailure) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = loadSettings();
        joinWindowStartNanos = System.nanoTime();
        registerCommand("noti");
        registerCommand("notice");
        registerCommand("noticereload");
        getServer().getPluginManager().registerEvents(this, this);
        logPoolWarnings(settings);
        restartAnnouncementSchedule();
    }

    @Override
    public void onDisable() {
        scheduleGeneration.incrementAndGet();
        cancelAnnouncementTask();
    }

    private void registerCommand(String name) {
        PluginCommand command = Objects.requireNonNull(
                getCommand(name), "Command '" + name + "' is missing from plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    @Override
    public synchronized boolean onCommand(
            CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "noti" -> broadcastCommand(
                    sender,
                    args,
                    settings.randomPrefixes(),
                    settings.notiUsage(),
                    settings.emptyRandomPrefixes());
            case "notice" -> broadcastCommand(
                    sender,
                    args,
                    settings.fixedPrefixes(),
                    settings.noticeUsage(),
                    settings.emptyFixedPrefixes());
            case "noticereload" -> reloadCommand(sender, args);
            default -> false;
        };
    }

    private boolean broadcastCommand(
            CommandSender sender,
            String[] args,
            List<String> prefixPool,
            String usage,
            String emptyPoolMessage) {
        if (args.length == 0) {
            sender.sendMessage(parseFormattedText(usage));
            return true;
        }
        if (prefixPool.isEmpty()) {
            sender.sendMessage(parseFormattedText(emptyPoolMessage));
            return true;
        }

        // Bukkit 已经按空格拆分参数，这里完整拼回，保留多词消息的内容顺序。
        String content = joinContent(args);
        String prefix = randomElement(prefixPool, ThreadLocalRandom.current());
        broadcast(prefix, content, settings.separator());
        return true;
    }

    static String joinContent(String[] args) {
        return String.join(" ", args);
    }

    private boolean reloadCommand(CommandSender sender, String[] args) {
        if (args.length != 0) {
            sender.sendMessage(parseFormattedText("&c用法: /noticereload"));
            return true;
        }

        try {
            reloadConfig();
            PluginSettings reloaded = loadSettings();
            settings = reloaded;
            logPoolWarnings(reloaded);
            restartAnnouncementSchedule();
            sender.sendMessage(parseFormattedText(reloaded.reloadSuccess()));
        } catch (RuntimeException exception) {
            getLogger().severe("重载配置失败: " + exception.getMessage());
            sender.sendMessage(parseFormattedText(settings.reloadFailure()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        PluginSettings current = settings;
        if (current == null) {
            return;
        }

        Player player = event.getPlayer();
        sendMessageIfPresent(player, current.joinMessage(), "join.message");

        long elapsedNanos = elapsedNanosSince(joinWindowStartNanos, System.nanoTime());
        if (elapsedNanos < 0L) {
            return;
        }
        for (TimedJoinMessage notice : current.timedJoinMessages()) {
            if (matchesTimedJoin(notice, player.getName(), elapsedNanos)) {
                sendMessageIfPresent(
                        player,
                        notice.message(),
                        "join.targeted[" + notice.playerName() + "]");
            }
        }
    }

    private void sendMessageIfPresent(Player player, String message, String settingPath) {
        try {
            parseMessageIfPresent(message).ifPresent(player::sendMessage);
        } catch (RuntimeException exception) {
            getLogger().warning(
                    "无法发送 " + settingPath + " 消息，已跳过：" + exception.getMessage());
        }
    }

    private PluginSettings loadSettings() {
        return new PluginSettings(
                List.copyOf(getConfig().getStringList("random-prefixes")),
                List.copyOf(getConfig().getStringList("random-messages")),
                List.copyOf(getConfig().getStringList("fixed-prefixes")),
                getConfig().getBoolean("announcement.enabled", true),
                normalizeInterval(
                        getConfig().getLong("announcement.interval-seconds.min", 300L),
                        getConfig().getLong("announcement.interval-seconds.max", 600L)),
                getConfig().getString("separator", " "),
                readJoinMessage(),
                readTimedJoinMessages(),
                getConfig().getString("messages.noti-usage", "&c用法: /noti <内容>"),
                getConfig().getString("messages.notice-usage", "&c用法: /notice <内容>"),
                getConfig().getString(
                        "messages.empty-random-prefixes", "&c随机前缀库为空。"),
                getConfig().getString(
                        "messages.empty-random-messages", "&c随机消息库为空。"),
                getConfig().getString(
                        "messages.empty-fixed-prefixes", "&c固定前缀库为空。"),
                getConfig().getString(
                        "messages.reload-success", "&aSimpMC-Notice 配置和内容库已重载。"),
                getConfig().getString(
                        "messages.reload-failure", "&c配置重载失败，请检查控制台。"));
    }

    private String readJoinMessage() {
        String message = getConfig().getString("join.message", "");
        return message == null ? "" : message;
    }

    private List<TimedJoinMessage> readTimedJoinMessages() {
        List<TimedJoinMessage> notices = new ArrayList<>();
        List<Map<?, ?>> entries = getConfig().getMapList("join.targeted");
        int index = 0;
        for (Map<?, ?> entry : entries) {
            index++;
            String playerName = stringValue(entry.get("player"));
            String message = stringValue(entry.get("message"));
            long withinSeconds = longValue(entry.get("within-seconds"), -1L);
            addTimedJoinMessage(notices, playerName, withinSeconds, message, index);
        }
        return List.copyOf(notices);
    }

    private void addTimedJoinMessage(
            List<TimedJoinMessage> destination,
            String playerName,
            long withinSeconds,
            String message,
            int index) {
        String path = "join.targeted[" + index + "]";
        if (isBlankMessage(playerName)) {
            getLogger().warning(path + " 缺少玩家名，已跳过。");
            return;
        }
        if (withinSeconds < 0L) {
            getLogger().warning(path + " 的 within-seconds 无效，已跳过。");
            return;
        }
        destination.add(new TimedJoinMessage(playerName, withinSeconds, message));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // The caller logs and skips invalid values.
            }
        }
        return defaultValue;
    }

    static boolean isBlankMessage(String message) {
        return message == null || expandEscapedNewlines(message).isBlank();
    }

    static Optional<Component> parseMessageIfPresent(String message) {
        if (isBlankMessage(message)) {
            return Optional.empty();
        }
        Component formatted = parseFormattedText(message);
        return PLAIN_TEXT_SERIALIZER.serialize(formatted).isBlank()
                ? Optional.empty()
                : Optional.of(formatted);
    }

    static long elapsedNanosSince(long startNanos, long nowNanos) {
        long elapsed = nowNanos - startNanos;
        return elapsed < 0L ? -1L : elapsed;
    }

    static boolean isWithinJoinWindow(long elapsedNanos, long withinSeconds) {
        if (elapsedNanos < 0L || withinSeconds < 0L) {
            return false;
        }
        long maximumNanos = withinSeconds > Long.MAX_VALUE / 1_000_000_000L
                ? Long.MAX_VALUE
                : withinSeconds * 1_000_000_000L;
        return elapsedNanos <= maximumNanos;
    }

    static boolean matchesTimedJoin(
            TimedJoinMessage notice, String playerName, long elapsedNanos) {
        return notice != null
                && playerName != null
                && notice.playerName().equalsIgnoreCase(playerName)
                && isWithinJoinWindow(elapsedNanos, notice.withinSeconds());
    }

    static Interval normalizeInterval(long first, long second) {
        long safeFirst = Math.clamp(first, 1L, MAX_INTERVAL_SECONDS);
        long safeSecond = Math.clamp(second, 1L, MAX_INTERVAL_SECONDS);
        return new Interval(Math.min(safeFirst, safeSecond), Math.max(safeFirst, safeSecond));
    }

    private void restartAnnouncementSchedule() {
        long generation = scheduleGeneration.incrementAndGet();
        cancelAnnouncementTask();
        if (settings.announcementsEnabled()) {
            scheduleNextAnnouncement(generation);
        }
    }

    private void cancelAnnouncementTask() {
        ScheduledTask task = announcementTask;
        announcementTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    private void scheduleNextAnnouncement(long generation) {
        if (generation != scheduleGeneration.get() || !settings.announcementsEnabled()) {
            return;
        }

        long seconds = randomDelaySeconds(settings.interval(), ThreadLocalRandom.current());
        announcementTask = Bukkit.getGlobalRegionScheduler().runDelayed(
                this,
                task -> {
                    if (generation != scheduleGeneration.get() || !isEnabled()) {
                        return;
                    }
                    sendAutomaticAnnouncement();
                    scheduleNextAnnouncement(generation);
                },
                seconds * 20L);
    }

    static long randomDelaySeconds(Interval interval, RandomGenerator random) {
        if (interval.minimumSeconds() == interval.maximumSeconds()) {
            return interval.minimumSeconds();
        }
        return random.nextLong(interval.minimumSeconds(), interval.maximumSeconds() + 1L);
    }

    private void sendAutomaticAnnouncement() {
        PluginSettings current = settings;
        if (Bukkit.getOnlinePlayers().isEmpty()
                || current.randomPrefixes().isEmpty()
                || current.randomMessages().isEmpty()) {
            return;
        }

        String prefix = randomElement(current.randomPrefixes(), ThreadLocalRandom.current());
        String message = randomElement(current.randomMessages(), ThreadLocalRandom.current());
        broadcast(prefix, message, current.separator());
    }

    private void logPoolWarnings(PluginSettings current) {
        if (current.randomPrefixes().isEmpty()) {
            getLogger().warning("random-prefixes 随机前缀库为空。");
        }
        if (current.randomMessages().isEmpty()) {
            getLogger().warning("random-messages 随机消息库为空。");
        }
        if (current.fixedPrefixes().isEmpty()) {
            getLogger().warning("fixed-prefixes 固定前缀库为空。");
        }
    }

    static <T> T randomElement(List<T> values, RandomGenerator random) {
        return values.get(random.nextInt(values.size()));
    }

    private static void broadcast(String prefix, String content, String separator) {
        Bukkit.broadcast(formatBroadcast(prefix, separator, content));
    }

    static Component formatBroadcast(String prefix, String separator, String content) {
        return parseFormattedText(continuePrefixStyle(prefix) + separator + content);
    }

    static String continuePrefixStyle(String prefix) {
        return TRAILING_STYLE_BOUNDARY.matcher(prefix).replaceFirst("");
    }

    static Component parseFormattedText(String text) {
        return MINI_MESSAGE.deserialize(convertLegacyCodes(expandEscapedNewlines(text)));
    }

    static String expandEscapedNewlines(String text) {
        return text.replace("\\n", "\n");
    }

    static String convertLegacyCodes(String text) {
        String converted = replaceBungeeHex(text);
        converted = replacePattern(converted, AMPERSAND_HEX, match -> "<#" + match.group(1) + ">");
        return replacePattern(converted, LEGACY_CODE, match -> legacyTag(match.group(1).charAt(0)));
    }

    private static String replaceBungeeHex(String text) {
        return replacePattern(text, BUNGEE_HEX, match -> {
            String value = match.group();
            return "<#" + value.charAt(3) + value.charAt(5) + value.charAt(7)
                    + value.charAt(9) + value.charAt(11) + value.charAt(13) + ">";
        });
    }

    private static String replacePattern(
            String text, Pattern pattern, java.util.function.Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.apply(matcher)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String legacyTag(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> throw new IllegalArgumentException("Unsupported legacy color code: " + code);
        };
    }
}
