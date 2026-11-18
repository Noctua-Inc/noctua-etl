package com.noctua.conection.bucket;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

public class S3Provider {
    // Lista de Atributos
    private final AwsCredentialsProvider credentials;

    // Construtor
    public S3Provider() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String accessKey = "";
        String secretKey = "";
        String sessionToken = "";

        for (DotenvEntry campo : dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
            switch (campo.getKey()) {
                case "AWS_ACCESS_KEY_ID" -> accessKey = campo.getValue();
                case "AWS_SECRET_ACCESS_KEY" -> secretKey = campo.getValue();
                case "AWS_SESSION_TOKEN" -> sessionToken = campo.getValue();
            }
        }

        if (!accessKey.isBlank() && !secretKey.isBlank() && !sessionToken.isBlank()) {
            this.credentials = StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(accessKey, secretKey, sessionToken));
        } else {
            this.credentials = DefaultCredentialsProvider.create();
        }
    }

    // Lista de Métodos
    public S3Client getS3Client() {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .build();
    }

    /*
     * Apenas com desencargo de consciência, para a conexão funcionar correta, é necessário realizar os exports de acordo com as suas informações da AWS.
     * Eu fiz a partir do terminal, utilizando a seguinte sequência de comandos:
     * export AWS_ACCESS_KEY_ID=SEU_ACCESS_KEY
     * export AWS_SECRET_ACCESS_KEY=SEU_SECRET_KEY
     * export AWS_SESSION_TOKEN=SEU_SESSION_TOKEN
     * */
}
