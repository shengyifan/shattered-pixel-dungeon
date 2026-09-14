package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.game.CompactProtocol;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Offline projection of explicitly supplied public observations, never a save/profile reader. */
public final class Cli4TokenSamples {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if(args.length != 2) throw new IllegalArgumentException("Usage: Cli4TokenSamples <public-input.jsonl> <projected.jsonl>");
        int count = 0;
        try(BufferedReader input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
            BufferedWriter output = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            for(String line; (line = input.readLine()) != null;) {
                Map<String,Object> row = JsonCodec.decode(line);
                Map<String,Object> projected;
                if(row.get("err") != null) {
                    projected = CompactProtocol.failure((String)row.get("id"), (String)row.get("s"), (String)row.get("err"));
                } else {
                    Map<String,Object> canonical = (Map<String,Object>)row.get("canonical");
                    if(canonical.containsKey("cli_version")) {
                        canonical.put("cli_version", "CLI.4.0.0");
                        canonical.put("protocol_version", 4);
                        canonical.put("audit_schema_version", 7);
                        canonical.put("schema", CompactProtocol.info());
                    }
                    projected = CompactProtocol.success((String)row.get("id"), (String)row.get("s"),
                            (String)row.get("st"), canonical, Boolean.TRUE.equals(row.get("live")), false);
                }
                output.write(JsonCodec.encode(projected)); output.newLine(); count++;
            }
        }
        System.out.println("Projected " + count + " previously recorded public replies with the production CLI 4 encoder");
    }
}
