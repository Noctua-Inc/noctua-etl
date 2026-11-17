package com.noctua;

import com.noctua.fetching.bucket.Bronze2SilverProcessos;

public class Main {
    public static void main(String[] args) {
        Bronze2SilverProcessos bronze2Silver = new Bronze2SilverProcessos();

        String bronzePath = bronze2Silver.searchingFileCsv();
        bronze2Silver.downloadFile(bronzePath);
        bronze2Silver.fileTransformation(bronzePath);
        bronze2Silver.uploadFile(bronzePath);
    }
}
