package com.noctua.conection.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.noctua.fetching.bucket.Bronze2Silver;
import com.noctua.fetching.bucket.Bronze2SilverProcessos;

public class EntradaLambda implements RequestHandler<S3Event, String> {

    @Override
    public String handleRequest(S3Event evento, Context contexto) {

        for (int i = 0; i < evento.getRecords().size(); i++) {

            String caminho = evento.getRecords()
                    .get(i)
                    .getS3()
                    .getObject()
                    .getUrlDecodedKey();

            contexto.getLogger().log("Arquivo recebido: " + caminho + "\n");

            if (caminho.contains("/01-bronze/") && caminho.endsWith(".csv")) {
                if (caminho.contains("_processos_")) {
                    Bronze2SilverProcessos etl = new Bronze2SilverProcessos();
                    etl.downloadFile(caminho);
                    etl.fileTransformation(caminho);
                    etl.uploadFile(caminho);
                } else {
                    Bronze2Silver etl = new Bronze2Silver();
                    etl.downloadFile(caminho);
                    etl.fileTransformation(caminho);
                    etl.uploadFile(caminho);
                }
            }
        }

        return "Concluído";
    }
}
