package cn.simpmc.notice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

class SimpMCNoticePluginTest {

    private static final LegacyComponentSerializer LEGACY_SECTIONS =
            LegacyComponentSerializer.legacySection();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    @Test
    void joinsEveryCommandArgumentWithSpaces() {
        assertEquals(
                "这是一段 包含 多个空格分隔参数 的消息",
                SimpMCNoticePlugin.joinContent(
                        new String[] {"这是一段", "包含", "多个空格分隔参数", "的消息"}));
    }

    @Test
    void prefixColorContinuesIntoContentDespiteTrailingReset() {
        Component result = SimpMCNoticePlugin.formatBroadcast(
                "&6[公告]&r", " ", "服务器将在五分钟后重启");

        assertEquals("§6[公告] 服务器将在五分钟后重启", LEGACY_SECTIONS.serialize(result));
    }

    @Test
    void closedMiniMessagePrefixColorContinuesIntoContent() {
        Component result = SimpMCNoticePlugin.formatBroadcast(
                "<red>[警告]</red>", " ", "请立即回城");

        assertEquals("§c[警告] 请立即回城", LEGACY_SECTIONS.serialize(result));
    }

    @Test
    void contentColorCanOverrideContinuedPrefixColor() {
        Component result = SimpMCNoticePlugin.formatBroadcast(
                "&c[警告]&r", " ", "&e请立即回城");

        assertEquals("§c[警告] §e请立即回城", LEGACY_SECTIONS.serialize(result));
    }

    @Test
    void supportsTraditionalMiniMessageAndHexFormattingTogether() {
        assertEquals(
                MINI_MESSAGE.deserialize("<green>绿色 <bold>粗体</bold> <#12ABEF>十六进制"),
                SimpMCNoticePlugin.parseFormattedText(
                        "&a绿色 <bold>粗体</bold> &#12ABEF十六进制"));
    }

    @Test
    void supportsBungeeStyleHexColors() {
        assertEquals(
                MINI_MESSAGE.deserialize("<#12ABEF>十六进制"),
                SimpMCNoticePlugin.parseFormattedText("&x&1&2&A&B&E&F十六进制"));
    }

    @Test
    void supportsMiniMessageGradients() {
        String input = "<gradient:#ff0000:#0000ff>渐变文字</gradient>";

        assertEquals(
                MINI_MESSAGE.serialize(MINI_MESSAGE.deserialize(input)),
                MINI_MESSAGE.serialize(SimpMCNoticePlugin.parseFormattedText(input)));
    }

    @Test
    void supportsEscapedAndMiniMessageNewlines() {
        Component result = SimpMCNoticePlugin.parseFormattedText(
                "第一行\\n第二行<newline>第三行");

        assertEquals("第一行\n第二行\n第三行", LEGACY_SECTIONS.serialize(result));
    }

    @Test
    void treatsMissingAndWhitespaceJoinMessagesAsEmpty() {
        assertTrue(SimpMCNoticePlugin.isBlankMessage(null));
        assertTrue(SimpMCNoticePlugin.isBlankMessage(""));
        assertTrue(SimpMCNoticePlugin.isBlankMessage(" \t\n "));
        assertTrue(SimpMCNoticePlugin.isBlankMessage("\\n\\n"));
        assertFalse(SimpMCNoticePlugin.isBlankMessage("&a欢迎"));
        assertTrue(SimpMCNoticePlugin.parseMessageIfPresent("&a").isEmpty());
        assertTrue(SimpMCNoticePlugin.parseMessageIfPresent("<newline>").isEmpty());
        assertTrue(SimpMCNoticePlugin.parseMessageIfPresent("&a欢迎").isPresent());
    }

    @Test
    void yamlEscapedJoinMessageKeepsColorsAndNewlines() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("join:\n  message: \"&a第一行\\\\n&b第二行\"\n");

