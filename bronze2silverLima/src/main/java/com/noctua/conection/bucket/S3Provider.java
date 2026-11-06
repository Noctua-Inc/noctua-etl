package com.noctua.conection.bucket;

import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

public class S3Provider {
    public S3Client getS3Client(Dotenv dotenv) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(dotenv.get("AWS_REGION", "us-east-1")));

        String accessKey = dotenv.get("AWS_ACCESS_KEY_ID", "");
        String secretKey = dotenv.get("AWS_SECRET_ACCESS_KEY", "");
        String sessionToken = dotenv.get("AWS_SESSION_TOKEN", "");

        if (!accessKey.isBlank() || !secretKey.isBlank() || !sessionToken.isBlank()) {
            if (accessKey.isBlank() || secretKey.isBlank()) {
                throw new IllegalArgumentException("Preencha AWS_ACCESS_KEY_ID e AWS_SECRET_ACCESS_KEY no .env.");
            }

            AwsCredentials credentials;
            if (sessionToken.isBlank()) {
                credentials = AwsBasicCredentials.create(accessKey, secretKey);
            } else {
                credentials = AwsSessionCredentials.create(accessKey, secretKey, sessionToken);
            }
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }

        return builder.build();
    }
}
