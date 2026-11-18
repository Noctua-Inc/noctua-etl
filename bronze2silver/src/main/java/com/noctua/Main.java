package com.noctua;

import com.noctua.fetching.bucket.Bronze2Silver;
import com.noctua.fetching.bucket.Bronze2SilverProcessos;

public class Main {
    public static void main(String[] args) {
        if (args.length > 0 && "processos".equals(args[0])) {
            Bronze2SilverProcessos etl = new Bronze2SilverProcessos();
            String bronzePath = etl.searchingFileCsv();
            etl.downloadFile(bronzePath);
            etl.fileTransformation(bronzePath);
            etl.uploadFile(bronzePath);
            return;
        }

        Bronze2Silver bronze2Silver = new Bronze2Silver();

        String bronzePath = bronze2Silver.searchingFileCsv();
        bronze2Silver.downloadFile(bronzePath);
        bronze2Silver.fileTransformation(bronzePath);
        bronze2Silver.uploadFile(bronzePath);
    }
}
