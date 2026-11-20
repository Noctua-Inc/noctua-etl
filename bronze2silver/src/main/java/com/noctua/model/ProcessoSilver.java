package com.noctua.model;

import java.util.Optional;

/**
 * Contrato único da camada Silver de processos.
 *
 * O CSV bruto (Bronze) possui 10 colunas, ou 12 nas capturas novas com CPU
 * global e CPUs lógicas. A camada Silver mantém essas informações padronizadas.
 */
public record ProcessoSilver(
        String captureId, String hostname, String timestamp, Integer pid,
        String processName, Double cpuPercent, Double memoryMb,
        Long bytesRead, Long bytesWritten, Double cpuGlobalPercent,
        Integer logicalCpus
) {
    public static final String CABECALHO =
            "capture_id,hostname,timestamp,pid,process_name,cpu_percent,memory_mb,bytes_read,bytes_written,cpu_global_percent,logical_cpus";

    public String toCsvLine() {
        return String.join(",",
                captureId, hostname, timestamp, String.valueOf(pid), processName,
                String.valueOf(cpuPercent), String.valueOf(memoryMb),
                bytesRead == null ? "" : String.valueOf(bytesRead),
                bytesWritten == null ? "" : String.valueOf(bytesWritten),
                cpuGlobalPercent == null ? "" : String.valueOf(cpuGlobalPercent),
                logicalCpus == null ? "" : String.valueOf(logicalCpus)
        );
    }

    /** Linha do CSV bruto (Bronze), compatível com lotes antigos e novos. */
    public static Optional<ProcessoSilver> fromBronzeLine(String linhaCsvBruta) {
        String[] colunas = linhaCsvBruta.split(",", -1);
        if (colunas.length >= 12) {
            return montar(colunas, 7, 8, 9, 10, 11);
        }
        if (colunas.length >= 10) {
            return montar(colunas, 7, 8, 9, -1, -1);
        }
        return Optional.empty();
    }

    /** Linha do CSV já tratado (Silver), compatível com lotes antigos e novos. */
    public static Optional<ProcessoSilver> fromSilverLine(String linhaCsvSilver) {
        String[] colunas = linhaCsvSilver.split(",", -1);
        if (colunas.length >= 11) {
            return montar(colunas, 6, 7, 8, 9, 10);
        }
        if (colunas.length >= 9) {
            return montar(colunas, 6, 7, 8, -1, -1);
        }
        return Optional.empty();
    }

    private static Optional<ProcessoSilver> montar(
            String[] colunas, int indiceMemoriaMb, int indiceLidos, int indiceEscritos,
            int indiceCpuGlobal, int indiceCpusLogicos) {
        if (colunas.length <= indiceEscritos) return Optional.empty();

        try {
            String captureId = colunas[0].trim();
            String hostname = colunas[1].trim();
            String timestamp = colunas[2].trim();
            String pidStr = colunas[3].trim();
            String processName = colunas[4].trim();
            String cpuStr = colunas[5].trim();
            String memMbStr = colunas[indiceMemoriaMb].trim();
            String lidosStr = colunas[indiceLidos].trim();
            String escritosStr = colunas[indiceEscritos].trim();

            // Descartar processos inúteis (sem nome).
            if (processName.isEmpty() || processName.equalsIgnoreCase("nan")) {
                return Optional.empty();
            }

            Integer pid = Integer.parseInt(pidStr);
            Double cpuPercent = Double.parseDouble(cpuStr);
            Double memoryMb = Double.parseDouble(memMbStr);

            // I/O vazio é aceito como nulo (não como zero).
            Long bytesRead = lidosStr.isEmpty() ? null : Long.parseLong(lidosStr);
            Long bytesWritten = escritosStr.isEmpty() ? null : Long.parseLong(escritosStr);
            Double cpuGlobalPercent = indiceCpuGlobal >= 0
                    && colunas.length > indiceCpuGlobal
                    && !colunas[indiceCpuGlobal].trim().isEmpty()
                    ? Double.parseDouble(colunas[indiceCpuGlobal].trim()) : null;
            Integer logicalCpus = indiceCpusLogicos >= 0
                    && colunas.length > indiceCpusLogicos
                    && !colunas[indiceCpusLogicos].trim().isEmpty()
                    ? Integer.parseInt(colunas[indiceCpusLogicos].trim()) : null;

            return Optional.of(new ProcessoSilver(
                    captureId, hostname, timestamp, pid, processName,
                    cpuPercent, memoryMb, bytesRead, bytesWritten,
                    cpuGlobalPercent, logicalCpus));
        } catch (Exception e) {
            // Conversão inválida: a linha vai para a quarentena.
            return Optional.empty();
        }
    }
}
