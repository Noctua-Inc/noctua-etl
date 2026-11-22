package com.noctua.processamento.historico;

import com.noctua.processamento.processos.ProcessoSilver;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LeitorCsv {
    public static final String[] CAMPOS_ADICIONAIS = {
        "Swap", "Freq_CPU", "Disponivel_RAM", "Disponivel_Disco", "Qtd_Requisicao_Escrita", "Qtd_Requisicao_Leitura",
        "Dados_Enviados", "Dados_Recebidos", "Pacotes_Perdido", "Sockets_Tw"
    };

    public record Historico(String chave, String empresa, String hostname, String etag, List<String[]> capturas) {}

    public static boolean ehHistorico(String chave) {
        String[] partes = chave.split("/", -1);
        return partes.length == 4 && partes[1].equals("02-silver")
                && partes[3].equals(partes[2] + "_historico.csv");
    }

    public Historico ler(S3Client s3, String bucket, String chave) throws IOException {
        if (!ehHistorico(chave)) throw new IllegalArgumentException("Chave de histórico inválida: " + chave);
        String[] partes = chave.split("/");
        String hostname = partes[2];
        var pedido = GetObjectRequest.builder().bucket(bucket).key(chave).build();
        try (var entrada = s3.getObject(pedido)) {
            String etag = entrada.response().eTag();
            return new Historico(chave, partes[0], hostname, etag, Collections.unmodifiableList(ler(entrada, hostname)));
        }
    }

    public List<ProcessoSilver> lerProcessos(BufferedReader leitor) throws IOException {
        if (leitor.readLine() == null) throw new IOException("CSV de processos sem cabeçalho.");
        List<ProcessoSilver> processos = new ArrayList<>();
        String linha;
        while ((linha = leitor.readLine()) != null) {
            ProcessoSilver.fromSilverLine(linha).ifPresent(processos::add);
        }
        return processos;
    }

    public List<String[]> lerLinhas(Path caminho) throws IOException {
        var linhas = new ArrayList<String[]>();
        try (var leitor = Files.newBufferedReader(caminho, Charset.defaultCharset())) {
            String linha;
            while ((linha = leitor.readLine()) != null) linhas.add(linha.split(","));
        }
        return linhas;
    }

    public ArrayList<String[]> ler(String caminho, String hostname) throws IOException {
        try (InputStream entrada = new FileInputStream(caminho)) {
            return ler(entrada, hostname);
        }
    }

    public ArrayList<String[]> ler(InputStream entrada, String hostname) throws IOException {

        ArrayList<String[]> leituras = new ArrayList<>();
        try (
                InputStreamReader conversor = new InputStreamReader(entrada, StandardCharsets.UTF_8);
                BufferedReader leitor = new BufferedReader(conversor)
        ) {
            String primeiraLinha = leitor.readLine();
            if (primeiraLinha == null) throw new IOException("Arquivo CSV sem cabeçalho.");

            String[] cabecalho = primeiraLinha.replace("\uFEFF", "").split(",", -1);
            int colunaData = coluna(cabecalho, "Timestamp");
            int colunaHost = coluna(cabecalho, "HostName");
            int cpu = coluna(cabecalho, "Porcentagem_CPU");
            int ram = coluna(cabecalho, "Porcentagem_RAM");
            int disco = coluna(cabecalho, "Porcentagem_Disco");
            var nomes = Arrays.asList(cabecalho);
            int leituraDisco = nomes.indexOf("Leitura_Disco_MiB_s");
            int escritaDisco = nomes.indexOf("Escrita_Disco_MiB_s");
            int[] adicionais = Arrays.stream(CAMPOS_ADICIONAIS).mapToInt(nomes::indexOf).toArray();
            int quantidade = -1;
            for (int i = 0; i < cabecalho.length; i++) {
                if (cabecalho[i].equals("Quantidade_Capturas")) {
                    quantidade = i;
                }
            }
            DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            String linha;
            while ((linha = leitor.readLine()) != null) {
                if (linha.isBlank()) continue;
                String[] valores = linha.split(",", -1);
                if (!valores[colunaHost].equals(hostname)) throw new IOException("Hostname diferente dentro do histórico.");
                int contagem;
                if (quantidade < 0) {
                    contagem = 1;
                } else {
                    contagem = Integer.parseInt(valores[quantidade]);
                }
                double valorCpu = Double.parseDouble(valores[cpu]);
                double valorRam = Double.parseDouble(valores[ram]);
                double valorDisco = Double.parseDouble(valores[disco]);
                double valorLeituraDisco = opcional(valores, leituraDisco);
                double valorEscritaDisco = opcional(valores, escritaDisco);
                if (contagem <= 0 || !Double.isFinite(valorCpu) || !Double.isFinite(valorRam) || !Double.isFinite(valorDisco)) {
                    throw new IOException("Captura com quantidade ou percentuais inválidos.");
                }
                String[] captura = new String[]{
                        LocalDateTime.parse(valores[colunaData], formato).withSecond(0).withNano(0).format(formato),
                        hostname,
                        Integer.toString(contagem),
                        Double.toString(valorCpu),
                        Double.toString(valorRam),
                        Double.toString(valorDisco),
                        Double.toString(valorLeituraDisco),
                        Double.toString(valorEscritaDisco)
                };
                String[] leitura = Arrays.copyOf(captura, captura.length + adicionais.length);
                for (int i = 0; i < adicionais.length; i++) {
                    leitura[captura.length + i] = Double.toString(opcional(valores, adicionais[i]));
                }
                leituras.add(leitura);
            }
        }
        return leituras;
    }

    private double opcional(String[] valores, int coluna) {
        if (coluna < 0 || coluna >= valores.length || valores[coluna].isBlank()) return Double.NaN;
        return Double.parseDouble(valores[coluna]);
    }

    private int coluna(String[] cabecalho, String nome) throws IOException {
        for (int i = 0; i < cabecalho.length; i++) {
            if (cabecalho[i].equals(nome)) {
                return i;
            }
        }
        throw new IOException("Coluna não encontrada: " + nome);
    }
}
