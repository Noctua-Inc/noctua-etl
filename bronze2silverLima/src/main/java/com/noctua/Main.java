package com.noctua;

import com.noctua.conection.bucket.S3Provider;
import com.noctua.fetching.bucket.Bronze2Silver;
import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String bucketName = dotenv.get("BUCKET_NAME");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("Preencha BUCKET_NAME no .env do Bronze2Silver.");
        }

        try (S3Client s3Client = new S3Provider().getS3Client(dotenv)) {
            Bronze2Silver bronze2Silver = new Bronze2Silver(s3Client, bucketName);
            String bronzePath = bronze2Silver.searchingFileCsv();
            if (bronzePath == null) {
                System.out.println("Nenhum CSV de métricas encontrado no Bronze.");
                return;
            }
            bronze2Silver.processarArquivo(bronzePath);
        }
    }
}
