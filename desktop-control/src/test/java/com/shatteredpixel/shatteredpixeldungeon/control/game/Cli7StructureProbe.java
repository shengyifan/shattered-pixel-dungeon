package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline public-value replay through the production codec. No game/profile access. */
public final class Cli7StructureProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "Usage: Cli7StructureProbe <expanded-public.jsonl> <output-directory>");
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        Map<String, BufferedWriter> writers = new LinkedHashMap<>();
        int frames = 0;
        try {
            for (String mode : Arrays.asList("sharing", "templates", "combined"))
                writers.put(mode, Files.newBufferedWriter(output.resolve(mode + ".jsonl"), StandardCharsets.UTF_8));
            try (BufferedReader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null;) {
                    Map<String, Object> input = JsonCodec.decode(line);
                    String original = JsonCodec.encode(input);
                    Object expected = CompactStructures.expandPure(input);
                    for (Map.Entry<String, BufferedWriter> entry : writers.entrySet()) {
                        Map<String, Object> candidate = JsonCodec.decode(original);
                        CompactStructures.compact(candidate, !entry.getKey().equals("templates"), !entry.getKey().equals("sharing"));
                        Object restored = CompactStructures.expandPure(candidate);
                        if (!expected.equals(restored)) throw new AssertionError(
                                "Lossless comparison failed: frame " + frames + " id " + input.get("id") + " mode " + entry.getKey());
                        entry.getValue().write(JsonCodec.encode(candidate));
                        entry.getValue().newLine();
                    }
                    if (!original.equals(JsonCodec.encode(input))) throw new AssertionError("Input was mutated");
                    frames++;
                }
            }
        } finally {
            for (BufferedWriter writer : writers.values()) writer.close();
        }
        System.out.println("Verified " + frames + " public frames across 3 production structural modes");
    }
}
