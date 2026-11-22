package com.noctua.processamento.processos;

import com.google.gson.JsonParser;
import com.noctua.infraestrutura.s3.S3Provider;
import com.noctua.processamento.historico.LeitorCsv;
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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class Silver2GoldProcessos {
    private static final String LOTE_ORIGEM = "lote-silver";
    private static final String MARCADOR_PROCESSOS = "_processos_";
    private final Path diretorioSilver;
    private final Path diretorioGold;

    public Silver2GoldProcessos() {
        this(Path.of(""));
    }

    public Silver2GoldProcessos(Path diretorioBase) {
        diretorioSilver = diretorioBase.resolve("silver");
        diretorioGold = diretorioBase.resolve("gold");
    }

    public static boolean ehLoteSilver(String chave) {
        String[] partes = chave.split("/", -1);
        if (partes.length != 4 || partes[0].isBlank() || !partes[1].equals("02-silver") || partes[2].isBlank()) return false;
        String prefixo = partes[2] + MARCADOR_PROCESSOS;
        return partes[3].regionMatches(true, 0, prefixo, 0, prefixo.length()) && partes[3].endsWith("_silver.csv");
    }

    public boolean executar(S3Client s3, String bucket, String chave) throws IOException {
        if (!ehLoteSilver(chave)) throw new IllegalArgumentException("Chave Silver de processos inválida: " + chave);
        return publicar(s3, bucket, chave, false);
    }

    public boolean recuperarSeAusente(S3Client s3, String bucket, String caminhoMetricas) throws IOException {
        String hostname = extrairHostname(caminhoMetricas);
        if (metadados(s3, bucket, caminhoGold(caminhoMetricas, hostname)) != null) return false;
        String prefixo = caminhoMetricas.substring(0, caminhoMetricas.lastIndexOf('/') + 1);
        var pedido = ListObjectsV2Request.builder().bucket(bucket).prefix(prefixo).build();
        String lote = null;
        for (S3Object objeto : s3.listObjectsV2Paginator(pedido).contents()) {
            if (ehLoteSilver(objeto.key()) && (lote == null || nomeLote(objeto.key()).compareTo(nomeLote(lote)) > 0)) {
                lote = objeto.key();
            }
        }
        if (lote == null) return false;
        return publicar(s3, bucket, lote, true);
    }

    private boolean publicar(S3Client s3, String bucket, String chave, boolean somenteAusente) throws IOException {
        var pedido = GetObjectRequest.builder().bucket(bucket).key(chave).build();
        String destino = caminhoGold(chave, extrairHostname(chave));
        String nomeLote = nomeLote(chave);
        String json;
        try (var entrada = s3.getObject(pedido);
             var leitor = new BufferedReader(new InputStreamReader(entrada, StandardCharsets.UTF_8))) {
            json = gerarJson(leitor);
        }
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            var anterior = metadados(s3, bucket, destino);
            if (anterior != null) {
                String loteAnterior = anterior.metadata().get(LOTE_ORIGEM);
                if (somenteAusente || (loteAnterior != null && loteAnterior.compareTo(nomeLote) > 0)) return false;
            }
            var escrita = PutObjectRequest.builder().bucket(bucket).key(destino)
                    .contentType("application/json; charset=utf-8").metadata(Map.of(LOTE_ORIGEM, nomeLote));
            if (anterior == null) escrita.overrideConfiguration(c -> c.putHeader("If-None-Match", "*"));
            else escrita.overrideConfiguration(c -> c.putHeader("If-Match", anterior.eTag()));
            try {
                s3.putObject(escrita.build(), RequestBody.fromString(json));
                return true;
            } catch (S3Exception erro) {
                if (erro.statusCode() != 409 && erro.statusCode() != 412) throw erro;
            }
        }
        throw new IOException("Conflito ao publicar " + destino);
    }

    private static String nomeLote(String chave) {
        String[] partes = chave.split("/", -1);
        return partes[3].substring(partes[2].length() + MARCADOR_PROCESSOS.length());
    }

    private HeadObjectResponse metadados(S3Client s3, String bucket, String chave) {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(chave).build());
        } catch (S3Exception erro) {
            if (erro.statusCode() == 404) return null;
            throw erro;
        }
    }

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

            Files.createDirectories(diretorioSilver);
            Path arquivoLocal = diretorioSilver.resolve(nomeApenasDoArquivo);

            InputStream inputStream = s3Client.getObject(getObjectRequest, ResponseTransformer.toInputStream());
            Files.copy(inputStream, arquivoLocal, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            System.out.println("Arquivo baixado com sucesso na pasta silver: " + arquivoLocal);
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
        criarJson(diretorioSilver, diretorioGold, nomeArquivo, "latest.json");
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

        String json;
        try (BufferedReader reader = Files.newBufferedReader(origem, StandardCharsets.UTF_8)) {
            json = gerarJson(reader);
        } catch (IOException e) {
            System.err.println("Erro ao ler o CSV da Silver: " + e.getMessage());
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(destino, StandardCharsets.UTF_8)) {
            writer.write(json);
            System.out.println("JSON de processos gerado com sucesso!");
        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
        }
    }

    private String gerarJson(BufferedReader leitor) throws IOException {
        List<ProcessoSilver> processos = new LeitorCsv().lerProcessos(leitor);
        String json = EtapaSilverParaGold.gerarJsonGold(processos);
        JsonParser.parseString(json).getAsJsonObject();
        return json;
    }

    public void uploadFile(String file) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");

        String hostname = extrairHostname(file);
        String pathS3 = caminhoGold(file, hostname);
        Path arquivoJson = diretorioGold.resolve("latest.json");

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

        for (Path caminho : List.of(diretorioSilver, diretorioGold)) {
            if (Files.exists(caminho)) {
                try {
                    Files.walk(caminho)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);

                    System.out.println("A pasta '" + caminho + "' foi apagada.");
                } catch (IOException e) {
                    System.err.println("Erro ao apagar a pasta " + caminho + ": " + e.getMessage());
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
