package com.noctua;

import com.noctua.conection.bucket.S3Provider;
import com.noctua.fetching.bucket.Bronze2Silver;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        Bronze2Silver bronze2Silver = new Bronze2Silver();

        String bronzePath = bronze2Silver.searchingFileCsv();
        bronze2Silver.downloadFile(bronzePath);
        bronze2Silver.fileTransformation(bronzePath);
        bronze2Silver.uploadFile(bronzePath);
    }
}
