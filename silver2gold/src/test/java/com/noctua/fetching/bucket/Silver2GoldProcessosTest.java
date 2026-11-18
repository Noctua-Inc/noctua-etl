package com.noctua.fetching.bucket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.noctua.model.ProcessoSilver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Silver2GoldProcessosTest {

    @TempDir
    Path temporario;

    @Test
    void leCsvDaSilverEEscreveLatestJson() throws Exception {
        Path silver = Files.createDirectories(temporario.resolve("silver"));
        Path gold = Files.createDirectories(temporario.resolve("gold"));
        String nome = "MF_processos_2026-11-11_19-30_silver.csv";
        String linha = "cap1,MF,2026-11-11 19:30:00,123,chrome,12.5,12.5,1024,";

        Files.write(silver.resolve(nome),
                (ProcessoSilver.CABECALHO + "\n" + linha + "\n").getBytes(StandardCharsets.UTF_8));

        new Silver2GoldProcessos().criarJson(silver, gold, nome, "latest.json");

        Path destino = gold.resolve("latest.json");
        assertTrue(Files.exists(destino));
        JsonObject json = JsonParser.parseString(Files.readString(destino)).getAsJsonObject();
        assertEquals("MF", json.get("hostname").getAsString());
        assertEquals(1, json.getAsJsonArray("processos").size());
    }

    @Test
    void comparaChaveGold() {
        String caminho = "sptech/02-silver/MF/MF_processos_2026-11-11_19-30_silver.csv";

        assertEquals("sptech/03-gold/MF/processos/latest.json",
                new Silver2GoldProcessos().caminhoGold(caminho, "MF"));
    }
}
