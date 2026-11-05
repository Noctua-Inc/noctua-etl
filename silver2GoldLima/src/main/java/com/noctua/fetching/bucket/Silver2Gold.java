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

        creatingFullJson(arquivoJsonTotalRegistros, apenasArquivo, diretorioGold);
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

                    jsonObject.append("  {");


                    for (Integer i = 0; i < colunas.length; i++) {
                        if (i == 0 || i == 1) {
                            continue;
                        }

                        String chave;
                        if (cabecalho != null && i < cabecalho.length) {
                            chave = cabecalho[i];
                        } else {
                            chave = "coluna_" + i;
                        }


                        jsonObject.append("\"").append(chave).append("\":");

                        try {
                            Double.parseDouble(colunas[i]);
                            jsonObject.append(colunas[i]);
                        } catch (NumberFormatException e) {
                            jsonObject.append("\"").append(colunas[i]).append("\"");
                        }

                        if (i < colunas.length - 1) {
                            jsonObject.append(", ");
                        }
                    }
                    jsonObject.append("}");

                    writer.write(jsonObject.toString());
                    primeiraLinha = false;
                }
                contador++;
            }

            writer.newLine();
            writer.write("]");

            System.out.println("Arquivo transformado para JSON com sucesso!");

        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
}
