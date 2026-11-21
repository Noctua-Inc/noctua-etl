package com.noctua.fetching.bucket;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.noctua.conection.bucket.S3Provider;
import com.noctua.dashboard.previsoes.CalculosPrevisoes;
import com.noctua.dashboard.previsoes.LeitorCsv;
import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Monitoramento {
    private static final DateTimeFormatter DATA_CSV = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] METRICAS = {
        "cpu", "ram", "disco", "leitura_mib_s", "escrita_mib_s", "swap", "frequencia_ghz", "ram_livre_gib",
        "disco_livre_gib", "escritas_acumuladas", "leituras_acumuladas", "enviados_mib", "recebidos_mib",
        "erros_recebimento", "sockets_time_wait"
    };

    public void executar(String chave) throws IOException {
        String[] partes = chave.split("/");
        if (partes.length != 4 || !partes[3].equals(partes[2] + "_historico.csv")) return;
        String hostname = partes[2];
        String bucket = Dotenv.configure().ignoreIfMissing().load().get("BUCKET_NAME");
        String pasta = partes[0] + "/03-gold/" + hostname + "/monitoramento/";
        try (S3Client s3 = new S3Provider().getS3Client();
             var entrada = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(chave).build())) {
            String versao = entrada.response().eTag();
            List<String[]> capturas = new LeitorCsv().ler(entrada, hostname);
            if (capturas.isEmpty()) return;
            Documentos documentos = gerar(hostname, capturas);
            publicar(s3, bucket, chave, versao, pasta + "historico.json", documentos.historico());
            publicar(s3, bucket, chave, versao, pasta + "atual.json", documentos.atual());
        }
    }

    static Documentos gerar(String hostname, List<String[]> capturas) {
        JsonArray colunas = new JsonArray();
        colunas.add("horario");
        for (String metrica : METRICAS) colunas.add(metrica);

        JsonArray amostras = new JsonArray();
        for (String[] captura : capturas) {
            JsonArray linha = new JsonArray();
            linha.add(horario(captura));
            for (int i = 0; i < METRICAS.length; i++) linha.add(valor(captura, i));
            amostras.add(linha);
        }

        String[] ultima = capturas.getLast();
        JsonObject atual = new JsonObject();
        atual.addProperty("hostname", hostname);
        atual.addProperty("atualizado_em", horario(ultima));
        JsonObject valores = new JsonObject();
        for (int i = 0; i < METRICAS.length; i++) valores.add(METRICAS[i], valor(ultima, i));
        atual.add("valores", valores);

        JsonObject historico = new JsonObject();
        historico.addProperty("hostname", hostname);
        historico.addProperty("atualizado_em", horario(ultima));
        historico.add("colunas", colunas);
        historico.add("amostras", amostras);
        return new Documentos(atual, historico);
    }

    private static String horario(String[] captura) {
        return CalculosPrevisoes.horario(LocalDateTime.parse(captura[0], DATA_CSV));
    }

    private static com.google.gson.JsonElement valor(String[] captura, int metrica) {
        double numero = Double.parseDouble(captura[metrica + 3]);
        return Double.isFinite(numero) ? new JsonPrimitive(Math.round(numero * 1_000_000) / 1_000_000.0) : JsonNull.INSTANCE;
    }

    private void publicar(S3Client s3, String bucket, String origem, String versao, String destino, JsonObject dados) throws IOException {
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            HeadObjectResponse anterior = metadados(s3, bucket, destino);
            if (anterior != null && versao.equals(anterior.metadata().get("origem-etag"))) return;
            if (!Objects.equals(versao, s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(origem).build()).eTag())) return;
            var pedido = PutObjectRequest.builder().bucket(bucket).key(destino)
                    .contentType("application/json; charset=utf-8").metadata(Map.of("origem-etag", versao));
            if (anterior == null) pedido.overrideConfiguration(c -> c.putHeader("If-None-Match", "*"));
            else pedido.overrideConfiguration(c -> c.putHeader("If-Match", anterior.eTag()));
            try {
                s3.putObject(pedido.build(), RequestBody.fromString(dados.toString()));
                return;
            } catch (S3Exception erro) {
                if (erro.statusCode() != 409 && erro.statusCode() != 412) throw erro;
            }
        }
        throw new IOException("O monitoramento mudou durante a gravação. Reexecute o evento.");
    }

    private HeadObjectResponse metadados(S3Client s3, String bucket, String chave) {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(chave).build());
        } catch (S3Exception erro) {
            if (erro.statusCode() == 404) return null;
            throw erro;
        }
    }

    record Documentos(JsonObject atual, JsonObject historico) {}
}
