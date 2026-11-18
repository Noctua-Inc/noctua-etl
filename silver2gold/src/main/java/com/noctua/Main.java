package com.noctua;

import com.noctua.fetching.bucket.Silver2Gold;
import com.noctua.fetching.bucket.Silver2GoldProcessos;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "processos".equals(args[0])) {
            Silver2GoldProcessos etl = new Silver2GoldProcessos();
            String caminhoArquivo = etl.searchingFileCsv();
            etl.downloadFile(caminhoArquivo);
            etl.fileCreation(caminhoArquivo);
            etl.uploadFile(caminhoArquivo);
            return;
        }

        Silver2Gold silver2Gold = new Silver2Gold();
        String caminhoArquivo = silver2Gold.searchingFileCsv();

        silver2Gold.downloadFile(caminhoArquivo);
        silver2Gold.fileCreation(caminhoArquivo);
        silver2Gold.uploadFile(caminhoArquivo);
    }
}
