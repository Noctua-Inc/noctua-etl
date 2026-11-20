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

        assertEquals(1, json.getAsJsonObject("grafico").getAsJsonArray("datasets").size());
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
        JsonObject json = JsonParser.parseString(EtapaSilverParaGold.gerarJsonGold(List.of())).getAsJsonObject();

        assertTrue(json.has("kpis"));
        assertTrue(json.getAsJsonArray("processos").isEmpty());
        assertEquals(0, json.getAsJsonObject("kpis").get("processos_capturados").getAsInt());
    }

    @Test
    void removeProcessoOciosoOrdenaRankingsEPreencheGraficos() {
        List<ProcessoSilver> processos = List.of(
                processo("2026-11-11 19:30:00", 618.3, "System Idle Process", 0, 1.0, 0L, 0L),
                processo("2026-11-11 19:30:00", 99.0, "SystemIdle_Process", 999, 1.0, 0L, 0L),
                processo("2026-11-11 19:30:00", 10.0, "chrome", 10, 100.0, 100L, 200L),
                processo("2026-11-11 19:30:00", 66.0, "python.exe", 20, 250.0, 300L, 400L),
                processo("2026-11-11 19:29:00", 5.0, "chrome", 10, 90.0, 50L, 50L));

        JsonObject json = JsonParser.parseString(EtapaSilverParaGold.gerarJsonGold(processos)).getAsJsonObject();
        JsonObject kpis = json.getAsJsonObject("kpis");

        assertEquals(76.0, kpis.get("cpu_percentual").getAsDouble());
        assertEquals(2, kpis.get("processos_capturados").getAsInt());
        assertEquals("python.exe", json.getAsJsonArray("processos")
                .get(0).getAsJsonObject().get("Nome_Processo").getAsString());
        assertEquals("python.exe", json.getAsJsonObject("rankings")
                .getAsJsonArray("ram").get(0).getAsJsonObject().get("Nome_Processo").getAsString());
        assertEquals("python.exe", json.getAsJsonObject("rankings")
                .getAsJsonArray("disco").get(0).getAsJsonObject().get("Nome_Processo").getAsString());
        assertEquals("Atencao", json.getAsJsonArray("processos")
                .get(0).getAsJsonObject().get("status").getAsString());
        assertEquals(1, json.getAsJsonObject("grafico").getAsJsonArray("datasets").size());
        assertEquals(2, json.getAsJsonObject("graficos").getAsJsonObject("disco")
                .getAsJsonArray("datasets").size());
    }

    @Test
    void naoSomaCpuDosProcessosQuandoCpuGlobalNaoEstaDisponivel() {
        List<ProcessoSilver> processos = List.of(
                processo("2026-11-11 19:30:00", 66.0, "python.exe", 20,
                        250.0, null, null),
                processo("2026-11-11 19:30:00", 80.0, "chrome", 21,
                        250.0, null, null));

        JsonObject json = JsonParser.parseString(EtapaSilverParaGold.gerarJsonGold(processos)).getAsJsonObject();

        assertEquals(0.0, json.getAsJsonObject("kpis").get("cpu_percentual").getAsDouble());
        assertEquals(0.0, json.getAsJsonObject("kpis").get("cpu_pico_percentual").getAsDouble());
    }

    @Test
    void usaCpuGlobalDaMaquinaEmVezDaSomaDosProcessos() {
        List<ProcessoSilver> processos = List.of(
                processoComCpuGlobal("2026-11-11 19:30:00", 66.0, "python.exe", 20,
                        250.0, 29.5, 8));

        JsonObject json = JsonParser.parseString(EtapaSilverParaGold.gerarJsonGold(processos)).getAsJsonObject();

        assertEquals(29.5, json.getAsJsonObject("kpis").get("cpu_percentual").getAsDouble());
        assertEquals(29.5, json.getAsJsonObject("grafico").getAsJsonArray("datasets")
                .get(0).getAsJsonObject().getAsJsonArray("data").get(0).getAsDouble());
        assertEquals(8.25, json.getAsJsonArray("processos").get(0).getAsJsonObject()
                .get("CPU_percentual_maquina").getAsDouble());
    }

    private ProcessoSilver processo(String timestamp, double cpu, String nome) {
        return processo(timestamp, cpu, nome, 123, 42.0, null, null);
    }

    private ProcessoSilver processo(String timestamp, double cpu, String nome, int pid,
                                    double memoria, Long lidos, Long escritos) {
        return new ProcessoSilver("cap1", "MF", timestamp, pid, nome,
                cpu, memoria, lidos, escritos, null, null);
    }

    private ProcessoSilver processoComCpuGlobal(String timestamp, double cpu, String nome, int pid,
                                                double memoria, double cpuGlobal, int cpus) {
        return new ProcessoSilver("cap1", "MF", timestamp, pid, nome,
                cpu, memoria, 0L, 0L, cpuGlobal, cpus);
    }
}
