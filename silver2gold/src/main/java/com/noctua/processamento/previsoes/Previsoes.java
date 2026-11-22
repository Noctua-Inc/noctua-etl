package com.noctua.processamento.previsoes;


import com.google.gson.JsonObject;
import com.noctua.infraestrutura.s3.S3Provider;
import com.noctua.infraestrutura.banco.DBConnectionProvider;
import com.noctua.infraestrutura.banco.ConsultasPrevisoes;
import com.noctua.infraestrutura.banco.Mainframe;
import com.noctua.processamento.historico.LeitorCsv;
import com.noctua.processamento.historico.LeitorCsv.Historico;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

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
        if (!LeitorCsv.ehHistorico(chave)) return;
        LeitorCsv leitor = new LeitorCsv();
        if (clienteTeste != null) {
            executar(clienteTeste, bancoTeste, bucketTeste, leitor.ler(clienteTeste, bucketTeste, chave));
            return;
        }
        DBConnectionProvider conexao = new DBConnectionProvider();
        try (S3Client s3 = new S3Provider().getS3Client()) {
            String bucket = Dotenv.configure().ignoreIfMissing().load().get("BUCKET_NAME");
            executar(s3, conexao.getJdbcTemplate(), bucket, leitor.ler(s3, bucket, chave));
        } finally {
            conexao.fechar();
        }
    }

    public void executar(S3Client s3, JdbcTemplate jdbc, String bucket, Historico historico) throws Exception {

        ConsultasPrevisoes consultas = new ConsultasPrevisoes(jdbc);

        String empresa = historico.empresa();
        String hostname = historico.hostname();

        int idEmpresa = consultas.buscarIdEmpresa(empresa);
        Mainframe mainframe = consultas.buscarMainframe(hostname, idEmpresa);

        CalculosPrevisoes calculos = new CalculosPrevisoes();
        calculos.carregarHistorico(historico.capturas());
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
