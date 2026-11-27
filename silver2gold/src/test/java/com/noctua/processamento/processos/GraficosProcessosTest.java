package com.noctua.processamento.processos;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraficosProcessosTest {

    @Test
    void graficosRamEDiscoIncluemMaioresAgressoresPorCaptura() {
        List<ProcessoSilver> processos = List.of(
                processo("2026-11-11 19:29:00", 999.0, "System Idle Process", 0,
                        999.0, 9000L, 9000L),
                processo("2026-11-11 19:29:00", 10.0, "chrome.exe", 10,
                        100.0, 1000L, 300L),
                processo("2026-11-11 19:29:00", 20.0, "java.exe", 11,
                        150.0, 3000L, 500L),
                processo("2026-11-11 19:30:00", 999.0, "System Idle Process", 0,
                        999.0, 9000L, 9000L),
                processo("2026-11-11 19:30:00", 30.0, "Code.exe", 12,
                        80.0, 500L, 700L),
                processo("2026-11-11 19:30:00", 25.0, "node.exe", 13,
                        40.0, 1000L, 1200L));

        JsonObject json = JsonParser.parseString(EtapaSilverParaGold.gerarJsonGold(processos)).getAsJsonObject();
        JsonObject graficos = json.getAsJsonObject("graficos");
        var ramDatasets = graficos.getAsJsonObject("ram").getAsJsonArray("datasets");
        var discoDatasets = graficos.getAsJsonObject("disco").getAsJsonArray("datasets");

        assertEquals(2, ramDatasets.size());
        assertEquals("Memória dos processos (MiB)", ramDatasets.get(0).getAsJsonObject().get("label").getAsString());
        assertEquals(List.of(250.0, 120.0), ramDatasets.get(0).getAsJsonObject().getAsJsonArray("data")
                .asList().stream().map(valor -> valor.getAsDouble()).toList());
        assertEquals("Maior agressor RAM: Code.exe", ramDatasets.get(1).getAsJsonObject()
                .get("label").getAsString());
        assertEquals(List.of(150.0, 80.0), ramDatasets.get(1).getAsJsonObject().getAsJsonArray("data")
                .asList().stream().map(valor -> valor.getAsDouble()).toList());

        assertEquals(4, discoDatasets.size());
        assertEquals(List.of(4000.0, 1500.0), discoDatasets.get(0).getAsJsonObject()
                .getAsJsonArray("data").asList().stream().map(valor -> valor.getAsDouble()).toList());
        assertEquals(List.of(800.0, 1900.0), discoDatasets.get(1).getAsJsonObject()
                .getAsJsonArray("data").asList().stream().map(valor -> valor.getAsDouble()).toList());
        assertEquals("Maior leitor: node.exe (Bytes lidos)", discoDatasets.get(2).getAsJsonObject()
                .get("label").getAsString());
        assertEquals(List.of(3000.0, 1000.0), discoDatasets.get(2).getAsJsonObject()
                .getAsJsonArray("data").asList().stream().map(valor -> valor.getAsDouble()).toList());
        assertEquals("Maior escritor: node.exe (Bytes escritos)", discoDatasets.get(3).getAsJsonObject()
                .get("label").getAsString());
        assertEquals(List.of(500.0, 1200.0), discoDatasets.get(3).getAsJsonObject()
                .getAsJsonArray("data").asList().stream().map(valor -> valor.getAsDouble()).toList());
    }

    @Test
    void graficoCpuIncluiMaiorAgressorEmCadaCapturaEUsaPrimeiroEmEmpate() {
        List<ProcessoSilver> processos = List.of(
                processo("2026-11-11 19:29:00", 999.0, "System Idle Process", 0, 1.0, 0L, 0L),
                processoComCpuGlobal("2026-11-11 19:29:00", 20.0, "chrome.exe", 10,
                        42.0, 40.0, 8),
                processoComCpuGlobal("2026-11-11 19:29:00", 25.0, "java.exe", 11,
                        42.0, 40.0, 8),
                processo("2026-11-11 19:30:00", 999.0, "System Idle Process", 0, 1.0, 0L, 0L),
                processoComCpuGlobal("2026-11-11 19:30:00", 30.0, "Code.exe", 12,
                        42.0, 55.0, 8),
                processoComCpuGlobal("2026-11-11 19:30:00", 30.0, "node.exe", 13,
                        42.0, 55.0, 8));

        JsonObject json = JsonParser.parseString(EtapaSilverParaGold.gerarJsonGold(processos)).getAsJsonObject();
        var datasets = json.getAsJsonObject("graficos").getAsJsonObject("cpu").getAsJsonArray("datasets");
        JsonObject cpuTotal = datasets.get(0).getAsJsonObject();
        JsonObject maiorAgressor = datasets.get(1).getAsJsonObject();

        assertEquals("CPU da máquina (%)", cpuTotal.get("label").getAsString());
        assertEquals(List.of(40.0, 55.0), cpuTotal.getAsJsonArray("data").asList().stream()
                .map(valor -> valor.getAsDouble()).toList());
        assertEquals("Maior agressor: Code.exe", maiorAgressor.get("label").getAsString());
        assertEquals(List.of(3.13, 3.75), maiorAgressor.getAsJsonArray("data").asList().stream()
                .map(valor -> valor.getAsDouble()).toList());
    }

    @Test
    void processosOciososSemAgressorNaoQuebramGeracaoDoGold() {
        List<ProcessoSilver> processos = List.of(
                processo("2026-11-11 19:30:00", 999.0, "System Idle Process", 0, 1.0, 0L, 0L));

        JsonObject json = JsonParser.parseString(EtapaSilverParaGold.gerarJsonGold(processos)).getAsJsonObject();

        assertTrue(json.getAsJsonArray("processos").isEmpty());
        assertTrue(json.getAsJsonObject("graficos").entrySet().isEmpty());
        assertTrue(json.getAsJsonObject("grafico").getAsJsonArray("datasets").isEmpty());
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
