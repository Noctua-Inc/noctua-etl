package com.noctua.dashboard.previsoes;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;

public class AtualizadorHistoricoCsv {
    private static final String CABECALHO = "Timestamp,HostName,Quantidade_Capturas,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco";
    private final DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final LeitorCsv leitor = new LeitorCsv();

    public void atualizar(String caminhoLote, String caminhoHistorico, String hostname) throws IOException {
        Path lote = Path.of(caminhoLote).toAbsolutePath().normalize();
        Path historico = Path.of(caminhoHistorico).toAbsolutePath().normalize();

        if (lote.equals(historico)) {
            throw new IOException("O lote e o histórico devem ser arquivos diferentes.");
        }

        ArrayList<String[]> capturas = leitor.ler(lote.toString(), hostname);
        if (Files.exists(historico)) {
            capturas.addAll(leitor.ler(historico.toString(), hostname));
        }
        ArrayList<String[]> historicoAtualizado = consolidar(capturas);

        Path temporario = Files.createTempFile(historico.getParent(), "historico-", ".csv");
        try {
            gravar(temporario, historicoAtualizado);
            try {
                Files.move(temporario, historico, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException erro) {
                Files.move(temporario, historico, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporario);
        }
    }

    public ArrayList<String[]> consolidar(ArrayList<String[]> capturas) {
        ArrayList<String[]> ordenadas = new ArrayList<>(capturas);
        ordenadas.sort(Comparator.comparing(linha -> linha[0]));
        ArrayList<String[]> consolidadas = new ArrayList<>();
        if (ordenadas.isEmpty()) return consolidadas;

        LocalDateTime inicio = LocalDateTime.parse(ordenadas.get(ordenadas.size() - 1)[0], formato).minusDays(7);
        for (String[] captura : ordenadas) {
            LocalDateTime horario = LocalDateTime.parse(captura[0], formato);
            if (horario.isBefore(inicio)) continue;
            int ultimo = consolidadas.size() - 1;
            if (ultimo >= 0 && consolidadas.get(ultimo)[0].equals(captura[0])) {
                String[] anterior = consolidadas.get(ultimo);
                int quantidadeAnterior = Integer.parseInt(anterior[2]);
                int quantidadeNova = Integer.parseInt(captura[2]);
                int total = quantidadeAnterior + quantidadeNova;
                for (int m = 0; m < 3; m++) {
                    double valorAnterior = Double.parseDouble(anterior[m + 3]);
                    double valorNovo = Double.parseDouble(captura[m + 3]);
                    double media = (valorAnterior * quantidadeAnterior + valorNovo * quantidadeNova) / total;
                    anterior[m + 3] = Double.toString(media);
                }
                anterior[2] = Integer.toString(total);
            } else {
                consolidadas.add(captura.clone());
            }
        }
        return consolidadas;
    }

    private void gravar(Path arquivo, ArrayList<String[]> historico) throws IOException {
        try (BufferedWriter escritor = Files.newBufferedWriter(arquivo, StandardCharsets.UTF_8)) {
            escritor.write(CABECALHO);
            escritor.newLine();
            for (String[] linha : historico) {
                escritor.write(String.join(",", linha));
                escritor.newLine();
            }
        }
    }
}
