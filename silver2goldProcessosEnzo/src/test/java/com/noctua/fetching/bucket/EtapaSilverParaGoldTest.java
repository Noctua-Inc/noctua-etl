package com.noctua.fetching.bucket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.noctua.model.ProcessoSilver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EtapaSilverParaGoldTest {

    @Test
    void geraSchemaDaDashComKpisDaUltimaCaptura() {
        List<ProcessoSilver> processos = new ArrayList<>();
        processos.add(processo("2026-11-11 19:30:00", 10.0, "chrome"));
        processos.add(processo("2026-11-11 19:30:00", 20.5, "code"));
        processos.add(processo("2026-11-11 19:31:00", 5.0, "chrome"));

        JsonObject json = JsonParser.parseString(EtapaSilverParaGold.gerarJsonGold(processos)).getAsJsonObject();

        assertEquals("MF", json.get("hostname").getAsString());
        assertEquals("2026-11-11 19:30:00", json.getAsJsonObject("periodo").get("inicio").getAsString());
        assertEquals("2026-11-11 19:31:00", json.getAsJsonObject("periodo").get("fim").getAsString());

        JsonObject kpis = json.getAsJsonObject("kpis");
        assertEquals(5.0, kpis.get("cpu_percentual").getAsDouble());
        assertEquals(1, kpis.get("processos_capturados").getAsInt());
        assertEquals("2026-11-11 19:31:00", kpis.get("ultima_captura").getAsString());

        assertEquals(1, json.getAsJsonArray("processos").size());
        JsonObject ultimo = json.getAsJsonArray("processos").get(0).getAsJsonObject();
        assertEquals(123, ultimo.get("PID").getAsInt());
        assertEquals("chrome", ultimo.get("Nome_Processo").getAsString());
        assertEquals(0, ultimo.get("Bytes_Lidos").getAsInt());
        assertEquals(0, ultimo.get("Bytes_Escritos").getAsInt());
        assertEquals("Normal", ultimo.get("status").getAsString());

        assertTrue(json.getAsJsonObject("grafico").getAsJsonArray("datasets").isEmpty());
    }

    @Test
    void escapaAspasNoNomeDoProcesso() {
        List<ProcessoSilver> processos = List.of(
                processo("2026-11-11 19:31:00", 1.0, "app \"teste\""));

        String json = EtapaSilverParaGold.gerarJsonGold(processos);

        assertTrue(json.contains("app \\\"teste\\\""));
        assertEquals("app \"teste\"", JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonArray("processos").get(0).getAsJsonObject()
                .get("Nome_Processo").getAsString());
    }

    @Test
    void listaVaziaGeraObjetoVazio() {
        assertEquals("{}", EtapaSilverParaGold.gerarJsonGold(List.of()));
    }

    private ProcessoSilver processo(String timestamp, double cpu, String nome) {
        return new ProcessoSilver("cap1", "MF", timestamp, 123, nome,
                cpu, 42.0, null, null);
    }
}
