package com.noctua.fetching.bucket;

import com.noctua.conection.bucket.S3Provider;
import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.*;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class Silver2Gold {
    DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm");

    // Construtor
    public Silver2Gold() {
    }

    // Lista de Métodos
    public String searchingFileCsv() {
        Dotenv dotenv = Dotenv.load();

        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");

        String collectedFile = null;

        try {
            ListObjectsRequest requisicao = ListObjectsRequest.builder()
                    .bucket(bucketName)
                    .build();

            List<S3Object> objects = s3Client.listObjects(requisicao).contents();
            for (S3Object object : objects) {
                if (object.key().contains("csv")
                        && object.key().contains("/02-silver/")
                        && !object.key().contains("processos")
//                        && object.key().contains(LocalDateTime.now().format(formato))
                ) {
                    collectedFile = object.key();
                }
            }
        } catch (
                S3Exception e) {
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

            Integer retirandoPrefixos = pathFile.indexOf("/02-silver/") + "/02-silver/".length();
            String nomeApenasDoArquivo = pathFile.substring(retirandoPrefixos);

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

    public void fileTransformation(String pathFile) {
        if (pathFile == null || pathFile.isBlank()) {
            System.err.println("Nenhum arquivo fornecido.");
            return;
        }

        File diretorioGold = new File("gold");

        if (!diretorioGold.exists()) {
            diretorioGold.mkdir();
        }

        Integer retirandoPrefixos = pathFile.indexOf("/02-silver/") + "/02-silver/".length();
        String apenasArquivo = pathFile.substring(retirandoPrefixos);

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
            String linha;
            Integer contador = 0;
            String[] cabecalho = null;

            writer.write("[");
            writer.newLine();
            writer.write("  {");
            writer.newLine();
            writer.write("\t\t\"estatisticas\": {");
            writer.newLine();
            writer.write("\t\t\t\"horario_pico\": " + "\"" + findPeakTime(file, List.of(2, 4, 7, 11)).trim() + "\"");
            writer.newLine();
            writer.write("\t},");
            writer.newLine();
            writer.write("\t\"registros\": [");
            writer.newLine();

            Boolean primeiraLinha = true;

            while ((linha = reader.readLine()) != null) {
                if (contador == 0) {
                    cabecalho = linha.split(",");
                } else {
                    String[] colunas = linha.split(",");

                    if (!primeiraLinha) {
                        writer.write(",");
                        writer.newLine();
                    }

                    StringBuilder jsonObject = new StringBuilder();

                    jsonObject.append("\t\t{\n");

                    for (Integer i = 0; i < colunas.length; i++) {
                        if (i == 0 || i == 1 || i == 5) {
                            continue;
                        }

                        String chave = cabecalho[i];

                        jsonObject.append("\t\t\t\"").append(chave).append("\":");

                        try {
                            Double.parseDouble(colunas[i]);
                            jsonObject.append(colunas[i]);
                        } catch (NumberFormatException e) {
                            jsonObject.append("\"").append(colunas[i]).append("\"");
                        }

                        if (i < colunas.length - 1) {
                            jsonObject.append(", \n");
                        }
                    }
                    jsonObject.append("\n\t\t}");

                    writer.write(jsonObject.toString());
                    primeiraLinha = false;
                }
                contador++;
            }

            writer.newLine();
            writer.write("\t]");
            writer.newLine();
            writer.write("  }");
            writer.newLine();
            writer.write("]");

            System.out.println("Registros totais transformado para JSON com sucesso!");

        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void creatingCpuJson(String exampleFile, String file, File directory) {
        File arquivoGold = new File(directory, exampleFile);

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoGold))
        ) {
            String linha;
            Integer contador = 0;
            String[] cabecalho = null;

            writer.write("[");
            writer.newLine();
            writer.write("  {");
            writer.newLine();
            writer.write("\t\t\"estatisticas\": {");
            writer.newLine();
            writer.write("\t\t\t\"horario_pico\": " + "\"" + findPeakTime(file, List.of(2)).trim() + "\"" + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_cpu_porcentagem\": " + String.format(java.util.Locale.US, "%.2f", calculateAverage(file, 2)) + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_cpu_frenquecia\": " + String.format(java.util.Locale.US, "%.2f", calculateAverage(file, 3)));
            writer.newLine();
            writer.write("\t},");
            writer.newLine();
            writer.write("\t\"registros\": [");
            writer.newLine();

            Boolean primeiraLinha = true;

            while ((linha = reader.readLine()) != null) {
                if (contador == 0) {
                    cabecalho = linha.split(",");
                } else {
                    String[] colunas = linha.split(",");

                    if (!primeiraLinha) {
                        writer.write(",");
                        writer.newLine();
                    }

                    StringBuilder jsonObject = new StringBuilder();

                    jsonObject.append("\t\t{\n");


                    for (Integer i = 0; i < colunas.length; i++) {
                        if (i != 2 && i != 3) {
                            continue;
                        }

                        String chave = cabecalho[i];

                        jsonObject.append("\t\t\t\"").append(chave).append("\":");

                        try {
                            Double.parseDouble(colunas[i]);
                            jsonObject.append(colunas[i]);
                        } catch (NumberFormatException e) {
                            jsonObject.append("\"").append(colunas[i]).append("\"");
                        }

                        if (i <= 2) {
                            jsonObject.append(", \n");
                        }
                    }
                    jsonObject.append("\n\t\t}");

                    writer.write(jsonObject.toString());
                    primeiraLinha = false;
                }
                contador++;
            }

            writer.newLine();
            writer.write("\t]");
            writer.newLine();
            writer.write("  }");
            writer.newLine();
            writer.write("]");

            System.out.println("Registros de CPU transformado para JSON com sucesso!");

        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void creatingRamJson(String exampleFile, String file, File directory) {
        File arquivoGold = new File(directory, exampleFile);

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoGold))
        ) {
            String linha;
            Integer contador = 0;
            String[] cabecalho = null;

            writer.write("[");
            writer.newLine();
            writer.write("  {");
            writer.newLine();
            writer.write("\t\t\"estatisticas\": {");
            writer.newLine();
            writer.write("\t\t\t\"horario_pico\": " + "\"" + findPeakTime(file, List.of(4, 11)).trim() + "\"" + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_ram_porcentagem\": " + String.format(java.util.Locale.US, "%.2f", calculateAverage(file, 4)) + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_swap_porcentagem\": " + String.format(java.util.Locale.US, "%.2f", calculateAverage(file, 11)) + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_ram_disponivel\": " + String.format(java.util.Locale.US, "%.2f", calculateAverage(file, 6)));
            writer.newLine();
            writer.write("\t},");
            writer.newLine();
            writer.write("\t\"registros\": [");
            writer.newLine();

            Boolean primeiraLinha = true;

            while ((linha = reader.readLine()) != null) {
                Double total_ram = 0.0;
                Double ram_disponivel = 0.0;

                if (contador == 0) {
                    cabecalho = linha.split(",");
                } else {
                    String[] colunas = linha.split(",");

                    if (!primeiraLinha) {
                        writer.write(",");
                        writer.newLine();
                    }

                    StringBuilder jsonObject = new StringBuilder();

                    jsonObject.append("\t\t{\n");

                    for (Integer i = 0; i < colunas.length; i++) {
                        if (i != 4 && i != 6 && i != 11) {
                            continue;
                        }


                        String chave = cabecalho[i];

                        jsonObject.append("\t\t\t\"").append(chave).append("\":");

                        try {
                            Double.parseDouble(colunas[i]);
                            jsonObject.append(colunas[i]);
                        } catch (NumberFormatException e) {
                            jsonObject.append("\"").append(colunas[i]).append("\"");
                        }

                        if (i < 11) {
                            jsonObject.append(", \n");
                        }
                    }
                    jsonObject.append("\n\t\t}");

                    writer.write(jsonObject.toString());
                    primeiraLinha = false;
                }
                contador++;
            }

            writer.newLine();
            writer.write("\t]");
            writer.newLine();
            writer.write("  }");
            writer.newLine();
            writer.write("]");

            System.out.println("Registros RAM transformado para JSON com sucesso!");

        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void creatingNetworkJson(String exampleFile, String file, File directory) {
        File arquivoGold = new File(directory, exampleFile);

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoGold))
        ) {
            String linha;
            Integer contador = 0;
            String[] cabecalho = null;

            writer.write("[");
            writer.newLine();
            writer.write("  {");
            writer.newLine();
            writer.write("\t\t\"estatisticas\": {");
            writer.newLine();
            writer.write("\t\t\t\"horario_pico\": " + "\"" + findPeakTime(file, List.of(13, 14)).trim() + "\"" + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_dados_enviados\": " + String.format(java.util.Locale.US, "%.2f", calculateAverage(file, 13)) + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_dados_recebidos\": " + String.format(java.util.Locale.US, "%.2f", calculateAverage(file, 14)) + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_dados_perdidos\": " + String.format(java.util.Locale.US, "%.2f", calculateAverage(file, 15)) + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_sockets_tw\": " + String.format(java.util.Locale.US, "%.0f", calculateAverage(file, 16)));
            writer.newLine();
            writer.write("\t},");
            writer.newLine();
            writer.write("\t\"registros\": [");
            writer.newLine();

            Boolean primeiraLinha = true;

            while ((linha = reader.readLine()) != null) {
                Double total_ram = 0.0;
                Double ram_disponivel = 0.0;

                if (contador == 0) {
                    cabecalho = linha.split(",");
                } else {
                    String[] colunas = linha.split(",");

                    if (!primeiraLinha) {
                        writer.write(",");
                        writer.newLine();
                    }

                    StringBuilder jsonObject = new StringBuilder();

                    jsonObject.append("\t\t{\n");

                    for (Integer i = 0; i < colunas.length; i++) {
                        if (i != 13 && i != 14 && i != 15 && i != 16) {
                            continue;
                        }


                        String chave = cabecalho[i];

                        jsonObject.append("\t\t\t\"").append(chave).append("\":");

                        try {
                            Double.parseDouble(colunas[i]);
                            jsonObject.append(colunas[i]);
                        } catch (NumberFormatException e) {
                            jsonObject.append("\"").append(colunas[i]).append("\"");
                        }

                        if (i < 16) {
                            jsonObject.append(", \n");
                        }
                    }
                    jsonObject.append("\n\t\t}");

                    writer.write(jsonObject.toString());
                    primeiraLinha = false;
                }
                contador++;
            }

            writer.newLine();
            writer.write("\t]");
            writer.newLine();
            writer.write("  }");
            writer.newLine();
            writer.write("]");

            System.out.println("Registros rede transformado para JSON com sucesso!");

        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void creatingDiskJson(String exampleFile, String file, File directory) {
        File arquivoGold = new File(directory, exampleFile);

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoGold))
        ) {
            String linha;
            Integer contador = 0;
            String[] cabecalho = null;

            Long mediaEscrita = Math.round(calculateAverage(file, 9));
            Long mediaLeitura = Math.round(calculateAverage(file, 10));

            writer.write("[");
            writer.newLine();
            writer.write("  {");
            writer.newLine();
            writer.write("\t\t\"estatisticas\": {");
            writer.newLine();
            writer.write("\t\t\t\"horario_pico\": " + "\"" + findPeakTime(file, List.of(13, 14)).trim() + "\"" + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_disco_porcentagem\": " + String.format(java.util.Locale.US, "%.2f", calculateAverage(file, 7)) + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_disco_disponivel\": " + String.format(java.util.Locale.US, "%.2f", calculateAverage(file, 8)) + ",");
            writer.newLine();
            writer.write("\t\t\t\"media_qtd_requisicao_escrita\": \"" + String.format(new Locale("pt", "BR"), "%,d", mediaEscrita) + "\",");
            writer.newLine();
            writer.write("\t\t\t\"media_qtd_requisicao_leitura\": \"" + String.format(new Locale("pt", "BR"), "%,d", mediaLeitura) + "\"");writer.newLine();
            writer.write("\t},");
            writer.newLine();
            writer.write("\t\"registros\": [");
            writer.newLine();

            Boolean primeiraLinha = true;

            while ((linha = reader.readLine()) != null) {
                Double total_ram = 0.0;
                Double ram_disponivel = 0.0;

                if (contador == 0) {
                    cabecalho = linha.split(",");
                } else {
                    String[] colunas = linha.split(",");

                    if (!primeiraLinha) {
                        writer.write(",");
                        writer.newLine();
                    }

                    StringBuilder jsonObject = new StringBuilder();

                    jsonObject.append("\t\t{\n");

                    for (Integer i = 0; i < colunas.length; i++) {
                        if (i != 7 && i != 8 && i != 9 && i != 10) {
                            continue;
                        }


                        String chave = cabecalho[i];

                        jsonObject.append("\t\t\t\"").append(chave).append("\":");

                        try {
                            Double.parseDouble(colunas[i]);
                            jsonObject.append(colunas[i]);
                        } catch (NumberFormatException e) {
                            jsonObject.append("\"").append(colunas[i]).append("\"");
                        }

                        if (i < 10) {
                            jsonObject.append(", \n");
                        }
                    }
                    jsonObject.append("\n\t\t}");

                    writer.write(jsonObject.toString());
                    primeiraLinha = false;
                }
                contador++;
            }

            writer.newLine();
            writer.write("\t]");
            writer.newLine();
            writer.write("  }");
            writer.newLine();
            writer.write("]");

            System.out.println("Registros disco transformado para JSON com sucesso!");

        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
            e.printStackTrace();
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
                        } catch (NumberFormatException e) {
                        }
                    }
                }
                linhaAtual++;
            }
        } catch (IOException e) {
            System.err.println("Erro ao ler o arquivo para o cálculo da média: " + e.getMessage());
        }

        return (soma / contador);
    }

    private String findPeakTime(String file, List<Integer> listIndex) {
        Double maiorValor = -1.0;
        String horarioDoPico = "Nenhum registro encontrado";
        Integer linhaAtual = 0;

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("silver"), file)))
        ) {
            String linha;

            while ((linha = reader.readLine()) != null) {
                Double valorAtual = 0.0;

                if (linhaAtual > 0) {
                    String[] colunas = linha.split(",");

                    try {
                        String horario = colunas[17];

                        if(listIndex.size() > 1) {
                            Double soma = 0.0;
                            for(Integer i = 0; i < listIndex.size(); i++) {
                                soma += Double.parseDouble(colunas[listIndex.get(i)]);

                                valorAtual = soma / (listIndex.size());
                            }
                        } else {
                            valorAtual = Double.parseDouble(colunas[listIndex.getFirst()]);
                        }

                        if (valorAtual > maiorValor) {
                            maiorValor = valorAtual;
                            horarioDoPico = horario.substring(horario.indexOf(" "));
                        }

                    } catch (NumberFormatException e) {
                    }
                }
                linhaAtual++;
            }
        } catch (IOException e) {
            System.err.println("Erro ao ler o arquivo para encontrar o pico: " + e.getMessage());
        }

        return horarioDoPico;
    }
}
