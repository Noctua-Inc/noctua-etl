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

        String fileLocation = pathFile.replace("bronze", "silver").substring(pathFile.indexOf("01-") + ("01-").length());

        File file = new File(fileLocation);

        String pathS3 = pathFile.replace("01-bronze", "02-silver");

        Dotenv dotenv = Dotenv.load();
        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");

        try {
            HistoricoCsv historico = new HistoricoCsv();
            String hostname = historico.hostname(file.toPath());
            String prefixo = pathS3.substring(0, pathS3.indexOf("/02-silver/") + "/02-silver/".length());
            String caminhoHistorico = prefixo + hostname + "_historico.csv";
            Path historicoAnterior = Path.of("silver", hostname + "_historico_anterior.csv");
            Path historicoAtualizado = Path.of("silver", hostname + "_historico.csv");

            Files.deleteIfExists(historicoAnterior);
            try (InputStream entrada = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucketName).key(caminhoHistorico).build())) {
                Files.copy(entrada, historicoAnterior);
            } catch (NoSuchKeyException e) {
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

        } catch (IOException | S3Exception e) {
            System.err.println("Erro ao fazer upload do arquivo para a S3: " + e.getMessage());
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
