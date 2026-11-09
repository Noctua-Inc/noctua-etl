package com.noctua.fetching.bucket;

import com.noctua.conection.bucket.S3Provider;
import com.noctua.conection.jdbc.DBConnectionProvider;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.jdbc.core.JdbcTemplate;
import com.noctua.model.Limite;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

public class Previsoes {

    public void executar(String chave) throws Exception {
        String[] partes = chave.split("/", -1);
        String sufixo = "_historico.csv";
        String prefixoEmpresa = partes[0];
        String nomeArquivo = partes[2];
        String hostname = nomeArquivo.substring(0, nomeArquivo.length() - sufixo.length());

        consultarLimites(prefixoEmpresa, hostname);
        lerHistorico(chave);
    }

    private long lerHistorico(String chave) throws IOException {
        String bucket = Dotenv.load().get("BUCKET_NAME");

        GetObjectRequest requisicao = GetObjectRequest.builder()
                .bucket(bucket)
                .key(chave)
                .build();

        try (S3Client s3 = new S3Provider().getS3Client();
             BufferedReader leitor = new BufferedReader(
                     new InputStreamReader(s3.getObject(requisicao), StandardCharsets.UTF_8))) {
            leitor.readLine();
            long quantidadeCapturas = 0;
            while (leitor.readLine() != null) {
                quantidadeCapturas++;
            }
            System.out.println(quantidadeCapturas);
            return quantidadeCapturas;
        }
    }

    private List<Limite> consultarLimites(String prefixoEmpresa, String hostname) throws SQLException {
        DBConnectionProvider conexao = new DBConnectionProvider();

        try {
            JdbcTemplate jdbc = conexao.getJdbcTemplate();

            Integer idEmpresa = identificarEmpresa(jdbc, prefixoEmpresa);

            String sqlMainframe =
                """
                SELECT id_mainframe
                FROM mainframe
                WHERE hostname = ?
                    AND fk_empresa = ?
                """;

            Integer idMainframe = jdbc.queryForObject(
                    sqlMainframe,
                    Integer.class,
                    hostname,
                    idEmpresa
            );

            String sql =
                """
                SELECT c.tipo, p.pico_min, p.pico_max
                FROM parametro p
                JOIN componente c
                    ON c.id_componente = p.id_componente
                WHERE p.id_mainframe = ?
                ORDER BY c.tipo
                """;

            return jdbc.query(
                    sql,
                    new BeanPropertyRowMapper<>(Limite.class),
                    idMainframe
            );

        } finally {
            conexao.fechar();
        }
    }

    private int identificarEmpresa(JdbcTemplate jdbc, String prefixoEmpresa) {
        String sql = """
                SELECT id_empresa
                FROM empresa
                WHERE REPLACE(LOWER(razao_social), ' ', '_') = ?
                """;

        return jdbc.queryForObject(sql, Integer.class, prefixoEmpresa);
    }
}
