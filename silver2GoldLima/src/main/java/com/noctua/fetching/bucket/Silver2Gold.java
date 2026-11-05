package com.noctua.fetching.bucket;

import com.noctua.conection.bucket.S3Provider;
import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.format.DateTimeFormatter;
import java.util.List;

public class Silver2Gold {
    DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm");

    // Construtor
    public Silver2Gold() {
    }

    // Lista de Métodos
    public String searchingFileCsv () {
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
}
