package com.noctua.fetching.bucket;

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
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class Bronze2SilverProcessos {

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
                        && object.key().contains("/01-bronze/")
                        && object.key().contains("_processos_")
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
        Path diretorioBronze = Path.of("/tmp/bronze");
        Path diretorioSilver = Path.of("/tmp/silver");

        try {
            Files.createDirectories(diretorioBronze);
            Files.createDirectories(diretorioSilver);
        } catch (IOException e) {
            System.err.println("Erro ao criar os diretórios de trabalho: " + e.getMessage());
            return;
        }

        transformar(diretorioBronze, diretorioSilver, nomeApenasDoArquivo);
    }

    // Visibilidade de pacote para permitir testes com diretórios temporários.
    void transformar(Path diretorioBronze, Path diretorioSilver, String nomeArquivo) {
        Path origem = diretorioBronze.resolve(nomeArquivo);
        Path destino = diretorioSilver.resolve(nomeArquivo.replace(".csv", "_silver.csv"));
        Path quarentena = diretorioSilver.resolve(nomeArquivo.replace(".csv", "_quarentena.csv"));

        int quantidadeSucesso = 0;
        int quantidadeQuarentena = 0;

        try (BufferedReader reader = Files.newBufferedReader(origem, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(destino, StandardCharsets.UTF_8);
             BufferedWriter writerQuarentena = Files.newBufferedWriter(quarentena, StandardCharsets.UTF_8)) {

            reader.readLine(); // Pula o cabeçalho bruto.

            writer.write(ProcessoSilver.CABECALHO);
            writer.newLine();

            String linha;
            while ((linha = reader.readLine()) != null) {
                Optional<ProcessoSilver> resultado = ProcessoSilver.fromBronzeLine(linha);

                if (resultado.isPresent()) {
                    writer.write(resultado.get().toCsvLine());
                    writer.newLine();
                    quantidadeSucesso++;
                } else {
                    writerQuarentena.write(linha);
                    writerQuarentena.newLine();
                    quantidadeQuarentena++;
                }
            }

            System.out.println("Arquivo transformado! Linhas válidas: " + quantidadeSucesso
                    + " | Linhas em quarentena: " + quantidadeQuarentena);
        } catch (IOException e) {
            System.err.println("Ocorreu um erro de I/O ao processar os arquivos: " + e.getMessage());
        }
    }

    public void uploadFile(String pathFile) {
        if (pathFile == null || pathFile.isEmpty()) {
            System.err.println("Nenhum arquivo fornecido.");
            return;
        }

        String nomeArquivo = pathFile.substring(pathFile.lastIndexOf('/') + 1);
        Path arquivoSilver = Path.of("/tmp/silver", nomeArquivo.replace(".csv", "_silver.csv"));

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        S3Client s3Client = new S3Provider().getS3Client();
        String bucketName = dotenv.get("BUCKET_NAME");

        try {
            String hostname = hostname(arquivoSilver);
            String prefixo = empresa(pathFile) + "/02-silver/" + hostname + "/";
            String pathS3 = caminhoSilver(pathFile, hostname, nomeArquivo);
            String caminhoQuarentena = prefixo + nomeArquivo.replace(".csv", "_quarentena.csv");
            Path arquivoQuarentena = Path.of("/tmp/silver", nomeArquivo.replace(".csv", "_quarentena.csv"));

            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName).key(pathS3).build(),
                    RequestBody.fromFile(arquivoSilver));

            if (Files.exists(arquivoQuarentena)) {
                s3Client.putObject(PutObjectRequest.builder()
                                .bucket(bucketName).key(caminhoQuarentena).build(),
                        RequestBody.fromFile(arquivoQuarentena));
            }

            apagarLotesAnteriores(s3Client, bucketName, prefixo, pathS3, caminhoQuarentena);

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
                        System.err.println("Erro ao apagar o diretório: " + e.getMessage());
                    }
                } else {
                    System.out.println("Aviso: O diretório '" + diretorio + "' não existe na raiz do projeto.");
                }
            }
        } catch (IOException | S3Exception e) {
            System.err.println("Erro ao fazer upload do arquivo para a S3: " + e.getMessage());
        }
    }

    // Visibilidade de pacote para permitir testes.
    String caminhoSilver(String pathFile, String hostname, String nomeArquivo) {
        return empresa(pathFile) + "/02-silver/" + hostname + "/"
                + nomeArquivo.replace(".csv", "_silver.csv");
    }

    private String empresa(String pathFile) {
        return pathFile.substring(0, pathFile.indexOf("/01-bronze/"));
    }

    private String hostname(Path arquivo) throws IOException {
        try (BufferedReader leitor = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
            leitor.readLine(); // Cabeçalho da Silver.
            String linha = leitor.readLine();
            if (linha == null) {
                throw new IOException("Arquivo Silver sem registros: " + arquivo);
            }
            return ProcessoSilver.fromSilverLine(linha)
                    .orElseThrow(() -> new IOException("Primeira linha da Silver inválida: " + arquivo))
                    .hostname();
        }
    }

    private void apagarLotesAnteriores(S3Client s3, String bucket, String prefixo,
                                       String chaveAtual, String quarentenaAtual) {
        ListObjectsV2Request requisicao = ListObjectsV2Request.builder()
                .bucket(bucket).prefix(prefixo).build();

        for (ListObjectsV2Response pagina : s3.listObjectsV2Paginator(requisicao)) {
            for (S3Object objeto : pagina.contents()) {
                String chave = objeto.key();

                if (eLoteAnterior(chave, prefixo, chaveAtual, quarentenaAtual)) {
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(chave).build());
                }
            }
        }
    }

    // Visibilidade de pacote para permitir testes da regra de limpeza.
    boolean eLoteAnterior(String chave, String prefixo, String chaveAtual, String quarentenaAtual) {
        if (!chave.startsWith(prefixo)) return false;
        String nome = chave.substring(prefixo.length());
        // Reconhece apenas lotes de processos, sem atingir outros arquivos da Silver.
        boolean loteDeProcessos = nome.matches(".*_processos_.*_(silver|quarentena)\\.csv");
        return loteDeProcessos
                && !chave.equals(chaveAtual)
                && !chave.equals(quarentenaAtual)
                && chave.compareTo(chaveAtual) < 0;
    }
}
