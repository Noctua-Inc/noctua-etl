package com.noctua.dashboard.previsoes;


import com.google.gson.JsonObject;
import com.noctua.conection.bucket.S3Provider;
import com.noctua.conection.jdbc.DBConnectionProvider;
import com.noctua.model.ConsultasPrevisoes;
import com.noctua.model.Mainframe;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.InputStream;
import java.time.*;
import java.util.*;

public class Previsoes {
    private final Clock relogio;
    private final S3Client clienteTeste;
    private final JdbcTemplate bancoTeste;
    private final String bucketTeste;

    public Previsoes() {
        this(Clock.system(CalculosPrevisoes.FUSO), null, null, null);
    }

    public Previsoes(Clock relogio, S3Client s3, JdbcTemplate jdbc, String bucket) {
        this.relogio = relogio;
        this.clienteTeste = s3;
        this.bancoTeste = jdbc;
        this.bucketTeste = bucket;
    }

    public void executar(String chave) throws Exception {
        if (!chave.endsWith("_historico.csv") || !chave.contains("/02-silver/")) return;
        if (clienteTeste != null) {
            executar(chave, clienteTeste, bancoTeste, bucketTeste);
            return;
        }
        DBConnectionProvider conexao = new DBConnectionProvider();
        try (S3Client s3 = new S3Provider().getS3Client()) {
            executar(chave, s3, conexao.getJdbcTemplate(), Dotenv.configure().ignoreIfMissing().load().get("BUCKET_NAME"));
        } finally {
            conexao.fechar();
        }
    }

    private void executar(String chave, S3Client s3, JdbcTemplate jdbc, String bucket) throws Exception {

        ConsultasPrevisoes consultas = new ConsultasPrevisoes(jdbc);

        String[] partes = chave.split("/");
        String empresa = partes[0];
        String nome = partes[partes.length - 1];
        String hostname = nome.substring(0, nome.length() - "_historico.csv".length());

        int idEmpresa = consultas.buscarIdEmpresa(empresa);
        Mainframe mainframe = consultas.buscarMainframe(hostname, idEmpresa);

        CalculosPrevisoes calculos = new CalculosPrevisoes();
        try (InputStream entrada = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(chave).build())) {
            calculos.lerHistorico(entrada, mainframe.getHostname());
        }
        LocalDateTime gerado = LocalDateTime.ofInstant(relogio.instant(), CalculosPrevisoes.FUSO);
        String base = empresa + "/03-gold/" + mainframe.getHostname() + "/";
        JsonPrevisoes json = new JsonPrevisoes(s3, bucket);

        JsonObject[] metricas = new JsonObject[5];
        for (int m = 0; m < 5; m++) {
            metricas[m] = calculos.calcular(m, gerado);
            IndicadoresPrevisao.incluirLimites(metricas[m], mainframe.getLimites(),
                    m < 3 ? new String[]{"CPU", "RAM", "DISCO"}[m] : "");
        }

        JsonObject documento = json.criarDocumento(
                idEmpresa,
                mainframe,
                calculos.getAtual(),
                calculos.getCapturasNoMinuto(),
                gerado,
                metricas
        );
        json.publicar(base + "previsoes.json", documento);
        List<String> hostnames = consultas.buscarHostnamesResumo(idEmpresa);
        json.publicarResumo(empresa, idEmpresa, hostnames, gerado);
    }
}
