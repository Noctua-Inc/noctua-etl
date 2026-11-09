package com.noctua.fetching.bucket;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class HistoricoCsv {
    private final DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String hostname(Path arquivo) throws IOException {
        try (BufferedReader leitor = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
            String cabecalho = leitor.readLine();
            String linha = leitor.readLine();
            return linha.split(",", -1)[indice(cabecalho, "HostName")];
        }
    }

    public void atualizar(Path anterior, Path lote, Path destino) throws IOException {
        LocalDateTime referencia = null;
        List<String> linhasNovas = new ArrayList<>();
        String cabecalho;
        int colunaData;

        try (BufferedReader leitor = Files.newBufferedReader(lote, StandardCharsets.UTF_8)) {
            cabecalho = leitor.readLine();
            colunaData = indice(cabecalho, "Timestamp");
            String linha;
            while ((linha = leitor.readLine()) != null) {
                String[] valores = linha.split(",", -1);
                referencia = LocalDateTime.parse(valores[colunaData], formato);
                linhasNovas.add(linha);
            }
        }

        LocalDateTime inicio = referencia.minusDays(7);

        try (BufferedWriter escritor = Files.newBufferedWriter(destino, StandardCharsets.UTF_8)) {
            escritor.write(cabecalho);
            escritor.newLine();

            if (Files.exists(anterior)) {
                try (BufferedReader leitor = Files.newBufferedReader(anterior, StandardCharsets.UTF_8)) {
                    leitor.readLine();
                    String linha;
                    while ((linha = leitor.readLine()) != null) {
                        String[] valores = linha.split(",", -1);
                        LocalDateTime data = LocalDateTime.parse(valores[colunaData], formato);
                        if (!data.isBefore(inicio)) {
                            escritor.write(linha);
                            escritor.newLine();
                        }
                    }
                }
            }

            for (String linha : linhasNovas) {
                String[] valores = linha.split(",", -1);
                LocalDateTime data = LocalDateTime.parse(valores[colunaData], formato);
                if (!data.isBefore(inicio)) {
                    escritor.write(linha);
                    escritor.newLine();
                }
            }
        }
    }

    private int indice(String cabecalho, String nome) throws IOException {
        String[] colunas = cabecalho.split(",", -1);
        for (int i = 0; i < colunas.length; i++) {
            if (colunas[i].equals(nome)) return i;
        }
        throw new IOException("Coluna não encontrada: " + nome);
    }
}
