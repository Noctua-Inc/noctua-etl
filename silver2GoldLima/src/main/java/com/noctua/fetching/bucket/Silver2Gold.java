package com.noctua.fetching.bucket;

import com.noctua.conection.bucket.S3Provider;
import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class Silver2Gold {
    DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm");

    public Silver2Gold() {
    }

    public String searchingFileCsv() {
        Dotenv dotenv = Dotenv.load();
        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");
        String collectedFile = null;

        try {
            ListObjectsV2Request requisicao = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .build();

            Instant maisRecente = Instant.MIN;
            for (S3Object object : s3Client.listObjectsV2Paginator(requisicao).contents()) {
                if (object.key().endsWith(".csv")
                        && object.key().contains("/02-silver/")
                        && !object.key().contains("processos")
                        && !object.key().endsWith("_historico.csv")
//                        && object.lastModified().isAfter(maisRecente)
                ) {
                    collectedFile = object.key();
                    maisRecente = object.lastModified();
                }
            }
        } catch (S3Exception e) {
            System.err.println("Erro ao listar objetos no bucket: " + e.getMessage());
        }

        return collectedFile;
    }

    public void downloadFile(String pathFile) {
        if (pathFile == null || pathFile.isEmpty()) {
            System.err.println("Nenhum arquivo fornecido.");
            return;
        }

        Dotenv dotenv = Dotenv.load();
        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(pathFile)
                    .build();

            String nomeApenasDoArquivo = pathFile.substring(pathFile.lastIndexOf('/') + 1);

            File diretorioSilver = new File("silver");
            if (!diretorioSilver.exists()) {
                diretorioSilver.mkdirs();
            }

            File arquivoLocal = new File(diretorioSilver, nomeApenasDoArquivo);

            InputStream inputStream = s3Client.getObject(getObjectRequest, ResponseTransformer.toInputStream());
            Files.copy(inputStream, arquivoLocal.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            System.out.println("Arquivo baixado com sucesso na pasta silver: " + arquivoLocal.getPath());
        } catch (IOException | S3Exception e) {
            System.err.println("Erro ao fazer download: " + e.getMessage());
        }
    }

    public void fileCreation(String pathFile) {
        if (pathFile == null || pathFile.isBlank()) {
            System.err.println("Nenhum arquivo fornecido.");
            return;
        }

        File diretorioGold = new File("gold");
        if (!diretorioGold.exists()) {
            diretorioGold.mkdir();
        }

        String apenasArquivo = pathFile.substring(pathFile.lastIndexOf('/') + 1);

        String arquivoJsonTotalRegistros = apenasArquivo.replace(".csv", "_todos_registros.json");
        String arquivoJsonCpuRegistros = apenasArquivo.replace(".csv", "_cpu_registros.json");
        String arquivoJsonRamRegistros = apenasArquivo.replace(".csv", "_ram_registros.json");
        String arquivoJsonRedeRegistros = apenasArquivo.replace(".csv", "_rede_registros.json");
        String arquivoJsonDiscoRegistros = apenasArquivo.replace(".csv", "_disco_registros.json");

        creatingFullJson(arquivoJsonTotalRegistros, apenasArquivo, diretorioGold);
        creatingCpuJson(arquivoJsonCpuRegistros, apenasArquivo, diretorioGold);
        creatingRamJson(arquivoJsonRamRegistros, apenasArquivo, diretorioGold);
        creatingNetworkJson(arquivoJsonRedeRegistros, apenasArquivo, diretorioGold);
        creatingDiskJson(arquivoJsonDiscoRegistros, apenasArquivo, diretorioGold);
    }

    private void creatingFullJson(String exampleFile, String file, File directory) {
        File arquivoGold = new File(directory, exampleFile);

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoGold))
        ) {

            Double mediaDadosEnviados = calculateAverage(file, 13);
            Double mediaDadosRecebidos = calculateAverage(file, 14);
            Double somaRede = mediaDadosEnviados + mediaDadosRecebidos;

            writer.write("{");
            writer.newLine();
            writer.write("  \"hora_de_captura\": \"" + extractCaptureTime(file) + "\",");
            writer.newLine();
            writer.write("  \"porcentagem_cpu\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 2)) + ",");
            writer.newLine();
            writer.write("  \"porcentagem_ram\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 4)) + ",");
            writer.newLine();
            writer.write("  \"porcentagem_disco\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 7)) + ",");
            writer.newLine();
            writer.write("  \"media_swap_porcentagem\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 11)) + ",");
            writer.newLine();
            writer.write("  \"media_trafego_rede\": " + String.format(Locale.US, "%.2f", somaRede) + ",");
            writer.newLine();
            writer.write("  \"dados_enviados_medio\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 13)) + ",");
            writer.newLine();
            writer.write("  \"dados_recebidos_medio\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 14)));
            writer.newLine();
            writer.write("}");

            System.out.println("JSON geral gerado com sucesso!");
        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
        }
    }

    private void creatingCpuJson(String exampleFile, String file, File directory) {
        File arquivoGold = new File(directory, exampleFile);

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoGold))
        ) {
            writer.write("{");
            writer.newLine();
            writer.write("  \"hora_de_captura\": \"" + extractCaptureTime(file) + "\",");
            writer.newLine();
            writer.write("  \"media_cpu_porcentagem\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 2)) + ",");
            writer.newLine();
            writer.write("  \"media_cpu_frequencia\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 3)));
            writer.newLine();
            writer.write("}");

            System.out.println("JSON de CPU gerado com sucesso!");
        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
        }
    }

    private void creatingRamJson(String exampleFile, String file, File directory) {
        File arquivoGold = new File(directory, exampleFile);

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoGold))
        ) {
            writer.write("{");
            writer.newLine();
            writer.write("  \"hora_de_captura\": \"" + extractCaptureTime(file) + "\",");
            writer.newLine();
            writer.write("  \"media_ram_porcentagem\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 4)) + ",");
            writer.newLine();
            writer.write("  \"media_swap_porcentagem\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 11)) + ",");
            writer.newLine();
            writer.write("  \"media_ram_disponivel\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 6)));
            writer.newLine();
            writer.write("}");

            System.out.println("JSON de RAM gerado com sucesso!");
        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
        }
    }

    private void creatingNetworkJson(String exampleFile, String file, File directory) {
        File arquivoGold = new File(directory, exampleFile);

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoGold))
        ) {
            writer.write("{");
            writer.newLine();
            writer.write("  \"hora_de_captura\": \"" + extractCaptureTime(file) + "\",");
            writer.newLine();
            writer.write("  \"media_dados_enviados\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 13)) + ",");
            writer.newLine();
            writer.write("  \"media_dados_recebidos\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 14)) + ",");
            writer.newLine();
            writer.write("  \"media_dados_perdidos\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 15)) + ",");
            writer.newLine();
            writer.write("  \"media_sockets_tw\": " + String.format(Locale.US, "%.0f", calculateAverage(file, 16)));
            writer.newLine();
            writer.write("}");

            System.out.println("JSON de Rede gerado com sucesso!");
        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
        }
    }

    private void creatingDiskJson(String exampleFile, String file, File directory) {
        File arquivoGold = new File(directory, exampleFile);

        Long mediaEscrita = Math.round(calculateAverage(file, 9));
        Long mediaLeitura = Math.round(calculateAverage(file, 10));

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoGold))
        ) {
            writer.write("{");
            writer.newLine();
            writer.write("  \"hora_de_captura\": \"" + extractCaptureTime(file) + "\",");
            writer.newLine();
            writer.write("  \"media_disco_porcentagem\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 7)) + ",");
            writer.newLine();
            writer.write("  \"media_disco_disponivel\": " + String.format(Locale.US, "%.2f", calculateAverage(file, 8)) + ",");
            writer.newLine();
            writer.write("  \"media_qtd_requisicao_escrita\": \"" + String.format(new Locale("pt", "BR"), "%,d", mediaEscrita) + "\",");
            writer.newLine();
            writer.write("  \"media_qtd_requisicao_leitura\": \"" + String.format(new Locale("pt", "BR"), "%,d", mediaLeitura) + "\"");
            writer.newLine();
            writer.write("}");

            System.out.println("JSON de Disco gerado com sucesso!");
        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
        }
    }

    private Double calculateAverage(String file, Integer columnIndex) {
        Double soma = 0.0;
        Integer contador = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)))) {
            String linha;
            Integer linhaAtual = 0;

            while ((linha = reader.readLine()) != null) {
                if (linhaAtual > 0) {
                    String[] colunas = linha.split(",");
                    if (columnIndex < colunas.length) {
                        try {
                            Double valor = Double.parseDouble(colunas[columnIndex]);
                            soma += valor;
                            contador++;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                linhaAtual++;
            }
        } catch (IOException e) {
            System.err.println("Erro ao ler o arquivo para o cálculo da média: " + e.getMessage());
        }

        return contador > 0 ? (soma / contador) : 0.0;
    }

    private String extractCaptureTime(String file) {
        String horaCaptura = "Nenhum registro encontrado";

        try (BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)))) {
            String linha;
            Integer linhaAtual = 0;

            while ((linha = reader.readLine()) != null) {
                if (linhaAtual == 1) { // Pega o primeiro registro após o cabeçalho
                    String[] colunas = linha.split(",");
                    if (colunas.length > 17) {
                        String horario = colunas[17].trim();
                        String h = horario.contains(" ") ? horario.substring(horario.indexOf(" ") + 1).trim() : horario;
                        if (h.length() >= 5) {
                            horaCaptura = h.substring(0, 5); // Apenas HH:mm
                        } else {
                            horaCaptura = h;
                        }
                    }
                    break;
                }
                linhaAtual++;
            }
        } catch (IOException e) {
            System.err.println("Erro ao ler o arquivo para extrair a hora de captura: " + e.getMessage());
        }

        return horaCaptura;
    }

    public void uploadFile(String file) {
        Dotenv dotenv = Dotenv.load();
        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");

        File pastaGold = new File("gold");
        String nomeEmpresa = file.substring(0, file.indexOf("/02-silver/"));
        File[] arquivos = pastaGold.listFiles();

        if (arquivos != null) {
            for (File arquivo : arquivos) {
                if (arquivo.isFile() && arquivo.getName().toLowerCase().endsWith(".json")) {
                    String pathS3 = nomeEmpresa + "/03-gold/gustavo/monitoramentoComponentes/" + arquivo.getName();

                    try {
                        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                                .bucket(bucketName)
                                .key(pathS3)
                                .build();

                        s3Client.putObject(putObjectRequest, RequestBody.fromFile(arquivo));
                        System.out.println("Enviado com sucesso para: " + pathS3);

                    } catch (S3Exception e) {
                        System.err.println("Erro ao enviar o arquivo: " + e.getMessage());
                    }
                }
            }
        }

        List<String> diretoriosParaApagar = List.of("silver", "gold");

        for (String nomeDiretorio : diretoriosParaApagar) {
            Path caminho = Paths.get(nomeDiretorio);

            if (Files.exists(caminho)) {
                try {
                    Files.walk(caminho)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);

                    System.out.println("A pasta '" + nomeDiretorio + "' foi apagada.");
                } catch (IOException e) {
                    System.err.println("Erro ao apagar a pasta " + nomeDiretorio + ": " + e.getMessage());
                }
            }
        }
    }
}