package com.noctua.fetching.bucket;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class HistoricoCsv {
    private final DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String CABECALHO =
            "Timestamp,HostName,Quantidade_Capturas,Porcentagem_CPU,Porcentagem_RAM,Porcentagem_Disco";

    public void atualizar(Path anterior, Path lote, Path destino) throws IOException {
        List<String> capturasNovas = lerLote(lote);
        ArrayList<Minuto> mediasDoLote = calcularMediasPorMinuto(capturasNovas);

        if (Files.exists(anterior)) {
            List<String> linhasAnteriores = Files.readAllLines(anterior);
            ArrayList<Minuto> mediasAnteriores = calcularMediasPorMinuto(linhasAnteriores);

            for (Minuto anteriorMinuto : mediasAnteriores) {
                Minuto minuto = buscarOuCriarMinuto(mediasDoLote, anteriorMinuto.horario, anteriorMinuto.hostname);
                int total = minuto.quantidade + anteriorMinuto.quantidade;

                minuto.cpu = (minuto.cpu * minuto.quantidade
                        + anteriorMinuto.cpu * anteriorMinuto.quantidade) / total;
                minuto.ram = (minuto.ram * minuto.quantidade
                        + anteriorMinuto.ram * anteriorMinuto.quantidade) / total;
                minuto.disco = (minuto.disco * minuto.quantidade
                        + anteriorMinuto.disco * anteriorMinuto.quantidade) / total;
                minuto.quantidade = total;
            }
        }

        gravarHistorico(destino, mediasDoLote);
    }

    private List<String> lerLote(Path lote) throws IOException {
        return Files.readAllLines(lote);
    }

    private ArrayList<Minuto> calcularMediasPorMinuto(List<String> linhas) throws IOException {
        ArrayList<Minuto> minutos = new ArrayList<>();
        String cabecalho = linhas.get(0);
        int colunaData = indice(cabecalho, "Timestamp");
        int colunaHost = indice(cabecalho, "HostName");
        int colunaCpu = indice(cabecalho, "Porcentagem_CPU");
        int colunaRam = indice(cabecalho, "Porcentagem_RAM");
        int colunaDisco = indice(cabecalho, "Porcentagem_Disco");
        boolean arquivoResumido = CABECALHO.equals(cabecalho);

        // A posição 0 é o cabeçalho. As capturas começam na posição 1.
        for (int i = 1; i < linhas.size(); i++) {
            String[] valores = linhas.get(i).split(",", -1);
            LocalDateTime horario = LocalDateTime.parse(valores[colunaData], formato);
            LocalDateTime horarioMinuto = horario.withSecond(0);

            int quantidade = 1;
            if (arquivoResumido) {
                quantidade = Integer.parseInt(valores[2]);
            }

            Minuto minuto = buscarOuCriarMinuto(minutos, horarioMinuto, valores[colunaHost]);
            minuto.quantidade += quantidade;
            minuto.cpu += Double.parseDouble(valores[colunaCpu]) * quantidade;
            minuto.ram += Double.parseDouble(valores[colunaRam]) * quantidade;
            minuto.disco += Double.parseDouble(valores[colunaDisco]) * quantidade;
        }

        // Até aqui, CPU, RAM e disco guardam somas. A divisão transforma cada soma em média.
        for (Minuto minuto : minutos) {
            minuto.cpu /= minuto.quantidade;
            minuto.ram /= minuto.quantidade;
            minuto.disco /= minuto.quantidade;
        }

        return minutos;
    }

    private Minuto buscarOuCriarMinuto(ArrayList<Minuto> minutos, LocalDateTime horario, String hostname) {
        int posicao = minutos.size() - 1;
        while (posicao >= 0 && minutos.get(posicao).horario.isAfter(horario)) {
            posicao--;
        }

        if (posicao >= 0 && minutos.get(posicao).horario.equals(horario)) {
            return minutos.get(posicao);
        }

        Minuto minuto = new Minuto(horario, hostname);
        minutos.add(posicao + 1, minuto);
        return minuto;
    }

    private void gravarHistorico(Path destino, ArrayList<Minuto> minutos) throws IOException {
        try (BufferedWriter escritor = Files.newBufferedWriter(destino)) {
            escritor.write(CABECALHO);
            escritor.newLine();

            if (minutos.isEmpty()) {
                return;
            }

            LocalDateTime inicio = minutos.get(minutos.size() - 1).horario.minusDays(7);
            for (Minuto minuto : minutos) {
                if (!minuto.horario.isBefore(inicio)) {
                    escritor.write(minuto.horario.format(formato) + "," + minuto.hostname
                            + "," + minuto.quantidade + "," + minuto.cpu + "," + minuto.ram + "," + minuto.disco);
                    escritor.newLine();
                }
            }
        }
    }

    public String hostname(Path arquivo) throws IOException {
        try (BufferedReader leitor = Files.newBufferedReader(arquivo)) {
            String cabecalho = leitor.readLine();
            String linha = leitor.readLine();
            return linha.split(",", -1)[indice(cabecalho, "HostName")];
        }
    }

    private int indice(String cabecalho, String nome) throws IOException {
        String[] colunas = cabecalho.split(",", -1);
        for (int i = 0; i < colunas.length; i++) {
            if (colunas[i].equals(nome)) {
                return i;
            }
        }
        throw new IOException("Coluna não encontrada: " + nome);
    }

    private static class Minuto {
        LocalDateTime horario;
        String hostname;
        int quantidade;
        double cpu;
        double ram;
        double disco;

        Minuto(LocalDateTime horario, String hostname) {
            this.horario = horario;
            this.hostname = hostname;
        }
    }
}
