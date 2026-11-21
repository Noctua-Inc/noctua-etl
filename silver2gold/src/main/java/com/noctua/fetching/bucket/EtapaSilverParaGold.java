package com.noctua.fetching.bucket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.noctua.model.ProcessoSilver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

public class EtapaSilverParaGold {

    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final double LIMITE_CPU_PROCESSO_ATENCAO = 10.0;
    private static final double LIMITE_MEMORIA_PROCESSO_ATENCAO_MB = 500.0;

    /**
     * Recebe a lista completa da Silver e gera o JSON da Gold no formato
     * esperado pela Dash-Processos. O Gson cuida do escape de strings.
     */
    public static String gerarJsonGold(List<ProcessoSilver> todosProcessos) {
        if (todosProcessos.isEmpty()) return jsonVazio();

        // O PID 0 representa o tempo ocioso da CPU no Windows. Ele não é um
        // aplicativo consumindo recurso e não deve entrar nos rankings/KPIs.
        List<ProcessoSilver> processosValidos = todosProcessos.stream()
                .filter(processo -> processo.pid() != 0)
                .filter(processo -> !ehProcessoOcioso(processo.processName()))
                .toList();

        if (processosValidos.isEmpty()) return jsonVazio();

        String hostname = processosValidos.get(0).hostname();

        // Agrupa por timestamp: a chave é a data/hora e o valor é a lista daquele momento.
        TreeMap<String, List<ProcessoSilver>> agrupadosPorTempo = new TreeMap<>();
        for (ProcessoSilver processo : processosValidos) {
            agrupadosPorTempo
                    .computeIfAbsent(processo.timestamp(), chave -> new ArrayList<>())
                    .add(processo);
        }

        String primeiroTimestamp = agrupadosPorTempo.firstKey();
        String ultimoTimestamp = agrupadosPorTempo.lastKey();
        List<ProcessoSilver> ultimosProcessos = agrupadosPorTempo.get(ultimoTimestamp);

        Double totalCpu = cpuGlobal(ultimosProcessos);

        JsonObject json = new JsonObject();
        json.addProperty("hostname", hostname);

        JsonObject periodo = new JsonObject();
        periodo.addProperty("inicio", primeiroTimestamp);
        periodo.addProperty("fim", ultimoTimestamp);
        json.add("periodo", periodo);

        JsonObject kpis = new JsonObject();
        kpis.addProperty("cpu_percentual", totalCpu);
        kpis.addProperty("cpu_pico_percentual", agrupadosPorTempo.values().stream()
                .map(EtapaSilverParaGold::cpuGlobal).filter(java.util.Objects::nonNull).max(Double::compare).orElse(null));
        kpis.addProperty("processos_cpu_atencao", ultimosProcessos.stream()
                .filter(processo -> processo.logicalCpus() != null && processo.logicalCpus() > 0
                        && cpuProcessoNaMaquina(processo) >= LIMITE_CPU_PROCESSO_ATENCAO)
                .count());
        kpis.addProperty("processos_capturados", ultimosProcessos.size());
        kpis.addProperty("ultima_captura", ultimoTimestamp);
        kpis.addProperty("memoria_total_mb", totalMemoria(ultimosProcessos));
        kpis.addProperty("memoria_pico_mb", arredondar(agrupadosPorTempo.values().stream()
                .mapToDouble(EtapaSilverParaGold::totalMemoria).max().orElse(0)));
        kpis.addProperty("processos_memoria_atencao", ultimosProcessos.stream()
                .filter(processo -> processo.memoryMb() >= LIMITE_MEMORIA_PROCESSO_ATENCAO_MB)
                .count());
        kpis.addProperty("bytes_lidos_total", totalBytesLidos(ultimosProcessos));
        kpis.addProperty("bytes_escritos_total", totalBytesEscritos(ultimosProcessos));
        json.add("kpis", kpis);

        // Mantemos "processos" ordenado por CPU para compatibilidade com
        // consumidores antigos do latest.json.
        JsonArray processos = serializarProcessos(ordenarPorCpu(ultimosProcessos));
        json.add("processos", processos);

        JsonObject rankings = new JsonObject();
        rankings.add("cpu", serializarProcessos(ordenarPorCpu(ultimosProcessos)));
        rankings.add("ram", serializarProcessos(ultimosProcessos.stream()
                .sorted(Comparator.comparingDouble(ProcessoSilver::memoryMb).reversed()).toList()));
        rankings.add("disco", serializarProcessos(ultimosProcessos.stream()
                .sorted(Comparator.comparingLong(EtapaSilverParaGold::atividadeDisco).reversed()).toList()));
        json.add("rankings", rankings);

        JsonArray labels = new JsonArray();
        for (String timestamp : agrupadosPorTempo.keySet()) {
            labels.add(timestamp);
        }

        JsonObject graficos = criarGraficos(agrupadosPorTempo, labels);
        // O campo antigo continua apontando para CPU para não quebrar clientes
        // que ainda leem apenas "grafico".
        json.add("grafico", graficos.getAsJsonObject("cpu"));
        json.add("graficos", graficos);

        return GSON.toJson(json);
    }

