package com.noctua.model;

import java.util.Optional;

/**
 * Contrato único da camada Silver de processos.
 *
 * O CSV bruto (Bronze) possui 10 colunas, incluindo Memoria_Bytes na posição 6.
 * O CSV da Silver possui 9 colunas: a coluna de bytes é descartada e apenas o
 * valor em MB (posição 7 do Bronze) é mantido, conforme o frontend espera.
 */
public record ProcessoSilver(
        String captureId, String hostname, String timestamp, Integer pid,
        String processName, Double cpuPercent, Double memoryMb,
        Long bytesRead, Long bytesWritten
) {
    public static final String CABECALHO =
            "capture_id,hostname,timestamp,pid,process_name,cpu_percent,memory_mb,bytes_read,bytes_written";

    public String toCsvLine() {
        return String.join(",",
                captureId, hostname, timestamp, String.valueOf(pid), processName,
                String.valueOf(cpuPercent), String.valueOf(memoryMb),
                bytesRead == null ? "" : String.valueOf(bytesRead),
                bytesWritten == null ? "" : String.valueOf(bytesWritten)
        );
    }

    /** Linha do CSV bruto (Bronze), com 10 colunas. */
    public static Optional<ProcessoSilver> fromBronzeLine(String linhaCsvBruta) {
        return montar(linhaCsvBruta.split(",", -1), 7, 8, 9);
    }

    /** Linha do CSV já tratado (Silver), com 9 colunas. */
    public static Optional<ProcessoSilver> fromSilverLine(String linhaCsvSilver) {
        return montar(linhaCsvSilver.split(",", -1), 6, 7, 8);
    }

    private static Optional<ProcessoSilver> montar(
            String[] colunas, int indiceMemoriaMb, int indiceLidos, int indiceEscritos) {
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

            return Optional.of(new ProcessoSilver(
                    captureId, hostname, timestamp, pid, processName,
                    cpuPercent, memoryMb, bytesRead, bytesWritten));
        } catch (Exception e) {
            // Conversão inválida: a linha vai para a quarentena.
            return Optional.empty();
        }
    }
}
