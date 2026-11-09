package com.noctua;

import com.noctua.fetching.bucket.Previsoes;
// import com.noctua.fetching.bucket.Silver2Gold;

public class Main {
    public static void main(String[] args) throws Exception {
        /*
        Silver2Gold silver2Gold = new Silver2Gold();
        String caminhoArquivo = silver2Gold.searchingFileCsv();

        silver2Gold.downloadFile(caminhoArquivo);
        silver2Gold.fileTransformation(caminhoArquivo);
        silver2Gold.uploadFile(caminhoArquivo);
        */

        Previsoes previsoes = new Previsoes();
        previsoes.executar(args[0]);
    }
}