    private static boolean ehProcessoOcioso(String nomeProcesso) {
        if (nomeProcesso == null) return true;

        String normalizado = nomeProcesso
                .trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replace("_", "")
                .replace(" ", "")
                .replace("-", "");

        return normalizado.equals("systemidleprocess")
                || normalizado.equals("idle")
                || normalizado.equals("idleprocess")
                || normalizado.equals("systemidle");
    }

    private static JsonObject criarGraficos(TreeMap<String, List<ProcessoSilver>> agrupados,
                                            JsonArray labels) {
        JsonArray cpu = new JsonArray();
        JsonArray memoria = new JsonArray();
        JsonArray lidos = new JsonArray();
        JsonArray escritos = new JsonArray();

        for (List<ProcessoSilver> processos : agrupados.values()) {
            cpu.add(cpuGlobal(processos));
            memoria.add(arredondar(processos.stream().mapToDouble(ProcessoSilver::memoryMb).sum()));
            lidos.add(totalBytesLidos(processos));
            escritos.add(totalBytesEscritos(processos));
        }

        JsonObject cpuGrafico = grafico(labels, "CPU da máquina (%)", cpu);
        JsonObject ramGrafico = grafico(labels, "Memória dos processos (MiB)", memoria);
        JsonObject discoGrafico = grafico(labels, "Bytes lidos acumulados", lidos);
        JsonArray discoDatasets = discoGrafico.getAsJsonArray("datasets");
        JsonObject escritosDataset = new JsonObject();
        escritosDataset.addProperty("label", "Bytes escritos acumulados");
        escritosDataset.add("data", escritos);
        discoDatasets.add(escritosDataset);

        JsonObject graficos = new JsonObject();
        graficos.add("cpu", cpuGrafico);
        graficos.add("ram", ramGrafico);
        graficos.add("disco", discoGrafico);
        return graficos;
    }

    private static JsonObject grafico(JsonArray labels, String nome, JsonArray dados) {
        JsonObject grafico = new JsonObject();
        grafico.add("labels", labels.deepCopy());
        JsonObject dataset = new JsonObject();
        dataset.addProperty("label", nome);
        dataset.add("data", dados);
        JsonArray datasets = new JsonArray();
        datasets.add(dataset);
        grafico.add("datasets", datasets);
        return grafico;
    }

    private static JsonArray serializarProcessos(List<ProcessoSilver> processos) {
        JsonArray resultado = new JsonArray();
        for (ProcessoSilver processo : processos) {
            JsonObject item = new JsonObject();
            item.addProperty("PID", processo.pid());
            item.addProperty("Nome_Processo", processo.processName());
            item.addProperty("CPU_percentual", processo.cpuPercent());
            item.addProperty("CPU_percentual_maquina", processo.logicalCpus() == null || processo.logicalCpus() < 1
                    ? null : cpuProcessoNaMaquina(processo));
            item.addProperty("Memoria_MB", processo.memoryMb());
            item.addProperty("Bytes_Lidos", processo.bytesRead());
            item.addProperty("Bytes_Escritos", processo.bytesWritten());
            item.addProperty("Timestamp", processo.timestamp());
            item.addProperty("status", processo.logicalCpus() == null || processo.logicalCpus() < 1
                    ? null : status(cpuProcessoNaMaquina(processo)));
            resultado.add(item);
        }
        return resultado;
    }

