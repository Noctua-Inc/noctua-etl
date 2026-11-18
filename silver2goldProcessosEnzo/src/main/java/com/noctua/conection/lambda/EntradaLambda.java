package com.noctua.conection.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.noctua.fetching.bucket.Silver2GoldProcessos;

public class EntradaLambda implements RequestHandler<S3Event, String> {

    @Override
    public String handleRequest(S3Event evento, Context contexto) {

        for (int i = 0; i < evento.getRecords().size(); i++) {

            String caminho = evento.getRecords()
                    .get(i)
                    .getS3()
                    .getObject()
                    .getUrlDecodedKey();

            if (caminho.contains("/02-silver/")
                    && caminho.contains("_processos_")
                    && !caminho.endsWith("_quarentena.csv")) {

                try {
                    Silver2GoldProcessos etl = new Silver2GoldProcessos();

                    etl.downloadFile(caminho);
                    etl.fileCreation(caminho);
                    etl.uploadFile(caminho);

                    contexto.getLogger().log(
                            "Gold de processos gerado: " + caminho + "\n"
                    );
                } catch (Exception erro) {
                    throw new RuntimeException(
                            "Erro ao gerar o Gold de processos.", erro
                    );
                }
            }
        }

        return "Concluído";
    }
}
