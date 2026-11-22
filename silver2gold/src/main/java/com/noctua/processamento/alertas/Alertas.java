package com.noctua.processamento.alertas;

import com.google.gson.JsonParser;
import com.noctua.infraestrutura.s3.S3Provider;
import com.noctua.infraestrutura.banco.DBConnectionProvider;
import com.noctua.infraestrutura.banco.ConsultasAlertas;
import com.noctua.infraestrutura.banco.ConsultasAlertas.MainframeAlertas;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class Alertas {
    private static final Pattern REGISTRO = Pattern.compile("[^/]+/04-controle/[^/]+/alertas/\\d{4}-\\d{2}-\\d{2}\\.json");

    public static boolean ehRegistro(String caminho) {
        return REGISTRO.matcher(caminho).matches();
    }

    public void executar(String bucket, String caminho) throws Exception {
        String empresa = caminho.substring(0, caminho.indexOf('/'));
        DBConnectionProvider conexao = new DBConnectionProvider();
        try (S3Client s3 = new S3Provider().getS3Client()) {
            var equipamentos = new ConsultasAlertas(conexao.getJdbcTemplate()).listar(empresa);
            publicar(s3, bucket, empresa, equipamentos);
        } finally {
            conexao.fechar();
        }
    }

    void publicar(S3Client s3, String bucket, String empresa, List<MainframeAlertas> equipamentos) throws IOException {
        String destino = empresa + "/03-gold/alertas/resumo.json";
        for (int tentativa = 0; tentativa < 5; tentativa++) {
            String etag = versao(s3, bucket, destino);
            Map<MainframeAlertas, List<ResumoAlertas.Avaliacao>> leituras = new LinkedHashMap<>();
            for (MainframeAlertas equipamento : equipamentos) {
                leituras.put(equipamento, ler(s3, bucket, empresa, equipamento.hostname()));
            }
            var pedido = PutObjectRequest.builder().bucket(bucket).key(destino).contentType("application/json; charset=utf-8");
            if (etag == null) pedido.overrideConfiguration(c -> c.putHeader("If-None-Match", "*"));
            else pedido.overrideConfiguration(c -> c.putHeader("If-Match", etag));
            try {
                s3.putObject(pedido.build(), RequestBody.fromString(ResumoAlertas.gerar(leituras, Instant.now())));
                return;
            } catch (S3Exception erro) {
                if (erro.statusCode() != 409 && erro.statusCode() != 412) throw erro;
            }
        }
        throw new IOException("Conflito ao atualizar o resumo de alertas: " + destino);
    }

    private List<ResumoAlertas.Avaliacao> ler(S3Client s3, String bucket, String empresa, String hostname) throws IOException {
        String prefixo = empresa + "/04-controle/" + hostname + "/alertas/";
        var pedido = ListObjectsV2Request.builder().bucket(bucket).prefix(prefixo).build();
        var avaliacoes = new ArrayList<ResumoAlertas.Avaliacao>();
        for (var objeto : s3.listObjectsV2Paginator(pedido).contents()) {
            String chave = objeto.key();
            if (!ehRegistro(chave)) continue;
            var leitura = GetObjectRequest.builder().bucket(bucket).key(chave).build();
            try (var entrada = new InputStreamReader(s3.getObject(leitura), StandardCharsets.UTF_8)) {
                var lotes = JsonParser.parseReader(entrada).getAsJsonObject();
                for (var lote : lotes.entrySet()) {
                    for (var elemento : lote.getValue().getAsJsonArray()) {
                        var linha = elemento.getAsJsonArray();
                        var horario = OffsetDateTime.parse(linha.get(0).getAsString()).toInstant();
                        String componente = linha.get(1).getAsString();
                        double desvio = linha.get(2).getAsDouble();
                        if (!ResumoAlertas.COMPONENTES.contains(componente) || !Double.isFinite(desvio) || desvio < 0) {
                            throw new IOException("Avaliação de alerta inválida em " + chave);
                        }
                        avaliacoes.add(new ResumoAlertas.Avaliacao(horario, componente, desvio));
                    }
                }
            } catch (RuntimeException erro) {
                throw new IOException("Erro ao ler o registro de alertas " + chave, erro);
            }
        }
        return avaliacoes;
    }

    private String versao(S3Client s3, String bucket, String chave) {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(chave).build()).eTag();
        } catch (S3Exception erro) {
            if (erro.statusCode() == 404) return null;
            throw erro;
        }
    }
}
