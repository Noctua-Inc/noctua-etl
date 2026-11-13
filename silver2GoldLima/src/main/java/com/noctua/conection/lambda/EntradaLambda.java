package com.noctua.conection.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.noctua.fetching.bucket.Previsoes;

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
                    && caminho.endsWith("_historico.csv")) {

                try {
                    Previsoes previsoes = new Previsoes();
                    previsoes.executar(caminho);

                    contexto.getLogger().log(
                            "Previsões geradas: " + caminho + "\n"
                    );
                } catch (Exception erro) {
                    throw new RuntimeException(
                            "Erro ao gerar as previsões.", erro
                    );
                }
            }
        }

        return "Concluído";
    }
}