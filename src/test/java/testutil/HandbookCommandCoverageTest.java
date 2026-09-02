package testutil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handbook guarantee: every registered command name must exist in the checked-in handbook data.
 *
 * <p>Purely source- and file-based on purpose. Instantiating the command registry would load
 * command classes and WZ-backed factories in this JVM; that has already been observed to perturb
 * other test classes which rely on the order in which static WZ state is initialised.
 */
class HandbookCommandCoverageTest {
    private static final Path SOURCE = Path.of("src/main/java/client/command/CommandsExecutor.java");
    private static final Path DATA = Path.of("docs/handbook/commands.json");
    private static final Pattern CALL = Pattern.compile(
            "addCommand\\((?:new String\\[\\]\\{([^}]*)}\"?)|\"([^\"]+)\"\\)");
    private static final Pattern NAMES = Pattern.compile("addCommand\\((?:new String\\[]\\{([^}]*)}|\"([^\"]+)\")");

    @Test
    void registryAndHandbookDataCoverTheSameNames() throws IOException {
        Map<String, Integer> registry = registryRanks();
        Map<String, Integer> documented = documentedRanks();

        List<String> missing = new ArrayList<>();
        for (String name : registry.keySet()) {
            if (!documented.containsKey(name)) {
                missing.add(name);
            }
        }
        List<String> stale = new ArrayList<>();
        for (String name : documented.keySet()) {
            if (!registry.containsKey(name)) {
                stale.add(name);
            }
        }
        List<String> rankMismatch = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : registry.entrySet()) {
            Integer documentRank = documented.get(entry.getKey());
            if (documentRank != null && !documentRank.equals(entry.getValue())) {
                rankMismatch.add(entry.getKey() + " registry=" + entry.getValue() + " doc=" + documentRank);
            }
        }

        assertTrue(missing.isEmpty(), "handbook data is missing commands: " + missing);
        assertTrue(stale.isEmpty(), "handbook data documents unknown commands: " + stale);
        assertTrue(rankMismatch.isEmpty(), "handbook ranks differ from the registry: " + rankMismatch);
    }

    @Test
    void gm2UtilitiesAreDocumentedWithRankTwo() throws IOException {
        Map<String, Integer> documented = documentedRanks();
        assertEquals(2, documented.get("gachalist"));
        assertEquals(2, documented.get("loot"));
        assertEquals(2, documented.get("mobskill"));
    }

    @Test
    void handbookSourceContainsNoRealEndpoint() throws IOException {
        String html = Files.readString(Path.of("docs/handbook/handbook.html"), StandardCharsets.UTF_8);
        Matcher ip = Pattern.compile("\\b(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)"
                + "(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b").matcher(html);
        List<String> publicIps = new ArrayList<>();
        while (ip.find()) {
            if (!ip.group().startsWith("127.")) {
                publicIps.add(ip.group());
            }
        }
        assertTrue(publicIps.isEmpty(), "handbook must not contain public IPv4 addresses: " + publicIps);
        assertTrue(html.contains("[SERVER-IP]"), "handbook must keep a server endpoint placeholder");
    }

    private static Map<String, Integer> registryRanks() throws IOException {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        Matcher matcher = NAMES.matcher(source);
        while (matcher.find()) {
            String group = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            int rankIndex = source.indexOf("addCommand", matcher.start());
            int end = source.indexOf(';', matcher.start());
            String call = source.substring(rankIndex, end);
            Matcher rankMatcher = Pattern.compile(",\\s*(\\d+)\\s*,").matcher(call);
            int rank = rankMatcher.find() ? Integer.parseInt(rankMatcher.group(1)) : 0;
            for (String part : group.split(",")) {
                String name = part.trim().replace("\"", "");
                if (!name.isEmpty()) {
                    ranks.putIfAbsent(name, rank);
                }
            }
        }
        return ranks;
    }

    private static Map<String, Integer> documentedRanks() throws IOException {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        String json;
        try (InputStream in = Files.newInputStream(DATA)) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher entry = Pattern.compile("\\{\\s*\"name\": \"([^\"]+)\",\\s*\"rank\": (\\d+)").matcher(json);
        while (entry.find()) {
            ranks.putIfAbsent(entry.group(1), Integer.parseInt(entry.group(2)));
        }
        return ranks;
    }
}
