package com.noctua.conection.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.noctua.infraestrutura.banco.DBConnectionProvider;
import com.noctua.infraestrutura.s3.S3Provider;
import com.noctua.processamento.historico.LeitorCsv;
import com.noctua.processamento.previsoes.Previsoes;
import com.noctua.processamento.servidores.Monitoramento;
import com.noctua.processamento.processos.Silver2GoldProcessos;
import com.noctua.processamento.alertas.Alertas;
import com.noctua.processamento.correlacao.Correlacao;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;


public class EntradaLambda implements RequestHandler<S3Event, String> {

    @Override
    public String handleRequest(S3Event evento, Context contexto) {

        for (int i = 0; i < evento.getRecords().size(); i++) {

            var objeto = evento.getRecords().get(i).getS3();
            String caminho = objeto.getObject().getUrlDecodedKey();
            String bucket = objeto.getBucket().getName();

            if (Alertas.ehRegistro(caminho)) {
                try {
                    new Alertas().executar(bucket, caminho);
                    contexto.getLogger().log("Resumo de alertas publicado: " + caminho + "\n");
                } catch (Exception erro) {
                    throw new RuntimeException("Erro ao publicar o resumo de alertas.", erro);
                }
                continue;
            }

            if (LeitorCsv.ehHistorico(caminho)) {

                try {
                    boolean recuperado = processarHistorico(bucket, caminho);

                    contexto.getLogger().log(
                            "Monitoramento, previsões e correlação publicados: " + caminho + "\n"
                    );
                    if (recuperado) contexto.getLogger().log("Gold de processos recuperado para: " + caminho + "\n");
                } catch (Exception erro) {
                    throw new RuntimeException(
                            "Erro ao gerar o Gold a partir do histórico.", erro
                    );
                }
                continue;
            }

            if (Silver2GoldProcessos.ehLoteSilver(caminho)) {

                try (S3Client s3 = new S3Provider().getS3Client()) {
                    if (new Silver2GoldProcessos().executar(s3, bucket, caminho)) {
                        contexto.getLogger().log("Gold de processos gerado: " + caminho + "\n");
                    } else {
                        contexto.getLogger().log("Lote de processos anterior ao publicado: " + caminho + "\n");
                    }
                } catch (Exception erro) {
                    throw new RuntimeException(
                            "Erro ao gerar o Gold de processos.", erro
                    );
                }
            } else if (ehLoteMetricas(caminho)) {
                recuperarProcessos(bucket, caminho, contexto);
            } else if (caminho.contains("/02-silver/") && caminho.endsWith("_quarentena.csv")) {
                contexto.getLogger().log("Lote de quarentena mantido na Silver: " + caminho + "\n");
            }
        }

        return "Concluído";
    }

    private static boolean ehLoteMetricas(String chave) {
        String[] partes = chave.split("/", -1);
        return partes.length == 4 && partes[1].equals("02-silver") && !partes[2].isBlank()
                && partes[3].startsWith(partes[2] + "_")
                && partes[3].substring(partes[2].length() + 1).matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}\\.csv");
    }

    private void recuperarProcessos(String bucket, String caminho, Context contexto) {
        try (S3Client s3 = new S3Provider().getS3Client()) {
            if (new Silver2GoldProcessos().recuperarSeAusente(s3, bucket, caminho)) {
                contexto.getLogger().log("Gold de processos recuperado para: " + caminho + "\n");
            }
        } catch (Exception erro) {
            throw new RuntimeException("Erro ao recuperar o Gold de processos a partir da Silver: " + caminho, erro);
        }
    }

    private boolean processarHistorico(String bucket, String caminho) throws Exception {
        DBConnectionProvider conexao = new DBConnectionProvider();
        try (S3Client s3 = new S3Provider().getS3Client()) {
            return processarHistorico(s3, conexao.getJdbcTemplate(), bucket, caminho);
        } finally {
            conexao.fechar();
        }
    }

    static boolean processarHistorico(S3Client s3, JdbcTemplate jdbc, String bucket, String caminho) throws Exception {
        var historico = new LeitorCsv().ler(s3, bucket, caminho);
        new Monitoramento().executar(s3, bucket, historico);
        new Previsoes().executar(s3, jdbc, bucket, historico);
        new Correlacao().executar(s3, jdbc, bucket, historico);
        return new Silver2GoldProcessos().recuperarSeAusente(s3, bucket, caminho);
    }
}
