package com.noctua;

import com.noctua.fetching.bucket.Bronze2Silver;

public class Main {
    public static void main(String[] args) {
        Bronze2Silver bronze2Silver = new Bronze2Silver();

        String bronzePath = bronze2Silver.searchingFileCsv();
        bronze2Silver.downloadFile(bronzePath);
        bronze2Silver.fileTransformation(bronzePath);
        bronze2Silver.uploadFile(bronzePath);
    }
}
