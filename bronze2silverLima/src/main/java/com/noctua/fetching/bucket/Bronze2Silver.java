package com.noctua.fetching.bucket;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Bronze2Silver {
    private final S3Client s3Client;
    private final String bucketName;

    public Bronze2Silver(S3Client s3Client, String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    public String searchingFileCsv() {
        ListObjectsRequest requisicao = ListObjectsRequest.builder()
                .bucket(bucketName)
                .build();

        String collectedFile = null;
        for (S3Object object : s3Client.listObjects(requisicao).contents()) {
            if (object.key().contains("csv")
                    && object.key().contains("/01-bronze/")
                    && !object.key().contains("processos")) {
                collectedFile = object.key();
            }
        }
        return collectedFile;
    }

    public void processarArquivo(String pathFile) throws IOException {
        GetObjectRequest download = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(pathFile)
                .build();
        StringBuilder csvTratado = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(s3Client.getObject(download), StandardCharsets.UTF_8))) {
            String linha;
            int contador = 0;
            while ((linha = reader.readLine()) != null) {
                if (contador > 0) {
                    String[] colunas = linha.split(",");
                    // Formatando frequência CPU
                    colunas[3] = String.format(java.util.Locale.US, "%.2f", Double.parseDouble(colunas[3]) / 1000.0);

                    // Formatando colunas sobre RAM
                    colunas[5] = String.format(java.util.Locale.US, "%.2f", Double.parseDouble(colunas[5]) / Math.pow(1024, 3));
                    colunas[6] = String.format(java.util.Locale.US, "%.2f", Double.parseDouble(colunas[6]) / Math.pow(1024, 3));

                    // Formatando coluna de Disco
                    colunas[8] = String.format(java.util.Locale.US, "%.2f", Double.parseDouble(colunas[8]) / Math.pow(1024, 3));

                    // Formatando dados recebidos e enviados
                    colunas[11] = String.format(java.util.Locale.US, "%.2f", Double.parseDouble(colunas[11]) / Math.pow(1024, 2));
                    colunas[12] = String.format(java.util.Locale.US, "%.2f", Double.parseDouble(colunas[12]) / Math.pow(1024, 2));

                    linha = String.join(",", colunas);
                }
                csvTratado.append(linha).append('\n');
                contador++;
            }
        }

        String pathS3 = pathFile.replace("/01-bronze/", "/02-silver/");
        PutObjectRequest upload = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(pathS3)
                .contentType("text/csv")
                .build();
        s3Client.putObject(upload, RequestBody.fromString(csvTratado.toString()));
        System.out.println("CSV tratado enviado ao Silver: " + pathS3);
    }
}
