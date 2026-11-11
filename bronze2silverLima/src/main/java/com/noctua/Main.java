package com.noctua;

import com.noctua.fetching.bucket.Bronze2Silver;

public class Main {
    public static void main(String[] args) {
        Bronze2Silver bronze2Silver = new Bronze2Silver();

        String bronzePath = "são_paulo_tech_school/01-bronze/laptop-Itz_4thu/laptop-Itz_4thu_2026-11-09_20-38.csv";
        bronze2Silver.downloadFile(bronzePath);
        bronze2Silver.fileTransformation(bronzePath);
        bronze2Silver.uploadFile(bronzePath);
    }
}
