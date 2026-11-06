package com.noctua.fetching.bucket;

import com.noctua.conection.bucket.S3Provider;
import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;


public class Bronze2Silver {
    DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm");

    // Construtor
    public Bronze2Silver() {
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
                        && object.key().contains("/01-bronze/")
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

    public void downloadFile (String pathFile) {
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

            Integer retirandoPrefixos = pathFile.indexOf("/01-bronze/") + "/01-bronze/".length();
            String nomeApenasDoArquivo = pathFile.substring(retirandoPrefixos);

            File diretorioBronze = new File("bronze");
            if (!diretorioBronze.exists()) {
                diretorioBronze.mkdirs();
            }

            File arquivoLocal = new File(diretorioBronze, nomeApenasDoArquivo);

            InputStream inputStream = s3Client.getObject(getObjectRequest, ResponseTransformer.toInputStream());
            Files.copy(inputStream, arquivoLocal.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            System.out.println("Arquivo baixado com sucesso na pasta bronze: " + arquivoLocal.getPath());
        } catch (IOException | S3Exception e) {
            System.err.println("Erro ao fazer download: " + e.getMessage());
        }
    }

    public void fileTransformation(String pathFile) {
        if (pathFile == null || pathFile.isEmpty()) {
            System.err.println("Nenhum arquivo fornecido.");
            return;
        }

        Integer retirandoPrefixos = pathFile.indexOf("/01-bronze/") + "/01-bronze/".length();
        String nomeApenasDoArquivo = pathFile.substring(retirandoPrefixos);

        File diretorioSilver = new File("silver");
        if (!diretorioSilver.exists()) {
            diretorioSilver.mkdirs();
        }

        File arquivoDestino = new File(diretorioSilver, nomeApenasDoArquivo);

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("bronze"), nomeApenasDoArquivo)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoDestino))
        ) {

            String linha;
            Integer contador = 0;

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

                    // Formatando coluna de Requisição de Disco
                    colunas[9] = String.format(new Locale("pt", "BR"), "%,d", Long.parseLong(colunas[9]));
                    colunas[10] = String.format(new Locale("pt", "BR"), "%,d", Long.parseLong(colunas[10]));

                    // Formatando dados recebidos e enviados
                    colunas[13] = String.format(java.util.Locale.US, "%.2f", Double.parseDouble(colunas[13]) / Math.pow(1024, 2));
                    colunas[14] = String.format(java.util.Locale.US, "%.2f", Double.parseDouble(colunas[14]) / Math.pow(1024, 2));

                    linha = String.join(",", colunas);
                }

                writer.write(linha);
                writer.newLine();
                contador++;
            }

            System.out.println("Arquivo transformado!");

        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void uploadFile(String pathFile) {
        if (pathFile == null || pathFile.isEmpty()) {
            System.err.println("Nenhum arquivo fornecido.");
            return;
        }

        String fileLocation = pathFile.replace("bronze", "silver").substring(pathFile.indexOf("01-") + ("01-").length());

        File file = new File(fileLocation);

        String pathS3 = pathFile.replace("01-bronze", "02-silver");

        Dotenv dotenv = Dotenv.load();
        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(pathS3)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromFile(file));

            System.out.println("Arquivo enviado com sucesso para a S3 com a chave: " + pathS3);

            List<String> diretoriosParaApagar = List.of("bronze", "silver");

            for (String diretorio : diretoriosParaApagar) {
                Path caminho = Path.of(diretorio);

                if (Files.exists(caminho)) {
                    try (Stream<Path> stream = Files.walk(caminho).sorted(Comparator.reverseOrder())) {

                        for (Path p : (Iterable<Path>) stream::iterator) {
                            Files.delete(p);
                        }

                    } catch (IOException e) {
                        System.err.println("Erro ao apagar o diretório: "+ e.getMessage());
                    }
                } else {
                    System.out.println("Aviso: O diretório '" + diretorio + "' não existe na raiz do projeto.");
                }
            }

        } catch (S3Exception e) {
            System.err.println("Erro ao fazer upload do arquivo para a S3: " + e.getMessage());
        }
    }
}
