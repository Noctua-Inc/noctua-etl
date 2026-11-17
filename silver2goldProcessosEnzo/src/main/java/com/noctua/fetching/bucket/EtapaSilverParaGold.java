package com.noctua.fetching.bucket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.noctua.model.ProcessoSilver;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class EtapaSilverParaGold {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Recebe a lista completa da Silver e gera o JSON da Gold no formato
     * esperado pela Dash-Processos. O Gson cuida do escape de strings.
     */
    public static String gerarJsonGold(List<ProcessoSilver> todosProcessos) {
        if (todosProcessos.isEmpty()) return "{}";

        String hostname = todosProcessos.get(0).hostname();

        // Agrupa por timestamp: a chave é a data/hora e o valor é a lista daquele momento.
        TreeMap<String, List<ProcessoSilver>> agrupadosPorTempo = new TreeMap<>();
        for (ProcessoSilver processo : todosProcessos) {
            agrupadosPorTempo
                    .computeIfAbsent(processo.timestamp(), chave -> new ArrayList<>())
                    .add(processo);
        }

        String primeiroTimestamp = agrupadosPorTempo.firstKey();
        String ultimoTimestamp = agrupadosPorTempo.lastKey();
        List<ProcessoSilver> ultimosProcessos = agrupadosPorTempo.get(ultimoTimestamp);

        double totalCpu = ultimosProcessos.stream()
                .mapToDouble(ProcessoSilver::cpuPercent)
                .sum();
        totalCpu = Math.round(totalCpu * 100.0) / 100.0;

        JsonObject json = new JsonObject();
        json.addProperty("hostname", hostname);

        JsonObject periodo = new JsonObject();
        periodo.addProperty("inicio", primeiroTimestamp);
        periodo.addProperty("fim", ultimoTimestamp);
        json.add("periodo", periodo);

        JsonObject kpis = new JsonObject();
        kpis.addProperty("cpu_percentual", totalCpu);
        kpis.addProperty("processos_capturados", ultimosProcessos.size());
        kpis.addProperty("ultima_captura", ultimoTimestamp);
        json.add("kpis", kpis);

        JsonArray processos = new JsonArray();
        for (ProcessoSilver processo : ultimosProcessos) {
            JsonObject item = new JsonObject();
            item.addProperty("PID", processo.pid());
            item.addProperty("Nome_Processo", processo.processName());
            item.addProperty("CPU_percentual", processo.cpuPercent());
            item.addProperty("Memoria_MB", processo.memoryMb());
            item.addProperty("Bytes_Lidos", processo.bytesRead() == null ? 0 : processo.bytesRead());
            item.addProperty("Bytes_Escritos", processo.bytesWritten() == null ? 0 : processo.bytesWritten());
            item.addProperty("Timestamp", processo.timestamp());
            item.addProperty("status", "Normal");
            processos.add(item);
        }
        json.add("processos", processos);

        JsonObject grafico = new JsonObject();
        JsonArray labels = new JsonArray();
        for (String timestamp : agrupadosPorTempo.keySet()) {
            labels.add(timestamp);
        }
        grafico.add("labels", labels);
        grafico.add("datasets", new JsonArray());
        json.add("grafico", grafico);

        return GSON.toJson(json);
    }
}
