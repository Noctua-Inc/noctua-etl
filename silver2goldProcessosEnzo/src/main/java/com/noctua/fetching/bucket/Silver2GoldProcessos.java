package com.noctua.fetching.bucket;

import com.google.gson.JsonParser;
import com.noctua.conection.bucket.S3Provider;
import com.noctua.model.ProcessoSilver;
import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Silver2GoldProcessos {

    public String searchingFileCsv() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
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
                        && object.key().contains("_processos_")
                        && !object.key().endsWith("_quarentena.csv")
                        && object.lastModified().isAfter(maisRecente)) {
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

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
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

        String nomeArquivo = pathFile.substring(pathFile.lastIndexOf('/') + 1);
        criarJson(Path.of("silver"), Path.of("gold"), nomeArquivo, "latest.json");
    }

    // Visibilidade de pacote para permitir testes com diretórios temporários.
    void criarJson(Path diretorioSilver, Path diretorioGold, String nomeArquivo, String nomeSaida) {
        Path origem = diretorioSilver.resolve(nomeArquivo);
        Path destino = diretorioGold.resolve(nomeSaida);

        try {
            Files.createDirectories(diretorioGold);
        } catch (IOException e) {
            System.err.println("Erro ao criar o diretório gold: " + e.getMessage());
            return;
        }

        List<ProcessoSilver> processos = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(origem, StandardCharsets.UTF_8)) {
            reader.readLine(); // Pula o cabeçalho da Silver.

            String linha;
            while ((linha = reader.readLine()) != null) {
                ProcessoSilver.fromSilverLine(linha).ifPresent(processos::add);
            }
        } catch (IOException e) {
            System.err.println("Erro ao ler o CSV da Silver: " + e.getMessage());
            return;
        }

        // Valida o JSON antes de gravar para não publicar um documento inválido.
        String json = EtapaSilverParaGold.gerarJsonGold(processos);
        JsonParser.parseString(json).getAsJsonObject();

        try (BufferedWriter writer = Files.newBufferedWriter(destino, StandardCharsets.UTF_8)) {
            writer.write(json);
            System.out.println("JSON de processos gerado com sucesso!");
        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
        }
    }

    public void uploadFile(String file) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");

        String hostname = extrairHostname(file);
        String pathS3 = caminhoGold(file, hostname);
        File arquivoJson = new File("gold", "latest.json");

        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(pathS3)
                            .contentType("application/json; charset=utf-8")
                            .build(),
                    RequestBody.fromFile(arquivoJson));

            System.out.println("Enviado com sucesso para: " + pathS3);
        } catch (S3Exception e) {
            System.err.println("Erro ao enviar o arquivo: " + e.getMessage());
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

    // Visibilidade de pacote para permitir testes.
    String caminhoGold(String pathFile, String hostname) {
        String nomeEmpresa = pathFile.substring(0, pathFile.indexOf("/02-silver/"));
        return nomeEmpresa + "/03-gold/" + hostname + "/processos/latest.json";
    }

    private String extrairHostname(String pathFile) {
        String[] partes = pathFile.split("/");
        for (int i = 0; i < partes.length - 1; i++) {
            if ("02-silver".equals(partes[i])) {
                return partes[i + 1];
            }
        }
        throw new IllegalArgumentException("Não foi possível identificar o hostname em: " + pathFile);
    }
}
