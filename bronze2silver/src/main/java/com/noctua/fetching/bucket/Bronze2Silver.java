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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;


public class Bronze2Silver {
    DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm");

    // Lista de Métodos
    public String searchingFileCsv () {
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
                        && object.key().contains("/01-bronze/")
                        && !object.key().contains("_processos_")
                        && object.lastModified().isAfter(maisRecente)
//                        && object.key().contains(LocalDateTime.now().format(formato))
                ) {
                    collectedFile = object.key();
                    maisRecente = object.lastModified();
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

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(pathFile)
                    .build();

            String nomeApenasDoArquivo = pathFile.substring(pathFile.lastIndexOf('/') + 1);

            File diretorioBronze = new File("/tmp/bronze");
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

        String nomeApenasDoArquivo = pathFile.substring(pathFile.lastIndexOf('/') + 1);

        File diretorioSilver = new File("/tmp/silver");
        if (!diretorioSilver.exists()) {
            diretorioSilver.mkdirs();
        }

        File arquivoDestino = new File(diretorioSilver, nomeApenasDoArquivo);

        try (
                BufferedReader reader = new BufferedReader(new FileReader(new File(new File("/tmp/bronze"), nomeApenasDoArquivo)));
                BufferedWriter writer = new BufferedWriter(new FileWriter(arquivoDestino))
        ) {

            String primeiraLinha = reader.readLine();

            String[] cabecalho = primeiraLinha.split(",", -1);
            writer.write(primeiraLinha);
            writer.newLine();

            String linha;
            while ((linha = reader.readLine()) != null) {
                String[] valores = linha.split(",", -1);

                for (int i = 0; i < cabecalho.length; i++) {
                    String coluna = cabecalho[i];
                    double divisor = 1;

                    if (coluna.equals("Freq_CPU")) {
                        divisor = 1000.0;
                    } else if (coluna.equals("Total_RAM")
                            || coluna.equals("Disponivel_RAM")
                            || coluna.equals("Disponivel_Disco")) {
                        divisor = Math.pow(1024, 3);
                    } else if (coluna.equals("Dados_Enviados")
                            || coluna.equals("Dados_Recebidos")) {
                        divisor = Math.pow(1024, 2);
                    }

                    if (divisor != 1) {
                        double valor = Double.parseDouble(valores[i]);
                        valores[i] = String.format(Locale.US, "%.2f", valor / divisor);
                    }
                }

                writer.write(String.join(",", valores));
                writer.newLine();
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

        String nomeArquivo = pathFile.substring(pathFile.lastIndexOf('/') + 1);
        File file = new File("/tmp/silver", nomeArquivo);
        String empresa = pathFile.substring(0, pathFile.indexOf("/01-bronze/"));

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");

        try {
            HistoricoCsv historico = new HistoricoCsv();
            String hostname = historico.hostname(file.toPath());
            String prefixoSilver = empresa + "/02-silver/";
            String prefixo = prefixoSilver + hostname + "/";
            String pathS3 = prefixo + nomeArquivo;
            String caminhoHistorico = prefixo + hostname + "_historico.csv";
            Path historicoAnterior = Path.of("/tmp/silver", hostname + "_historico_anterior.csv");
            Path historicoAtualizado = Path.of("/tmp/silver", hostname + "_historico.csv");

            Files.deleteIfExists(historicoAnterior);
            if (!baixarHistorico(s3Client, bucketName, caminhoHistorico, historicoAnterior)
                    && !baixarHistorico(s3Client, bucketName, prefixoSilver + hostname + "_historico.csv", historicoAnterior)) {
                System.out.println("Primeiro histórico desta mainframe.");
            }

            historico.atualizar(historicoAnterior, file.toPath(), historicoAtualizado);
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName).key(caminhoHistorico).build(),
                    RequestBody.fromFile(historicoAtualizado));

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(pathS3)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromFile(file));
            apagarLotesAnteriores(s3Client, bucketName, prefixo, hostname, pathS3);

            System.out.println("Arquivo enviado com sucesso para a S3 com a chave: " + pathS3);

            List<String> diretoriosParaApagar = List.of("/tmp/bronze", "/tmp/silver");

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

        } catch (IOException | S3Exception e) {
            System.err.println("Erro ao fazer upload do arquivo para a S3: " + e.getMessage());
        }
    }

    private boolean baixarHistorico(S3Client s3, String bucket, String chave, Path destino) throws IOException {
        try (InputStream entrada = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(chave).build())) {
            Files.copy(entrada, destino);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private void apagarLotesAnteriores(S3Client s3, String bucket, String prefixo,
                                      String hostname, String chaveAtual) {
        String prefixoMainframe = prefixo + hostname + "_";
        ListObjectsV2Request requisicao = ListObjectsV2Request.builder()
                .bucket(bucket).prefix(prefixoMainframe).build();

        for (ListObjectsV2Response pagina : s3.listObjectsV2Paginator(requisicao)) {
            for (S3Object objeto : pagina.contents()) {
                String chave = objeto.key();
                String finalNome = chave.substring(prefixoMainframe.length());
                // Reconhece apenas a data do lote, sem atingir históricos ou processos.
                boolean loteDatado = finalNome.matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}\\.csv");
                boolean loteAnterior = loteDatado && chave.compareTo(chaveAtual) < 0;

                if (loteAnterior) {
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(chave).build());
                }
            }
        }
    }
}
