package com.noctua;

import com.noctua.fetching.bucket.Silver2GoldProcessos;

public class Main {
    public static void main(String[] args) throws Exception {
        Silver2GoldProcessos silver2Gold = new Silver2GoldProcessos();
        String caminhoArquivo = silver2Gold.searchingFileCsv();

        silver2Gold.downloadFile(caminhoArquivo);
        silver2Gold.fileCreation(caminhoArquivo);
        silver2Gold.uploadFile(caminhoArquivo);
    }
}
