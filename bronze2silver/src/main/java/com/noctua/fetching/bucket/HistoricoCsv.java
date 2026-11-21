package com.noctua.fetching.bucket;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public class HistoricoCsv {
    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] METRICAS = {
        "Porcentagem_CPU", "Porcentagem_RAM", "Porcentagem_Disco", "Leitura_Disco_MiB_s", "Escrita_Disco_MiB_s",
        "Swap", "Freq_CPU", "Disponivel_RAM", "Disponivel_Disco", "Qtd_Requisicao_Escrita", "Qtd_Requisicao_Leitura",
        "Dados_Enviados", "Dados_Recebidos", "Pacotes_Perdido", "Sockets_Tw"
    };

    public void atualizar(Path anterior, Path lote, Path destino) throws IOException {
        TreeMap<LocalDateTime, Minuto> minutos = new TreeMap<>();
        if (Files.exists(anterior)) ler(anterior, minutos, null);
        // O mesmo minuto pode receber mais de um lote; o conteúdo identifica o replay.
        String origem = UUID.nameUUIDFromBytes(Files.readAllBytes(lote)).toString();
        ler(lote, minutos, origem);
        gravar(destino, minutos);
    }

    private void ler(Path arquivo, TreeMap<LocalDateTime, Minuto> minutos, String lote) throws IOException {
        try (var leitor = Files.newBufferedReader(arquivo)) {
            String cabecalho = leitor.readLine();
            if (cabecalho == null) throw new IOException("CSV sem cabeçalho: " + arquivo);
            var colunas = Arrays.asList(cabecalho.replace("\uFEFF", "").split(",", -1));
            int data = indice(colunas, "Timestamp");
            int host = indice(colunas, "HostName");
            int quantidade = colunas.indexOf("Quantidade_Capturas");
            int origem = colunas.indexOf("Lotes");
            int[] campos = Arrays.stream(METRICAS).mapToInt(colunas::indexOf).toArray();
            int[] contagens = Arrays.stream(METRICAS).mapToInt(m -> colunas.indexOf("Amostras_" + m)).toArray();
            Set<LocalDateTime> incluido = new LinkedHashSet<>();
            String linha;
            while ((linha = leitor.readLine()) != null) {
                if (linha.isBlank()) continue;
                String[] valores = linha.split(",", -1);
                LocalDateTime horario = LocalDateTime.parse(campo(valores, data), FORMATO).withSecond(0);
                String hostname = campo(valores, host);
                Minuto minuto = minutos.computeIfAbsent(horario, chave -> new Minuto(hostname));
                if (!minuto.hostname.equals(hostname)) throw new IOException("Hostnames diferentes no histórico.");
                if (lote != null && minuto.lotes.contains(lote)) continue;
                int capturas = quantidade < 0 ? 1 : Integer.parseInt(campo(valores, quantidade));
                if (capturas <= 0) throw new IOException("Quantidade de capturas inválida.");
                minuto.total += capturas;
                for (int m = 0; m < METRICAS.length; m++) {
                    String texto = campo(valores, campos[m]);
                    if (texto.isBlank()) continue;
                    double valor = Double.parseDouble(texto);
                    if (!Double.isFinite(valor)) continue;
                    int amostras = contagens[m] < 0 ? capturas : Integer.parseInt(campo(valores, contagens[m]));
                    minuto.somas[m] += valor * amostras;
                    minuto.amostras[m] += amostras;
                }
                String origens = campo(valores, origem);
                if (!origens.isBlank()) minuto.lotes.addAll(Arrays.asList(origens.split("\\|")));
                incluido.add(horario);
            }
            if (lote != null) for (LocalDateTime horario : incluido) minutos.get(horario).lotes.add(lote);
        }
    }

    private void gravar(Path destino, TreeMap<LocalDateTime, Minuto> minutos) throws IOException {
        try (BufferedWriter escritor = Files.newBufferedWriter(destino)) {
            escritor.write("Timestamp,HostName,Quantidade_Capturas," + String.join(",", METRICAS));
            for (String metrica : METRICAS) escritor.write(",Amostras_" + metrica);
            escritor.write(",Lotes");
            escritor.newLine();
            if (minutos.isEmpty()) return;
            LocalDateTime inicio = minutos.lastKey().minusDays(28);
            for (Map.Entry<LocalDateTime, Minuto> registro : minutos.tailMap(inicio).entrySet()) {
                Minuto minuto = registro.getValue();
                escritor.write(registro.getKey().format(FORMATO) + "," + minuto.hostname + "," + minuto.total);
                for (int m = 0; m < METRICAS.length; m++) {
                    escritor.write(",");
                    if (minuto.amostras[m] > 0) escritor.write(Double.toString(minuto.somas[m] / minuto.amostras[m]));
                }
                for (int amostras : minuto.amostras) escritor.write("," + amostras);
                escritor.write("," + String.join("|", minuto.lotes));
                escritor.newLine();
            }
        }
    }

    public String hostname(Path arquivo) throws IOException {
        try (var leitor = Files.newBufferedReader(arquivo)) {
            String cabecalho = leitor.readLine();
            String linha = leitor.readLine();
            if (cabecalho == null || linha == null) throw new IOException("Lote sem capturas.");
            int coluna = indice(Arrays.asList(cabecalho.replace("\uFEFF", "").split(",", -1)), "HostName");
            String hostname = campo(linha.split(",", -1), coluna);
            if (hostname.isBlank()) throw new IOException("Hostname ausente no lote.");
            return hostname;
        }
    }

    private int indice(java.util.List<String> colunas, String nome) throws IOException {
        int posicao = colunas.indexOf(nome);
        if (posicao < 0) throw new IOException("Coluna não encontrada: " + nome);
        return posicao;
    }

    private String campo(String[] valores, int coluna) {
        return coluna < 0 || coluna >= valores.length ? "" : valores[coluna].trim();
    }

    private static class Minuto {
        final String hostname;
        final double[] somas = new double[METRICAS.length];
        final int[] amostras = new int[METRICAS.length];
        final Set<String> lotes = new LinkedHashSet<>();
        int total;

        Minuto(String hostname) {
            this.hostname = hostname;
        }
    }
}