        Component result = SimpMCNoticePlugin.parseMessageIfPresent(
                        config.getString("join.message"))
                .orElseThrow();
        assertEquals("§a第一行\n§b第二行", LEGACY_SECTIONS.serialize(result));
    }

    @Test
    void doesNotEnableInteractiveMiniMessageTags() {
        Component result = SimpMCNoticePlugin.parseFormattedText(
                "<click:run_command:'/op Minecraft0122'><red>不要执行命令</red></click>");

        assertFalse(GsonComponentSerializer.gson().serialize(result).contains("clickEvent"));
    }

    @Test
    void normalizesReversedAndInvalidIntervals() {
        assertEquals(
                new SimpMCNoticePlugin.Interval(30, 90),
                SimpMCNoticePlugin.normalizeInterval(90, 30));
        assertEquals(
                new SimpMCNoticePlugin.Interval(1, 30),
                SimpMCNoticePlugin.normalizeInterval(-10, 30));
    }

    @Test
    void randomDelayAlwaysStaysInsideConfiguredRange() {
        SimpMCNoticePlugin.Interval interval = new SimpMCNoticePlugin.Interval(10, 20);
        for (int seed = 0; seed < 100; seed++) {
            long delay = SimpMCNoticePlugin.randomDelaySeconds(interval, new Random(seed));
            assertTrue(delay >= 10 && delay <= 20);
        }
    }

    @Test
    void randomElementAlwaysComesFromGivenPool() {
        List<String> pool = List.of("一", "二", "三");
        for (int seed = 0; seed < 100; seed++) {
            assertTrue(pool.contains(SimpMCNoticePlugin.randomElement(pool, new Random(seed))));
        }
    }

    @Test
    void targetedJoinMatchesNameIgnoringCaseAndIncludesWindowBoundary() {
        SimpMCNoticePlugin.TimedJoinMessage notice =
                new SimpMCNoticePlugin.TimedJoinMessage("Minecraft0122", 30, "欢迎");
        long boundary = 30L * 1_000_000_000L;

        assertTrue(SimpMCNoticePlugin.matchesTimedJoin(notice, "minecraft0122", boundary));
        assertFalse(SimpMCNoticePlugin.matchesTimedJoin(notice, "another-player", boundary));
        assertFalse(SimpMCNoticePlugin.matchesTimedJoin(notice, "Minecraft0122", boundary + 1));
        assertFalse(SimpMCNoticePlugin.matchesTimedJoin(notice, "Minecraft0122", -1));
    }

    @Test
    void targetedJoinSupportsImmediateAndLongWindows() {
        SimpMCNoticePlugin.TimedJoinMessage immediate =
                new SimpMCNoticePlugin.TimedJoinMessage("player", 0, "消息");
        SimpMCNoticePlugin.TimedJoinMessage longWindow =
                new SimpMCNoticePlugin.TimedJoinMessage("player", Long.MAX_VALUE, "消息");

        assertTrue(SimpMCNoticePlugin.matchesTimedJoin(immediate, "PLAYER", 0));
        assertTrue(SimpMCNoticePlugin.matchesTimedJoin(longWindow, "player", Long.MAX_VALUE));
    }

    @Test
    void defaultConfigContainsAllThreePools() {
        try (InputStream input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));

            assertFalse(config.getStringList("random-prefixes").isEmpty());
            assertFalse(config.getStringList("random-messages").isEmpty());
            assertFalse(config.getStringList("fixed-prefixes").isEmpty());
            assertEquals("", config.getString("join.message"));
            assertTrue(config.getMapList("join.targeted").isEmpty());
            assertTrue(config.getLong("announcement.interval-seconds.min") > 0);
            assertTrue(config.getLong("announcement.interval-seconds.max") > 0);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void pluginMetadataContainsCorrectNameAndCommands() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(input);
            PluginDescriptionFile description = new PluginDescriptionFile(input);

            assertEquals("SimpMC-Notice", description.getName());
            assertEquals("2.0.1", description.getVersion());
            assertEquals("A Plugin For SimpMC Network.", description.getDescription());
            assertEquals(List.of("Minecraft0122", "SimpMC", "GPT-5.6"), description.getAuthors());
            assertEquals("26.1.2", description.getAPIVersion());
            assertTrue(description.isFoliaSupported());
            assertEquals(
                    List.of("noti", "notice", "noticereload"),
                    description.getCommands().keySet().stream().toList());
            assertEquals(
                    List.of("noticrreload"),
                    description.getCommands().get("noticereload").get("aliases"));
            assertTrue(String.valueOf(
                            description.getCommands().get("noticereload").get("description"))
                    .contains("玩家加入消息"));
        }
    }
}