    private static List<ProcessoSilver> ordenarPorCpu(List<ProcessoSilver> processos) {
        return processos.stream()
                .sorted(Comparator.comparingDouble(EtapaSilverParaGold::cpuProcessoNaMaquina).reversed())
                .toList();
    }

    private static Double cpuGlobal(List<ProcessoSilver> processos) {
        return processos.stream()
                .map(ProcessoSilver::cpuGlobalPercent)
                .filter(java.util.Objects::nonNull)
                .filter(valor -> Double.isFinite(valor) && valor >= 0 && valor <= 100)
                .map(EtapaSilverParaGold::arredondar)
                .findFirst().orElse(null);
    }

    private static double cpuProcessoNaMaquina(ProcessoSilver processo) {
        Integer cpus = processo.logicalCpus();
        double cpu = processo.cpuPercent();
        double resultado = cpus == null || cpus < 1 ? cpu : cpu / cpus;
        return arredondar(normalizarPercentualCpu(resultado));
    }

    private static double normalizarPercentualCpu(double valor) {
        if (!Double.isFinite(valor)) return 0.0;
        return Math.max(0.0, Math.min(100.0, valor));
    }

    private static String status(double cpu) {
        // Sem acentos para formar as classes CSS status-critico/status-atencao.
        if (cpu >= 80) return "Critico";
        if (cpu >= 50) return "Atencao";
        return "Normal";
    }

    private static long atividadeDisco(ProcessoSilver processo) {
        return valorOuZero(processo.bytesRead()) + valorOuZero(processo.bytesWritten());
    }

    private static Long totalBytesLidos(List<ProcessoSilver> processos) {
        var valores = processos.stream().map(ProcessoSilver::bytesRead).filter(java.util.Objects::nonNull).toList();
        return valores.isEmpty() ? null : valores.stream().mapToLong(Long::longValue).sum();
    }

    private static Long totalBytesEscritos(List<ProcessoSilver> processos) {
        var valores = processos.stream().map(ProcessoSilver::bytesWritten).filter(java.util.Objects::nonNull).toList();
        return valores.isEmpty() ? null : valores.stream().mapToLong(Long::longValue).sum();
    }

    private static double totalMemoria(List<ProcessoSilver> processos) {
        return arredondar(processos.stream().mapToDouble(ProcessoSilver::memoryMb).sum());
    }

    private static long valorOuZero(Long valor) {
        return valor == null ? 0 : valor;
    }

    private static double arredondar(double valor) {
        return Math.round(valor * 100.0) / 100.0;
    }

    private static String jsonVazio() {
        JsonObject json = new JsonObject();
        json.add("hostname", com.google.gson.JsonNull.INSTANCE);
        JsonObject periodo = new JsonObject();
        periodo.add("inicio", com.google.gson.JsonNull.INSTANCE);
        periodo.add("fim", com.google.gson.JsonNull.INSTANCE);
        json.add("periodo", periodo);
        JsonObject kpis = new JsonObject();
        kpis.add("cpu_percentual", com.google.gson.JsonNull.INSTANCE);
        kpis.addProperty("processos_capturados", 0);
        kpis.add("ultima_captura", com.google.gson.JsonNull.INSTANCE);
        kpis.addProperty("memoria_total_mb", 0);
        kpis.addProperty("bytes_lidos_total", 0);
        kpis.addProperty("bytes_escritos_total", 0);
        json.add("kpis", kpis);
        json.add("processos", new JsonArray());
        json.add("rankings", new JsonObject());
        JsonObject grafico = new JsonObject();
        grafico.add("labels", new JsonArray());
        grafico.add("datasets", new JsonArray());
        json.add("grafico", grafico);
        json.add("graficos", new JsonObject());
        return GSON.toJson(json);
    }
}
